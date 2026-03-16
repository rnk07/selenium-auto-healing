/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.github.autoheal.healing.driver;

import io.github.autoheal.healing.db.HealingDatabase;
import io.github.autoheal.healing.report.HealingReport;
import io.github.autoheal.healing.strategy.AttributeFallbackStrategy;
import io.github.autoheal.healing.strategy.CssFallbackStrategy;
import io.github.autoheal.healing.strategy.IHealingStrategy;
import io.github.autoheal.healing.strategy.SiblingWalkStrategy;
import io.github.autoheal.healing.strategy.DomSimilarityStrategy;
import io.github.autoheal.healing.strategy.IframeStrategy;
import io.github.autoheal.healing.strategy.LlmHealingStrategy;
import io.github.autoheal.healing.strategy.StaleElementStrategy;
import io.github.autoheal.healing.strategy.ShadowDomStrategy;
import io.github.autoheal.healing.strategy.XPathFallbackStrategy;
import org.openqa.selenium.By;
import org.openqa.selenium.Capabilities;
import org.openqa.selenium.Cookie;
import org.openqa.selenium.HasCapabilities;
import org.openqa.selenium.JavascriptExecutor;
import org.openqa.selenium.NoSuchElementException;
import org.openqa.selenium.StaleElementReferenceException;
import org.openqa.selenium.OutputType;
import org.openqa.selenium.TakesScreenshot;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebDriverException;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.logging.Logs;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * AutoHealingDriver — a WebDriver wrapper that automatically heals broken locators.
 *
 * <p>When {@code findElement(By)} throws {@link NoSuchElementException}, this driver
 * intercepts the failure, disables implicit wait to prevent hanging, runs the
 * configured {@link IHealingStrategy} chain in priority order, and returns the
 * healed element if found — all transparently, with zero changes required to
 * existing Page Objects or test code.
 *
 * <p><b>Usage:</b>
 * <pre>{@code
 * // Build the driver — only this line changes in BaseTest
 * AutoHealingDriver driver = AutoHealingDriver.builder(new ChromeDriver()).build();
 *
 * // Use exactly like a normal WebDriver
 * driver.get("https://example.com");
 * driver.findElement(By.id("broken-id")).click(); // healed transparently
 *
 * // Write healing report in @AfterSuite
 * driver.getHealingReport().writeReport();
 * driver.quit();
 * }</pre>
 *
 * <p><b>Custom strategies:</b>
 * <pre>{@code
 * AutoHealingDriver driver = AutoHealingDriver.builder(new ChromeDriver())
 *         .withStrategy(new MyStrategy())
 *         .withoutStrategy(SiblingWalkStrategy.class)
 *         .build();
 * }</pre>
 */
