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
 * XPathFallbackStrategy (priority 20)
 *
 * <p>Heals broken locators by searching for elements using their visible text
 * content via case-insensitive XPath expressions. Most effective when button
 * or link labels are stable but HTML attributes have changed.
 *
 * <p><b>Example:</b> {@code By.id("submit-btn-old")} → stem "submit" →
 * {@code //button[contains(translate(normalize-space(text()),...),'submit')]}
 */
public class XPathFallbackStrategy implements IHealingStrategy {

    private static final Logger LOG =
            LoggerFactory.getLogger(XPathFallbackStrategy.class);

    private static final String[] TAGS = {
            "button", "a", "input", "label", "span", "div", "li", "h1", "h2", "h3", "td"
    };

    private static final String UPPER = "ABCDEFGHIJKLMNOPQRSTUVWXYZ";
    private static final String LOWER = "abcdefghijklmnopqrstuvwxyz";

    @Override
    public By heal(WebDriver driver, By broken) {
        String value = AttributeFallbackStrategy.extractCoreValue(broken.toString());
        if (value == null || value.isBlank()) return null;

        List<String> stems = new AttributeFallbackStrategy().buildStems(value);
        LOG.debug("[XPathFallbackStrategy] Attempting text search for '{}'", broken);

        for (String stem : stems) {
            if (stem.length() < 3) continue;
            String lowerStem = stem.toLowerCase();

            for (String tag : TAGS) {
                String xpath = "//" + tag + "[contains("
                        + "translate(normalize-space(text()),'"
                        + UPPER + "','" + LOWER + "'),'" + lowerStem + "')]";
                By candidate = By.xpath(xpath);
                if (isVisible(driver, candidate)) {
                    LOG.info("[XPathFallbackStrategy] Healed '{}' → {}", broken, xpath);
                    return candidate;
                }
            }

            // Also try input[@value] for submit buttons
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
