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
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * AttributeFallbackStrategy (priority 10 — runs first)
 *
 * <p>Heals broken locators by probing stable HTML attributes using progressive
 * stem variants of the broken locator value.
 *
 * <p><b>Stem generation:</b>
 * <pre>
 *   "submit-btn-old"  →  ["submit-btn-old", "submit-btn", "submit"]
 *   "loginFormV2"     →  ["loginFormV2", "login", "login-form"]   (camelCase split)
 *   "email-input-1"   →  ["email-input-1", "email-input"]         (number strip)
 * </pre>
 *
 * <p>Also handles the special case where the broken locator is itself an attribute
 * selector, e.g. {@code By.cssSelector("[aria-label='Login']")} — in that case
 * it matches the attribute/value pair directly before falling back to stems.
 */
public class AttributeFallbackStrategy implements IHealingStrategy {

    private static final Logger LOG =
            LoggerFactory.getLogger(AttributeFallbackStrategy.class);

    private static final List<String> ATTRIBUTES = Arrays.asList(
            "data-testid", "data-qa", "data-cy",
            "id", "name", "aria-label",
            "aria-describedby", "placeholder", "title", "type", "value"
    );

    private static final Pattern ATTR_SELECTOR_PATTERN =
            Pattern.compile("\\[(\\w[\\w-]*)([*^$]?=)'([^']+)'\\]");

    @Override
    public By heal(WebDriver driver, By broken) {
        String byString = broken.toString();
        String raw = extractRawSelector(byString);

        LOG.debug("[AttributeFallbackStrategy] Attempting to heal '{}'", broken);

        // Special case: broken locator is already an attribute selector
        Matcher m = ATTR_SELECTOR_PATTERN.matcher(raw.trim());
        if (m.matches()) {
            String attr  = m.group(1);
            String op    = m.group(2);
            String val   = m.group(3);
            boolean exact = op.equals("=") || op.isEmpty();

            By directCandidate = By.cssSelector(
                    "[" + attr + (exact ? "=" : "*=") + "'" + escapeCss(val) + "']");
            WebElement found = findVisible(driver, directCandidate);
            if (found != null) {
                LOG.info("[AttributeFallbackStrategy] Healed '{}' via direct attr: {}", broken, directCandidate);
                return directCandidate;
            }
            return healByStem(driver, broken, val);
        }

        String value = extractCoreValue(byString);
        if (value == null || value.isBlank()) return null;
        return healByStem(driver, broken, value);
    }

    private By healByStem(WebDriver driver, By broken, String searchValue) {
        List<String> stems = buildStems(searchValue);
        LOG.debug("[AttributeFallbackStrategy] Stems for '{}': {}", searchValue, stems);

        for (String stem : stems) {
            for (String attribute : ATTRIBUTES) {
                for (String op : new String[]{"=", "*="}) {
                    String selector = "[" + attribute + op + "'" + escapeCss(stem) + "']";
                    By candidate = By.cssSelector(selector);
                    WebElement visible = findVisible(driver, candidate);
                    if (visible != null) {
                        LOG.info("[AttributeFallbackStrategy] Healed '{}' → {}", broken, selector);
                        return candidate;
                    }
                }
            }
        }
        return null;
    }

