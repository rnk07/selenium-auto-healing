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
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * ShadowDomStrategy (priority 50)
 *
 * <p>Heals locators for elements that live inside a Shadow DOM — the isolated
 * DOM tree used by Web Components in React, Angular, Vue, and Lit-based apps.
 *
 * <p><b>Why standard Selenium fails on Shadow DOM:</b>
 * <pre>
 *   Normal DOM:               Shadow DOM:
 *   &lt;body&gt;                    &lt;body&gt;
 *     &lt;button id="submit"&gt;      &lt;my-login&gt;         ← web component
 *     &lt;/button&gt;                    #shadow-root   ← hidden from Selenium
 *   &lt;/body&gt;                          &lt;button id="submit"&gt; ← invisible!
 *                                    &lt;/button&gt;
 *                                  &lt;/my-login&gt;
 *                               &lt;/body&gt;
 * </pre>
 *
 * <p><b>How this strategy heals it:</b>
 * Uses JavaScript to find all shadow roots on the page and queries each one
 * for elements matching the broken locator's stem value across all stable
 * attributes ({@code id}, {@code name}, {@code data-testid}, {@code aria-label}).
 *
 * <p><b>What it returns:</b>
 * A special {@code By.xpath} locator that uses JavaScript execution to find
 * the element inside the shadow root. The healed locator stores the host
 * component selector and the inner CSS selector together.
 *
 * <p><b>Real-world example:</b>
 * <pre>
 *   Broken:  By.cssSelector("button#submit-old")
 *   Page:    &lt;my-login&gt;#shadow-root&lt;button id="submit"&gt;&lt;/button&gt;&lt;/my-login&gt;
 *   Healed:  ShadowBy — host: "my-login", inner: "[id*='submit']"
 * </pre>
 *
 * <p><b>Supported frameworks:</b> Lit, Stencil, Angular (Ivy), React with
 * custom elements, any HTML custom element using attachShadow().
 */
public class ShadowDomStrategy implements IHealingStrategy {

    private static final Logger LOG =
            LoggerFactory.getLogger(ShadowDomStrategy.class);

    private static final String[] ATTRIBUTES = {
            "id", "name", "data-testid", "data-qa", "data-cy",
            "aria-label", "placeholder", "title", "type", "value"
    };

    @Override
    public By heal(WebDriver driver, By broken) {
        if (!(driver instanceof JavascriptExecutor)) return null;

        String value = AttributeFallbackStrategy.extractCoreValue(broken.toString());
        if (value == null || value.isBlank()) return null;

        LOG.debug("[ShadowDomStrategy] Attempting shadow DOM healing for '{}'", broken);

        JavascriptExecutor js = (JavascriptExecutor) driver;
        List<String> stems = new AttributeFallbackStrategy().buildStems(value);

        // Step 1: Find all shadow host elements on the page
        String findHostsScript =
                "var hosts = [];" +
                "var all = document.querySelectorAll('*');" +
                "for (var i = 0; i < all.length; i++) {" +
                "  if (all[i].shadowRoot) hosts.push(all[i]);" +
                "}" +
                "return hosts;";

        List<WebElement> shadowHosts;
        try {
            Object result = js.executeScript(findHostsScript);
            if (result == null) return null;
            @SuppressWarnings("unchecked")
            List<WebElement> hosts = (List<WebElement>) result;
            shadowHosts = hosts;
        } catch (Exception e) {
            LOG.debug("[ShadowDomStrategy] Could not find shadow hosts: {}", e.getMessage());
            return null;
        }

        if (shadowHosts == null || shadowHosts.isEmpty()) {
            LOG.debug("[ShadowDomStrategy] No shadow DOM found on this page.");
            return null;
        }

        LOG.debug("[ShadowDomStrategy] Found {} shadow host(s), probing...", shadowHosts.size());

        // Step 2: For each shadow host, try each stem against each attribute
        for (WebElement host : shadowHosts) {
            String hostTag = getTagName(js, host);
            if (hostTag == null) continue;

            for (String stem : stems) {
                for (String attr : ATTRIBUTES) {
                    // Try exact match first, then contains
                    for (String op : new String[]{"=", "*="}) {
                        String innerCss = "[" + attr + op + "'" + stem + "']";
                        String probeScript =
                                "var host = arguments[0];" +
                                "if (!host.shadowRoot) return null;" +
                                "var el = host.shadowRoot.querySelector(arguments[1]);" +
                                "return el && el.offsetParent !== null ? el : null;";
                        try {
                            Object found = js.executeScript(probeScript, host, innerCss);
                            if (found != null) {
                                LOG.info("[ShadowDomStrategy] Healed '{}' → shadow('{}', '{}')",
                                        broken, hostTag, innerCss);
                                return new ShadowBy(hostTag, innerCss, js);
                            }
                        } catch (Exception e) {
                            LOG.debug("[ShadowDomStrategy] Probe failed for {}{}: {}",
                                    attr, op, e.getMessage());
                        }
                    }
                }
            }
        }

        LOG.debug("[ShadowDomStrategy] No match found in any shadow DOM.");
        return null;
    }

    private String getTagName(JavascriptExecutor js, WebElement el) {
        try {
            Object tag = js.executeScript("return arguments[0].tagName.toLowerCase()", el);
            return tag != null ? tag.toString() : null;
        } catch (Exception e) {
            return null;
        }
    }

    @Override
    public String getName() { return "ShadowDomStrategy"; }

    @Override
    public int getPriority() { return 50; }

    // =========================================================================
    // ShadowBy — a custom By that executes JS to find shadow DOM elements
    // =========================================================================

    /**
     * A custom {@link By} implementation that finds elements inside a Shadow DOM
     * using JavaScript execution. Stores the host element CSS selector and the
     * inner element CSS selector together.
     *
     * <p>Usage is transparent — returned from {@link ShadowDomStrategy#heal}
     * and used by {@code AutoHealingDriver.findElement()} like any other {@link By}.
     */
    public static class ShadowBy extends By {

        private final String hostSelector;
        private final String innerSelector;
        private final JavascriptExecutor js;

        public ShadowBy(String hostSelector, String innerSelector, JavascriptExecutor js) {
            this.hostSelector  = hostSelector;
            this.innerSelector = innerSelector;
            this.js            = js;
        }

        @Override
        public List<WebElement> findElements(org.openqa.selenium.SearchContext context) {
            try {
                String script =
                        "var host = document.querySelector(arguments[0]);" +
                        "if (!host || !host.shadowRoot) return [];" +
                        "var el = host.shadowRoot.querySelector(arguments[1]);" +
                        "return el ? [el] : [];";
                @SuppressWarnings("unchecked")
                List<WebElement> result = (List<WebElement>)
                        js.executeScript(script, hostSelector, innerSelector);
                return result != null ? result : java.util.Collections.emptyList();
            } catch (Exception e) {
                return java.util.Collections.emptyList();
            }
        }

        @Override
        public String toString() {
            return "ShadowBy(host='" + hostSelector + "', inner='" + innerSelector + "')";
        }
    }
}
