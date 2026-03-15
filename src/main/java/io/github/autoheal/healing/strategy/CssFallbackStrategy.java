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

import java.util.ArrayList;
import java.util.List;

/**
 * CssFallbackStrategy (priority 30)
 *
 * <p>Heals broken CSS class selectors using stem matching, CSS module hash
 * stripping, BEM pattern handling, and camelCase-to-kebab-case conversion.
 *
 * <p><b>Examples:</b>
 * <pre>
 *   .submit-button-old            ->  [class*='submit-button']
 *   .btn_abc123                   ->  [class*='btn']
 *   .form__field--error_abc123    ->  [class*='form__field--error']  (BEM + hash)
 *   .LoginForm__input___2abc      ->  [class*='LoginForm__input']    (React CSS Module)
 *   .text-blue-500_xK9m           ->  [class*='text-blue-500']       (Tailwind JIT)
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

        List<String> candidates = buildCssCandidates(value);

        for (String candidate : candidates) {
            By by = By.cssSelector("[class*='" + candidate + "']");
            if (isVisible(driver, by)) {
                LOG.info("[CssFallbackStrategy] Healed '{}' -> [class*='{}']", broken, candidate);
                return by;
            }
        }
        return null;
    }

    /**
     * Builds CSS class candidate values covering all modern naming conventions.
     */
    public List<String> buildCssCandidates(String value) {
        List<String> candidates = new ArrayList<>();

        addIfNew(candidates, value);

        for (String stem : new AttributeFallbackStrategy().buildStems(value)) {
            addIfNew(candidates, stem);
        }

        // React CSS Modules triple underscore: LoginForm__input___2abc -> LoginForm__input
        String reactModule = value.replaceAll("___[a-zA-Z0-9]+$", "");
        addIfNew(candidates, reactModule);

        // BEM with hash: form__field--error_abc123 -> form__field--error
        String bemHash = value.replaceAll("[_][a-zA-Z0-9]{3,}$", "");
        addIfNew(candidates, bemHash);

        // BEM block and element: form__field--error -> form__field -> form
        if (value.contains("__")) {
            String bemElement = value.contains("--")
                    ? value.substring(0, value.indexOf("--"))
                    : value;
            addIfNew(candidates, bemElement);
            String bemBlock = value.substring(0, value.indexOf("__"));
            addIfNew(candidates, bemBlock);
        }

        // camelCase to kebab: LoginFormInput -> login-form-input -> login-form -> login
        String kebab = value.replaceAll("([a-z])([A-Z])", "$1-$2").toLowerCase();
        addIfNew(candidates, kebab);
        // Also add prefix stems of the kebab form
        String[] kebabParts = kebab.split("-");
        StringBuilder kebabPrefix = new StringBuilder();
        for (int i = 0; i < kebabParts.length - 1; i++) {
            if (kebabPrefix.length() > 0) kebabPrefix.append("-");
            kebabPrefix.append(kebabParts[i]);
            String kebabStem = kebabPrefix.toString();
            if (kebabStem.length() >= 3) addIfNew(candidates, kebabStem);
        }

        // Tailwind JIT: text-blue-500_xK9m -> text-blue-500
        String tailwind = value.replaceAll("_[a-zA-Z0-9]{3,}$", "");
        addIfNew(candidates, tailwind);

        candidates.removeIf(s -> s == null || s.isBlank() || s.length() < 2);
        return candidates;
    }

    private void addIfNew(List<String> list, String value) {
        if (value != null && !value.isBlank() && !list.contains(value)) {
            list.add(value);
        }
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
    public int getPriority() { return 30; }
}
