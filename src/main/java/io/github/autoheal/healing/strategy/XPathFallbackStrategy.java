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
import java.util.regex.Pattern;

/**
 * XPathFallbackStrategy (priority 20)
 *
 * <p>Heals broken locators by searching for elements using their visible text
 * content via case-insensitive XPath expressions.
 *
 * <p>Also handles index-based XPath locators which are extremely fragile and
 * break whenever the DOM structure changes — converts them to attribute-based
 * or text-based locators instead.
 *
 * <p><b>Examples:</b>
 * <pre>
 *   By.id("submit-btn-old")  →  //button[contains(text(),'submit')]
 *   By.xpath("//div[3]/button[2]") →  converts to text/attribute search
 *   By.xpath("/html/body/div/form/input[1]")  →  converts to attribute search
 * </pre>
 */
public class XPathFallbackStrategy implements IHealingStrategy {

    private static final Logger LOG =
            LoggerFactory.getLogger(XPathFallbackStrategy.class);

    private static final String[] TAGS = {
            "button", "a", "input", "label", "span", "div", "li", "h1", "h2", "h3", "td"
    };

    private static final String UPPER = "ABCDEFGHIJKLMNOPQRSTUVWXYZ";
    private static final String LOWER = "abcdefghijklmnopqrstuvwxyz";

    // Detects index-based XPath: //div[3], /html/body/div[1]/input[2] etc.
    private static final Pattern INDEX_XPATH_PATTERN =
            Pattern.compile(".*/(\\w+)\\[\\d+\\].*");

    @Override
    public By heal(WebDriver driver, By broken) {
        String byString = broken.toString();

        // Special handling for index-based XPath locators
        if (byString.startsWith("By.xpath:") && isIndexBasedXPath(byString)) {
            LOG.warn("[XPathFallbackStrategy] Index-based XPath detected: '{}'. " +
                     "These are fragile — consider using id, name, or data-testid instead.", broken);
            return healIndexBasedXPath(driver, broken, byString);
        }

        String value = AttributeFallbackStrategy.extractCoreValue(byString);
        if (value == null || value.isBlank()) return null;

        List<String> stems = new AttributeFallbackStrategy().buildStems(value);
        LOG.debug("[XPathFallbackStrategy] Attempting text search for '{}'", broken);

        return searchByText(driver, broken, stems);
    }

    /**
     * Attempts to heal an index-based XPath by extracting the tag name
     * and searching by text content or stable attributes instead.
     */
    private By healIndexBasedXPath(WebDriver driver, By broken, String byString) {
        String raw = byString.substring(byString.indexOf(": ") + 2).trim();

        // Extract the last tag name from the XPath: //div[3]/button[2] -> button
        String[] parts = raw.split("/");
        String lastPart = parts[parts.length - 1];
        String tag = lastPart.replaceAll("\\[\\d+\\]", "").trim();

        if (tag.isBlank() || tag.equals("*")) return null;

        LOG.debug("[XPathFallbackStrategy] Extracted tag '{}' from index XPath", tag);

        // Try to find any visible element with this tag using text search
        String xpath = "//" + tag + "[string-length(normalize-space(text())) > 0]";
        try {
            List<WebElement> candidates = driver.findElements(By.xpath(xpath));
            for (WebElement el : candidates) {
                try {
                    if (el.isDisplayed()) {
                        String text = el.getText().trim();
                        if (!text.isBlank()) {
                            String textXpath = "//" + tag + "[contains(" +
                                    "translate(normalize-space(text()),'" + UPPER + "','" + LOWER + "')," +
                                    "'" + text.toLowerCase().substring(0, Math.min(text.length(), 20)) + "')]";
                            LOG.info("[XPathFallbackStrategy] Healed index XPath '{}' -> {}", broken, textXpath);
                            return By.xpath(textXpath);
                        }
                    }
                } catch (Exception ignored) { }
            }
        } catch (Exception e) {
            LOG.debug("[XPathFallbackStrategy] Index XPath heal failed: {}", e.getMessage());
        }
        return null;
    }

    private By searchByText(WebDriver driver, By broken, List<String> stems) {
        for (String stem : stems) {
            if (stem.length() < 3) continue;
            String lowerStem = stem.toLowerCase();

            for (String tag : TAGS) {
                String xpath = "//" + tag + "[contains("
                        + "translate(normalize-space(text()),'"
                        + UPPER + "','" + LOWER + "'),'" + lowerStem + "')]";
                By candidate = By.xpath(xpath);
                if (isVisible(driver, candidate)) {
                    LOG.info("[XPathFallbackStrategy] Healed '{}' -> {}", broken, xpath);
                    return candidate;
                }
            }

            // Try input[@value] for submit buttons
            String inputXpath = "//input[@type='submit' or @type='button'][contains("
                    + "translate(@value,'" + UPPER + "','" + LOWER + "'),'" + lowerStem + "')]";
            By inputCandidate = By.xpath(inputXpath);
            if (isVisible(driver, inputCandidate)) {
                LOG.info("[XPathFallbackStrategy] Healed '{}' via input[@value]: {}", broken, inputXpath);
                return inputCandidate;
            }
        }
        return null;
    }

    private boolean isIndexBasedXPath(String byString) {
        return INDEX_XPATH_PATTERN.matcher(byString).matches();
    }

    private boolean isVisible(WebDriver driver, By by) {
        try {
            List<WebElement> els = driver.findElements(by);
            return els.stream().anyMatch(e -> {
                try { return e.isDisplayed(); } catch (Exception ex) { return false; }
            });
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    public int getPriority() { return 20; }
}
