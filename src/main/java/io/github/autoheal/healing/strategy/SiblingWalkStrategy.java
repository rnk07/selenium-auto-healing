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
 * SiblingWalkStrategy (priority 40)
 *
 * <p>Heals broken form field locators by finding a stable {@code <label>} element
 * whose text matches the broken locator's value, then walking to its sibling input.
 * Also scans {@code aria-label} attributes via JavaScript.
 *
 * <p><b>Best for:</b> Form fields where the label text is stable but the input's
 * id/name attribute has changed.
 *
 * <p><b>Example:</b>
 * <pre>
 *   //label[contains(text(),'Email')]/following-sibling::input[1]
 * </pre>
 */
public class SiblingWalkStrategy implements IHealingStrategy {

    private static final Logger LOG =
            LoggerFactory.getLogger(SiblingWalkStrategy.class);

    private static final String UPPER = "ABCDEFGHIJKLMNOPQRSTUVWXYZ";
    private static final String LOWER = "abcdefghijklmnopqrstuvwxyz";

    @Override
    public By heal(WebDriver driver, By broken) {
        String value = AttributeFallbackStrategy.extractCoreValue(broken.toString());
        if (value == null || value.isBlank()) return null;

        LOG.debug("[SiblingWalkStrategy] Attempting label-anchor healing for '{}'", broken);

        List<String> stems = new AttributeFallbackStrategy().buildStems(value);

        for (String stem : stems) {
            if (stem.length() < 3) continue;
            String lowerStem = stem.toLowerCase();

            String labelXpath = "//label[contains("
                    + "translate(text(),'" + UPPER + "','" + LOWER + "'),'"
                    + lowerStem + "')]";

            String[] siblingPaths = {
                    "/following-sibling::input[1]",
                    "/following-sibling::select[1]",
                    "/following-sibling::textarea[1]",
                    "/..//input[1]",
                    "/..//select[1]"
            };

            for (String sibling : siblingPaths) {
                By candidate = By.xpath(labelXpath + sibling);
                try {
                    WebElement el = driver.findElement(candidate);
                    if (el.isDisplayed()) {
                        LOG.info("[SiblingWalkStrategy] Healed '{}' via label+sibling: {}", broken, labelXpath + sibling);
                        return candidate;
                    }
                } catch (Exception ignored) { }
            }
        }

        // JavaScript aria-label scan
        By ariaCandidate = tryAriaHeal(driver, value);
        if (ariaCandidate != null) {
            LOG.info("[SiblingWalkStrategy] Healed '{}' via aria-label JS scan", broken);
            return ariaCandidate;
        }

        return null;
    }

    private By tryAriaHeal(WebDriver driver, String value) {
        try {
            JavascriptExecutor js = (JavascriptExecutor) driver;
            String script =
                    "var v = arguments[0].toLowerCase();" +
                    "var all = document.querySelectorAll('[aria-label]');" +
                    "for (var i = 0; i < all.length; i++) {" +
                    "  var label = all[i].getAttribute('aria-label');" +
                    "  if (label && label.toLowerCase().includes(v)" +
                    "      && all[i].offsetParent !== null) {" +
                    "    return label;" +
                    "  }" +
                    "}" +
                    "return null;";
            Object result = js.executeScript(script, value);
            if (result != null) {
                return By.cssSelector("[aria-label='" + result + "']");
            }
        } catch (Exception e) {
            LOG.debug("[SiblingWalkStrategy] JS aria scan failed: {}", e.getMessage());
        }
        return null;
    }

    @Override
    public int getPriority() { return 40; }
}
