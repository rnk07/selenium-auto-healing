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
package io.github.autoheal.healing.strategy;

import org.openqa.selenium.By;
import org.openqa.selenium.JavascriptExecutor;
import org.openqa.selenium.StaleElementReferenceException;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * StaleElementStrategy (priority 5 — runs before all other strategies)
 *
 * <p>Handles {@link StaleElementReferenceException} — the error that occurs
 * when a WebElement reference becomes invalid because the DOM was refreshed
 * after the element was found.
 *
 * <p><b>Why this happens:</b>
 * <pre>
 *   1. driver.findElement(By.id("submit"))  → element found, reference stored
 *   2. React/Angular re-renders the component  → DOM rebuilt, old reference invalid
 *   3. element.click()  → StaleElementReferenceException
 * </pre>
 *
 * <p><b>How this strategy fixes it:</b>
 * Waits briefly for the DOM to settle after a re-render, then re-finds the
 * element using the same locator. Uses JavaScript to detect when the DOM
 * has finished updating before attempting the re-find.
 *
 * <p><b>Common triggers:</b>
 * <ul>
 *   <li>React/Angular/Vue component re-render after state change</li>
 *   <li>AJAX response updating part of the page</li>
 *   <li>Dynamic tables refreshing after sort/filter</li>
 *   <li>Form fields resetting after validation</li>
 *   <li>Lazy-loaded content appearing and re-rendering</li>
 * </ul>
 *
 * <p><b>Note:</b> This strategy runs at priority 5 — before all attribute/CSS/XPath
 * strategies — because a stale element is almost always recoverable with a simple
 * re-find and does not need complex stem matching.
 */
public class StaleElementStrategy implements IHealingStrategy {

    private static final Logger LOG =
            LoggerFactory.getLogger(StaleElementStrategy.class);

    /** Maximum number of re-find attempts before giving up. */
    private static final int MAX_RETRIES = 3;

    /** Milliseconds to wait between re-find attempts. */
    private static final long RETRY_DELAY_MS = 500;

    @Override
    public By heal(WebDriver driver, By broken) {
        LOG.debug("[StaleElementStrategy] Attempting stale element recovery for '{}'", broken);

        // Wait for DOM to settle after re-render
        waitForDomStable(driver);

        // Try to re-find the element with the same locator
        for (int attempt = 1; attempt <= MAX_RETRIES; attempt++) {
            try {
                List<WebElement> elements = driver.findElements(broken);
                WebElement visible = elements.stream()
                        .filter(e -> {
                            try { return e.isDisplayed(); }
                            catch (StaleElementReferenceException ex) { return false; }
                            catch (Exception ex) { return false; }
                        })
                        .findFirst().orElse(null);

                if (visible != null) {
                    LOG.info("[StaleElementStrategy] Recovered stale element on attempt {}: '{}'",
                            attempt, broken);
                    return broken; // return same locator — element re-found
                }

                if (attempt < MAX_RETRIES) {
                    LOG.debug("[StaleElementStrategy] Attempt {} failed, waiting {}ms...",
                            attempt, RETRY_DELAY_MS);
                    Thread.sleep(RETRY_DELAY_MS);
                }

            } catch (StaleElementReferenceException e) {
                LOG.debug("[StaleElementStrategy] Still stale on attempt {}, retrying...", attempt);
                if (attempt < MAX_RETRIES) {
                    try { Thread.sleep(RETRY_DELAY_MS); } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return null;
            } catch (Exception e) {
                LOG.debug("[StaleElementStrategy] Re-find failed: {}", e.getMessage());
                return null;
            }
        }

        LOG.debug("[StaleElementStrategy] Could not recover stale element after {} attempts.", MAX_RETRIES);
        return null;
    }

    /**
     * Uses JavaScript to wait for the DOM to finish updating.
     * Checks document.readyState and any pending jQuery/fetch requests.
     */
    private void waitForDomStable(WebDriver driver) {
        if (!(driver instanceof JavascriptExecutor)) return;
        JavascriptExecutor js = (JavascriptExecutor) driver;

        try {
            // Wait for document ready state
            for (int i = 0; i < 10; i++) {
                Object readyState = js.executeScript("return document.readyState");
                if ("complete".equals(readyState)) break;
                Thread.sleep(100);
            }

            // Wait for any pending jQuery AJAX (if jQuery is present)
            try {
                Object jqueryActive = js.executeScript(
                        "return (typeof jQuery !== 'undefined') ? jQuery.active : 0");
                if (jqueryActive != null && Long.parseLong(jqueryActive.toString()) > 0) {
                    Thread.sleep(300);
                }
            } catch (Exception ignored) { }

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            LOG.debug("[StaleElementStrategy] DOM stability check failed: {}", e.getMessage());
        }
    }

    @Override
    public String getName() { return "StaleElementStrategy"; }

    @Override
    public int getPriority() { return 5; }
}