    private WebElement findVisible(WebDriver driver, By by) {
        try {
            List<WebElement> elements = driver.findElements(by);
            return elements.stream()
                    .filter(e -> { try { return e.isDisplayed(); } catch (Exception ex) { return false; } })
                    .findFirst().orElse(null);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Builds progressive stem variants from a locator value.
     *
     * <p>Handles all common real-world renaming patterns:
     * <ul>
     *   <li>Trailing dash/underscore: {@code "username-"} → {@code "username"}</li>
     *   <li>Version/state suffix: {@code "submit-old"} → {@code "submit"}</li>
     *   <li>Numeric suffix: {@code "input-1234"} → {@code "input"}</li>
     *   <li>CSS module hash: {@code "input_abc123xyz"} → {@code "input"}</li>
     *   <li>Short random hash: {@code "field_xK9mP2"} → {@code "field"}</li>
     *   <li>camelCase split: {@code "submitButton"} → {@code "submit"}</li>
     * </ul>
     */
    public List<String> buildStems(String value) {
        List<String> stems = new ArrayList<>();

        // Strip trailing special characters: "username-" → "username"
        String cleaned = value.replaceAll("[-_]+$", "").trim();
        if (!cleaned.equals(value) && !cleaned.isBlank()) {
            stems.add(cleaned);
            value = cleaned;
        }

        stems.add(value);

        // Strip known suffixes: -old, -new, -v2, -123
        String stripped = value.replaceAll("[-_](old|new|v\\d+|\\d+)$", "").trim();
        if (!stripped.equals(value) && !stripped.isBlank()) {
            stems.add(stripped);
        }

        // Strip CSS module / framework-generated hash suffix
        // Matches: input_abc123xyz → input, field_xK9mP2 → field, btn_AbCdEf12 → btn
        // Pattern: underscore followed by 4+ mixed alphanumeric chars (hash-like)
        String hashStripped = value.replaceAll("[_-][a-zA-Z0-9]{4,}$", "").trim();
        if (!hashStripped.equals(value) && !hashStripped.isBlank()
                && !stems.contains(hashStripped)) {
            stems.add(hashStripped);
        }

        String[] parts = value.split("[-_]");
        StringBuilder prefix = new StringBuilder();
        for (int i = 0; i < parts.length - 1; i++) {
            if (prefix.length() > 0) prefix.append("-");
            prefix.append(parts[i]);
            String stem = prefix.toString();
            if (stem.length() >= 3 && !stems.contains(stem)) stems.add(stem);
        }

        // camelCase split: "submitButton" → "submit"
        String kebab = value.replaceAll("([a-z])([A-Z])", "$1-$2").toLowerCase();
        if (!kebab.equals(value.toLowerCase())) {
            String[] camelParts = kebab.split("-");
            StringBuilder camelPrefix = new StringBuilder();
            for (int i = 0; i < camelParts.length - 1; i++) {
                if (camelPrefix.length() > 0) camelPrefix.append("-");
                camelPrefix.append(camelParts[i]);
                String stem = camelPrefix.toString();
                if (stem.length() >= 3 && !stems.contains(stem)) stems.add(stem);
            }
        }
        return stems;
    }

    private String extractRawSelector(String byString) {
        int colon = byString.indexOf(": ");
        return colon == -1 ? byString : byString.substring(colon + 2).trim();
    }

    /**
     * Extracts a plain text value from a {@code By.toString()} representation.
     * Package-visible for use by other strategies.
     */
    public static String extractCoreValue(String byString) {
        if (byString == null) return null;
        int colonIndex = byString.indexOf(": ");
        if (colonIndex == -1) return null;
        String raw = byString.substring(colonIndex + 2).trim();

        // Handle tag#id pattern: "button#submit-old" → "submit-old"
        if (raw.contains("#")) {
            raw = raw.substring(raw.indexOf("#") + 1);
        }
        // Handle tag.class pattern: "button.submit-btn" → "submit-btn"
        else if (raw.matches("^[a-z]+\\..*")) {
            raw = raw.substring(raw.indexOf(".") + 1);
        }

        // Strip any remaining leading special chars
        raw = raw.replaceAll("^[#.\\[/@*>~+]", "");

        if (raw.contains("/")) {
            String[] parts = raw.split("/");
            raw = parts[parts.length - 1];
        }
        if (raw.contains("='")) {
            raw = raw.replaceAll(".*='([^']+)'.*", "$1");
        }
        return raw.trim();
    }

    private String escapeCss(String value) {
        return value.replace("'", "\\'");
    }

    @Override
    public int getPriority() { return 10; }
}