public class AutoHealingDriver implements WebDriver, JavascriptExecutor,
        TakesScreenshot, HasCapabilities {

    private static final Logger LOG = LoggerFactory.getLogger(AutoHealingDriver.class);

    private final WebDriver delegate;
    private final List<IHealingStrategy> strategies;
    private final HealingReport report;

    /** Tracks the implicit wait configured by the test — restored after every heal. */
    private Duration configuredImplicitWait = Duration.ofSeconds(0);

    /** Tracks locator strings successfully found in the current test run.
     *  Used by VisualHealingStrategy to exclude already-found fingerprints
     *  when healing, preventing username fingerprint from matching button. */
    private final java.util.Set<String> usedLocators =
            java.util.Collections.synchronizedSet(new java.util.HashSet<>());

    /** Static ThreadLocal so VisualHealingStrategy can read usedLocators
     *  without needing a reference to AutoHealingDriver. */
    public static final ThreadLocal<java.util.Set<String>> CURRENT_USED_LOCATORS =
            ThreadLocal.withInitial(java.util.HashSet::new);

    private AutoHealingDriver(WebDriver delegate,
                               List<IHealingStrategy> strategies,
                               HealingReport report) {
        this.delegate   = delegate;
        this.strategies = strategies;
        this.report     = report;
    }

    // =========================================================================
    // Strategy management — runtime registration
    // =========================================================================

    /** Returns the set of locators successfully found in the current test run. */
    public java.util.Set<String> getUsedLocators() { return usedLocators; }

    /** Clears the used locators tracking — called by AutoHealing listener before each test. */
    public void clearUsedLocators() {
        usedLocators.clear();
        CURRENT_USED_LOCATORS.get().clear();
    }

    /**
     * Adds a healing strategy to the chain at runtime.
     *
     * <p>Strategies are automatically sorted by priority after insertion.
     * This allows external modules (such as selenium-auto-healing-visual)
     * to register additional strategies without needing to rebuild the driver.
     *
     * @param strategy the strategy to add
     */
    public void addStrategy(IHealingStrategy strategy) {
        if (strategy == null) return;
        // Remove existing strategy of same type to avoid duplicates
        strategies.removeIf(s -> s.getClass().equals(strategy.getClass()));
        strategies.add(strategy);
        strategies.sort(Comparator.comparingInt(IHealingStrategy::getPriority));
        LOG.info("[AutoHealingDriver] Registered strategy: {} (priority {})",
                strategy.getName(), strategy.getPriority());
    }

    /**
     * Returns an unmodifiable view of the current strategy chain.
     */
    public List<IHealingStrategy> getStrategies() {
        return java.util.Collections.unmodifiableList(strategies);
    }

    // =========================================================================
    // Core: findElement with healing
    // =========================================================================

    /**
     * Attempts {@code findElement} on the underlying driver. Handles two failure modes:
     * <ul>
     *   <li>{@link NoSuchElementException} — locator is broken, runs healing strategy chain</li>
     *   <li>{@link StaleElementReferenceException} — DOM was refreshed, re-finds the element
     *       using the same {@link By} locator (common in React/Angular SPAs after re-render)</li>
     * </ul>
     *
     * @param by the locator to find
     * @return the found (or healed) element
     * @throws NoSuchElementException if all strategies fail
     * @throws StaleElementReferenceException if re-find also fails after DOM refresh
     */
    @Override
    public WebElement findElement(By by) {
        try {
            WebElement element = delegate.findElement(by);
            // Track this locator as "used" in current test
            usedLocators.add(by.toString());
            CURRENT_USED_LOCATORS.get().add(by.toString());
            return element;
        } catch (StaleElementReferenceException staleException) {
            // DOM was refreshed (React/Angular re-render, AJAX update)
            // Re-find the element using the same locator — element still exists, just stale
            LOG.warn("StaleElementReferenceException for \'{}\'. Re-finding after DOM refresh.", by);
            try {
                WebElement refound = delegate.findElement(by);
                LOG.info("Re-found stale element: \'{\'}", by);
                report.log(by, by, "StaleElementRefind");
                return refound;
            } catch (Exception e) {
                LOG.error("Could not re-find stale element \'{}\' after DOM refresh.", by);
                throw staleException;
            }
        } catch (NoSuchElementException originalException) {

            LOG.warn("NoSuchElementException for \'{}\'. Starting healing.", by);

            // Step 1: Check historical database first — instant recall, no DOM probing
            String currentUrl = getCurrentPageUrl();
            By fromDb = HealingDatabase.getInstance().lookup(by, currentUrl);
            if (fromDb != null) {
                try {
                    List<WebElement> dbFound = delegate.findElements(fromDb);
                    WebElement dbVisible = dbFound.stream()
                            .filter(e -> { try { return e.isDisplayed(); } catch (Exception ex) { return false; } })
                            .findFirst().orElse(null);
                    if (dbVisible != null) {
                        LOG.info("Healed instantly from history: \'{}\' -> \'{\'}", by, fromDb);
                        report.log(by, fromDb, "HistoricalDatabase");
                        return dbVisible;
                    }
                } catch (Exception ex) {
                    LOG.debug("DB lookup element no longer valid, falling back to strategy chain.");
                }
            }

            // Step 2: Run strategy chain — disable implicit wait to prevent hanging
            LOG.info("Running strategy chain ({} strategies) for \'{\'}", strategies.size(), by);
            delegate.manage().timeouts().implicitlyWait(Duration.ZERO);

            try {
                for (IHealingStrategy strategy : strategies) {
                    try {
                        By healed = strategy.heal(delegate, by);
                        if (healed == null) continue;

                        List<WebElement> found = delegate.findElements(healed);
                        WebElement visible = found.stream()
                                .filter(e -> {
                                    try { return e.isDisplayed(); }
                                    catch (Exception ex) { return false; }
                                })
                                .findFirst().orElse(null);

                        if (visible != null) {
                            report.log(by, healed, strategy.getName());
                            // Save to DB so next run is instant
                            HealingDatabase.getInstance().save(by, healed, currentUrl, strategy.getName());
                            return visible;
                        }
                    } catch (Exception ex) {
                        LOG.debug("Strategy {} threw: {}", strategy.getName(), ex.getMessage());
                    }
                }
            } finally {
                // Always restore implicit wait — whether healing succeeded or threw
                delegate.manage().timeouts().implicitlyWait(configuredImplicitWait);
            }

            LOG.error("All {} strategies failed for \'{}\'. Rethrowing original exception.", strategies.size(), by);
            throw originalException;
        }
    }

    /** Returns the current page URL safely — empty string if not available. */
    private String getCurrentPageUrl() {
        try { return delegate.getCurrentUrl(); } catch (Exception e) { return ""; }
    }
    @Override
    public List<WebElement> findElements(By by) {
        return delegate.findElements(by);
    }

    // =========================================================================
    // Standard WebDriver delegation
    // =========================================================================

    @Override public void get(String url)           { delegate.get(url); }
    @Override public String getCurrentUrl()         { return delegate.getCurrentUrl(); }
    @Override public String getTitle()              { return delegate.getTitle(); }
    @Override public String getPageSource()         { return delegate.getPageSource(); }
    @Override public void close()                   { delegate.close(); }
    @Override public void quit()                    { delegate.quit(); }
    @Override public Set<String> getWindowHandles() { return delegate.getWindowHandles(); }
    @Override public String getWindowHandle()       { return delegate.getWindowHandle(); }
    @Override public TargetLocator switchTo()       { return delegate.switchTo(); }
    @Override public Navigation navigate()          { return delegate.navigate(); }

    @Override
    public Options manage() {
        return new HealingOptionsWrapper(delegate.manage());
    }

    // =========================================================================
    // JavascriptExecutor
    // =========================================================================

    @Override
    public Object executeScript(String script, Object... args) {
        return ((JavascriptExecutor) delegate).executeScript(script, args);
    }

    @Override
    public Object executeAsyncScript(String script, Object... args) {
        return ((JavascriptExecutor) delegate).executeAsyncScript(script, args);
    }

    // =========================================================================
    // TakesScreenshot
    // =========================================================================

    @Override
    public <X> X getScreenshotAs(OutputType<X> target) throws WebDriverException {
        return ((TakesScreenshot) delegate).getScreenshotAs(target);
    }

    // =========================================================================
    // HasCapabilities
    // =========================================================================

    @Override
    public Capabilities getCapabilities() {
        return ((HasCapabilities) delegate).getCapabilities();
    }

    // =========================================================================
    // Report & delegate access
    // =========================================================================

    /**
     * Returns the healing report for this driver instance.
     * Call {@code getHealingReport().writeReport()} in your {@code @AfterSuite}.
     *
     * @return the {@link HealingReport} collecting events for this session
     */
    public HealingReport getHealingReport() { return report; }

    /**
     * Returns the underlying real driver (e.g. ChromeDriver).
     * Useful when a test needs to cast to a browser-specific interface.
     *
     * @return the wrapped {@link WebDriver}
     */
    public WebDriver getDelegate() { return delegate; }

    // =========================================================================
    // Options wrapper — intercepts implicitlyWait to track configured value
    // =========================================================================

    private class HealingOptionsWrapper implements Options {
        private final Options wrapped;
        HealingOptionsWrapper(Options w) { this.wrapped = w; }

        @Override
        public Timeouts timeouts() { return new HealingTimeoutsWrapper(wrapped.timeouts()); }

        @Override public void addCookie(Cookie c)              { wrapped.addCookie(c); }
        @Override public void deleteCookieNamed(String n)      { wrapped.deleteCookieNamed(n); }
        @Override public void deleteCookie(Cookie c)           { wrapped.deleteCookie(c); }
        @Override public void deleteAllCookies()               { wrapped.deleteAllCookies(); }
        @Override public Set<Cookie> getCookies()              { return wrapped.getCookies(); }
        @Override public Cookie getCookieNamed(String n)       { return wrapped.getCookieNamed(n); }
        @Override public Logs logs()                           { return wrapped.logs(); }
        @Override public WebDriver.Window window()             { return wrapped.window(); }
    }

    private class HealingTimeoutsWrapper implements Timeouts {
        private final Timeouts wrapped;
        HealingTimeoutsWrapper(Timeouts w) { this.wrapped = w; }

        @Override
        public Timeouts implicitlyWait(Duration duration) {
            if (!duration.isZero()) configuredImplicitWait = duration;
            return wrapped.implicitlyWait(duration);
        }

        @SuppressWarnings("deprecation")
        @Override
        public Timeouts implicitlyWait(long time, TimeUnit unit) {
            Duration d = Duration.ofMillis(unit.toMillis(time));
            if (!d.isZero()) configuredImplicitWait = d;
            return wrapped.implicitlyWait(time, unit);
        }

        @SuppressWarnings("deprecation")
        @Override
        public Timeouts pageLoadTimeout(long time, TimeUnit unit) {
            return wrapped.pageLoadTimeout(time, unit);
        }

        @SuppressWarnings("deprecation")
        @Override
        public Timeouts setScriptTimeout(long time, TimeUnit unit) {
            return wrapped.setScriptTimeout(time, unit);
        }

        @Override public Timeouts pageLoadTimeout(Duration d)  { return wrapped.pageLoadTimeout(d); }
        @Override public Timeouts scriptTimeout(Duration d)    { return wrapped.scriptTimeout(d); }
        @Override public Duration getImplicitWaitTimeout()     { return wrapped.getImplicitWaitTimeout(); }
        @Override public Duration getPageLoadTimeout()         { return wrapped.getPageLoadTimeout(); }
        @Override public Duration getScriptTimeout()           { return wrapped.getScriptTimeout(); }
    }

    // =========================================================================
    // Builder
    // =========================================================================

    /**
     * Start building an {@link AutoHealingDriver}.
     *
     * @param driver the base WebDriver to wrap (e.g. {@code new ChromeDriver()})
     * @return a fluent {@link Builder}
     */
    public static Builder builder(WebDriver driver) {
        return new Builder(driver);
    }

    // =========================================================================
    // Visual fingerprint recording — reflection-based, zero paid-lib dependency
    // =========================================================================

    /**
     * Records a visual fingerprint for a successfully found element.
     * Uses reflection to call VisualHealingStrategy.recordFingerprint()
     * so the free library has zero compile-time dependency on the paid library.
     * Silently skips if the visual library is not on the classpath.
     */
    private void tryRecordVisualFingerprint(By locator, WebElement element) {
        try {
            Class<?> strategyClass = Class.forName(
                    "io.github.autoheal.visual.strategy.VisualHealingStrategy");
            java.lang.reflect.Method record = strategyClass.getMethod(
                    "recordFingerprint",
                    WebDriver.class,
                    By.class,
                    WebElement.class);
            record.invoke(null, delegate, locator, element);
        } catch (ClassNotFoundException ignored) {
            // Visual library not present — free tier, skip silently
        } catch (Exception e) {
            LOG.debug("[AutoHealingDriver] Could not record visual fingerprint: {}",
                      e.getMessage());
        }
    }

    // =========================================================================
    // Builder
    // =========================================================================

    /**
     * Fluent builder for {@link AutoHealingDriver}.
     */
    public static class Builder {

        private final WebDriver baseDriver;
        private final List<IHealingStrategy> strategies;
        private HealingReport report = new HealingReport();

        private Builder(WebDriver driver) {
            this.baseDriver = driver;
            this.strategies = new ArrayList<>(Arrays.asList(
                    new StaleElementStrategy(),
                    new AttributeFallbackStrategy(),
                    new XPathFallbackStrategy(),
                    new CssFallbackStrategy(),
                    new SiblingWalkStrategy(),
                    new ShadowDomStrategy(),
                    new IframeStrategy(),
                    new LlmHealingStrategy(),
                    new DomSimilarityStrategy()
            ));
        }

        /**
         * Add a custom strategy to the healing chain.
         *
         * @param strategy the strategy to add
         * @return this builder
         */
        public Builder withStrategy(IHealingStrategy strategy) {
            strategies.add(strategy);
            return this;
        }

        /**
         * Remove a default strategy by class type (e.g. to skip slow strategies).
         *
         * @param strategyClass the class to remove
         * @return this builder
         */
        public Builder withoutStrategy(Class<? extends IHealingStrategy> strategyClass) {
            strategies.removeIf(s -> s.getClass().equals(strategyClass));
            return this;
        }

        /**
         * Replace ALL strategies with a custom list.
         *
         * @param custom the replacement strategies
         * @return this builder
         */
        /**
         * Use a shared {@link HealingReport} instead of creating a new one.
         * Used internally by {@link io.github.autoheal.healing.listener.AutoHealing}
         * to aggregate all healing events across the suite into one report.
         *
         * @param sharedReport the shared report instance
         * @return this builder
         */
        public Builder withReport(HealingReport sharedReport) {
            this.report = sharedReport;
            return this;
        }

        public Builder withStrategies(IHealingStrategy... custom) {
            strategies.clear();
            strategies.addAll(Arrays.asList(custom));
            return this;
        }

        /**
         * Build the {@link AutoHealingDriver}.
         *
         * @return a configured {@link AutoHealingDriver} ready to use
         */
        public AutoHealingDriver build() {
            strategies.sort(Comparator.comparingInt(IHealingStrategy::getPriority));
            return new AutoHealingDriver(baseDriver, strategies, report);
        }
    }
}
