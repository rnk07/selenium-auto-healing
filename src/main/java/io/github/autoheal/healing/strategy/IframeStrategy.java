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
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * IframeStrategy (priority 60)
 *
 * <p>Heals locators for elements that live inside {@code <iframe>} elements.
 * Standard Selenium {@code findElement} only searches the current frame context.
 * If an element is inside an iframe, the locator fails silently even though
 * the element exists on the page.
 *
 * <p><b>How it works:</b>
 * <ol>
 *   <li>Finds all {@code <iframe>} elements on the current page</li>
 *   <li>Switches into each iframe one by one</li>
 *   <li>Runs the full stem-based attribute probe inside each iframe</li>
 *   <li>If found, switches back to default content and returns a special
 *       {@link IframeBy} that switches to the correct frame before locating</li>
 * </ol>
 *
 * <p><b>Real-world example:</b>
 * <pre>
 *   Page structure:
 *   &lt;body&gt;
 *     &lt;iframe id="payment-frame"&gt;
 *       &lt;input id="card-number-old"&gt;  ← broken locator is here
 *     &lt;/iframe&gt;
 *   &lt;/body&gt;
 *
 *   Broken:  By.id("card-number-old")
 *   Healed:  IframeBy(frameIndex=0, inner=[id*='card-number'])
 * </pre>
 *
 * <p><b>Common use cases:</b> Payment forms (Stripe, PayPal), embedded maps,
 * Google reCAPTCHA, third-party chat widgets, embedded video players.
 */
public class IframeStrategy implements IHealingStrategy {

    private static final Logger LOG = LoggerFactory.getLogger(IframeStrategy.class);

    private static final String[] ATTRIBUTES = {
            "id", "name", "data-testid", "data-qa", "data-cy",
            "aria-label", "placeholder", "title", "type", "value"
    };

    @Override
    public By heal(WebDriver driver, By broken) {
        String value = AttributeFallbackStrategy.extractCoreValue(broken.toString());
        if (value == null || value.isBlank()) return null;

        LOG.debug("[IframeStrategy] Attempting iframe healing for '{}'", broken);

        List<WebElement> iframes;
        try {
            iframes = driver.findElements(By.tagName("iframe"));
        } catch (Exception e) {
            return null;
        }

        if (iframes == null || iframes.isEmpty()) {
            LOG.debug("[IframeStrategy] No iframes found on this page.");
            return null;
        }

        LOG.debug("[IframeStrategy] Found {} iframe(s), probing...", iframes.size());
        List<String> stems = new AttributeFallbackStrategy().buildStems(value);

        for (int i = 0; i < iframes.size(); i++) {
            try {
                driver.switchTo().frame(i);

                for (String stem : stems) {
                    for (String attr : ATTRIBUTES) {
                        for (String op : new String[]{"=", "*="}) {
                            String css = "[" + attr + op + "'" + stem + "']";
                            try {
                                List<WebElement> found = driver.findElements(By.cssSelector(css));
                                WebElement visible = found.stream()
                                        .filter(e -> {
                                            try { return e.isDisplayed(); }
                                            catch (Exception ex) { return false; }
                                        })
                                        .findFirst().orElse(null);

                                if (visible != null) {
                                    driver.switchTo().defaultContent();
                                    LOG.info("[IframeStrategy] Healed '{}' → iframe[{}] '{}'",
                                            broken, i, css);
                                    return new IframeBy(i, css);
                                }
                            } catch (Exception ignored) { }
                        }
                    }
                }
            } catch (Exception e) {
                LOG.debug("[IframeStrategy] Could not switch to iframe {}: {}", i, e.getMessage());
            } finally {
                try { driver.switchTo().defaultContent(); } catch (Exception ignored) { }
            }
        }

        LOG.debug("[IframeStrategy] Element not found in any iframe.");
        return null;
    }

    @Override
    public String getName() { return "IframeStrategy"; }

    @Override
    public int getPriority() { return 60; }

    // =========================================================================
    // IframeBy — switches to the correct frame before finding the element
    // =========================================================================

    /**
     * A custom {@link By} that switches to a specific iframe by index before
     * locating the inner element, then switches back to default content.
     */
    public static class IframeBy extends By {

        private final int frameIndex;
        private final String innerCss;

        public IframeBy(int frameIndex, String innerCss) {
            this.frameIndex = frameIndex;
            this.innerCss   = innerCss;
        }

        @Override
        public List<WebElement> findElements(org.openqa.selenium.SearchContext context) {
            if (!(context instanceof WebDriver)) return java.util.Collections.emptyList();
            WebDriver driver = (WebDriver) context;
            try {
                driver.switchTo().frame(frameIndex);
                List<WebElement> elements = driver.findElements(By.cssSelector(innerCss));
                driver.switchTo().defaultContent();
                return elements;
            } catch (Exception e) {
                try { driver.switchTo().defaultContent(); } catch (Exception ignored) { }
                return java.util.Collections.emptyList();
            }
        }

        @Override
        public String toString() {
            return "IframeBy(frame=" + frameIndex + ", inner='" + innerCss + "')";
        }
    }
}
