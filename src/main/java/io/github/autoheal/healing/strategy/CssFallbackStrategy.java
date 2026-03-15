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
 * CssFallbackStrategy (priority 30)
 *
 * <p>Heals broken CSS class selectors using stem matching, CSS module hash
 * stripping, and camelCase-to-kebab-case conversion.
 *
 * <p><b>Examples:</b>
 * <pre>
 *   .submit-button-old  →  [class*='submit-button']
 *   .btn_abc123         →  [class*='btn']              (hash stripped)
 *   .submitButton       →  [class*='submit']           (camelCase split)
 * </pre>
 */
public class CssFallbackStrategy implements IHealingStrategy {

    private static final Logger LOG =
            LoggerFactory.getLogger(CssFallbackStrategy.class);

    @Override
    public By heal(WebDriver driver, By broken) {
        String raw   = broken.toString();
        String value = AttributeFallbackStrategy.extractCoreValue(raw);
        if (value == null || value.isBlank()) return null;

        LOG.debug("[CssFallbackStrategy] Attempting class heal for '{}'", broken);

        List<String> stems = new AttributeFallbackStrategy().buildStems(value);

        for (String stem : stems) {
            By candidate = By.cssSelector("[class*='" + stem + "']");
            if (isVisible(driver, candidate)) {
                LOG.info("[CssFallbackStrategy] Healed '{}' → [class*='{}']", broken, stem);
                return candidate;
            }

            // camelCase → kebab-case
            String kebab = toKebabCase(stem);
            if (!kebab.equals(stem)) {
                candidate = By.cssSelector("[class*='" + kebab + "']");
                if (isVisible(driver, candidate)) {
                    LOG.info("[CssFallbackStrategy] Healed '{}' → kebab [class*='{}']", broken, kebab);
                    return candidate;
                }
            }

            // Strip CSS module hash: btn_abc123 → btn
            String stripped = stem.replaceAll("[_-][a-zA-Z0-9]{4,}$", "");
            if (!stripped.equals(stem) && !stripped.isBlank()) {
                candidate = By.cssSelector("[class*='" + stripped + "']");
                if (isVisible(driver, candidate)) {
                    LOG.info("[CssFallbackStrategy] Healed '{}' → hash-stripped [class*='{}']", broken, stripped);
                    return candidate;
                }
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

    private String toKebabCase(String v) {
        return v.replaceAll("([a-z])([A-Z])", "$1-$2").toLowerCase();
    }

    @Override
    public int getPriority() { return 30; }
}
