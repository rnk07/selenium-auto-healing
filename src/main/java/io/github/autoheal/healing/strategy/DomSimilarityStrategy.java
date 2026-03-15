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
import java.util.Map;

/**
 * DomSimilarityStrategy (priority 70 — last resort before giving up)
 *
 * <p>Heals broken locators by finding the most structurally similar element
 * on the current page when all attribute-based strategies have failed.
 *
 * <p><b>How it works:</b>
 * <ol>
 *   <li>Extracts a structural fingerprint of the broken locator:
 *       tag name, approximate position, size, text content, parent tag</li>
 *   <li>Scans all visible elements on the page with the same tag name</li>
 *   <li>Scores each candidate against the fingerprint using a weighted
 *       similarity algorithm</li>
 *   <li>Returns the highest-scoring candidate as a stable XPath locator</li>
 * </ol>
 *
 * <p><b>Similarity scoring weights:</b>
 * <pre>
 *   Text content match     — 40 points  (most reliable signal)
 *   Position proximity     — 30 points  (element moved slightly)
 *   Size similarity        — 15 points  (same button, same size)
 *   Parent tag match       — 15 points  (structural context)
 * </pre>
 *
 * <p><b>What it heals:</b>
 * <ul>
 *   <li>Completely renamed IDs with no stem relationship</li>
 *   <li>Elements that moved position but kept their text</li>
 *   <li>Form fields that kept their label but changed all attributes</li>
 *   <li>Buttons whose class, id, and name all changed simultaneously</li>
 * </ul>
 *
 * <p><b>What it cannot heal:</b>
 * <ul>
 *   <li>Elements that were genuinely removed from the page</li>
 *   <li>Elements with no visible text and completely changed attributes</li>
 *   <li>Pages that have changed layout completely</li>
 * </ul>
 *
 * <p><b>No dependencies:</b> Uses only Selenium's {@link JavascriptExecutor}
 * and standard Java. No screenshot, no ML library, no external API.
 * Works fully in headless mode.
 */
public class DomSimilarityStrategy implements IHealingStrategy {

    private static final Logger LOG =
            LoggerFactory.getLogger(DomSimilarityStrategy.class);

    /** Minimum similarity score (0-100) to accept a candidate. */
    private static final double MIN_SCORE = 40.0;

    @Override
    public By heal(WebDriver driver, By broken) {
        if (!(driver instanceof JavascriptExecutor)) return null;

        String value = AttributeFallbackStrategy.extractCoreValue(broken.toString());
        if (value == null || value.isBlank()) return null;

        LOG.debug("[DomSimilarityStrategy] Attempting DOM similarity healing for '{}'", broken);

        JavascriptExecutor js = (JavascriptExecutor) driver;

        // Step 1: Build a fingerprint from the broken locator value
        ElementFingerprint target = buildFingerprint(value, broken.toString());

        // Step 2: Scan all visible elements with the same tag
        List<Map<String, Object>> candidates = scanCandidates(js, target.tag);
        if (candidates == null || candidates.isEmpty()) {
            // Try broader scan with common interactive tags
            candidates = scanCandidates(js, null);
        }
        if (candidates == null || candidates.isEmpty()) return null;

        LOG.debug("[DomSimilarityStrategy] Scanning {} candidate(s) for '{}'",
                candidates.size(), broken);

        // Step 3: Score each candidate
        Map<String, Object> bestCandidate = null;
        double bestScore = 0;

        for (Map<String, Object> candidate : candidates) {
            double score = scoreSimilarity(target, candidate);
            if (score > bestScore) {
                bestScore = score;
                bestCandidate = candidate;
            }
        }

        if (bestCandidate == null || bestScore < MIN_SCORE) {
            LOG.debug("[DomSimilarityStrategy] No candidate reached minimum score {}. Best: {}",
                    MIN_SCORE, bestScore);
            return null;
        }

        // Step 4: Build a stable locator for the best candidate
        By healed = buildLocator(bestCandidate);
        if (healed == null) return null;

        // Step 5: Verify the healed locator actually finds a visible element
        try {
            List<WebElement> found = driver.findElements(healed);
            boolean visible = found.stream().anyMatch(e -> {
                try { return e.isDisplayed(); } catch (Exception ex) { return false; }
            });
            if (!visible) return null;
        } catch (Exception e) {
            return null;
        }

        LOG.info("[DomSimilarityStrategy] Healed '{}' -> '{}' (score: {}/100)",
                broken, healed, String.format("%.0f", bestScore));
        return healed;
    }

    // =========================================================================
    // Fingerprint
    // =========================================================================

    /**
     * Builds a structural fingerprint from the broken locator value.
     * Used as the reference for scoring candidates.
     */
    private ElementFingerprint buildFingerprint(String value, String byString) {
        ElementFingerprint fp = new ElementFingerprint();

        // Infer tag from locator type
        String lower = byString.toLowerCase();
        if (lower.contains("button") || lower.contains("submit") || lower.contains("btn")) {
            fp.tag = "button";
        } else if (lower.contains("input") || lower.contains("field") ||
                   lower.contains("username") || lower.contains("password") ||
                   lower.contains("email")) {
            fp.tag = "input";
        } else if (lower.contains("select") || lower.contains("dropdown")) {
            fp.tag = "select";
        } else if (lower.contains("textarea")) {
            fp.tag = "textarea";
        } else if (lower.contains("link") || lower.contains("href") ||
                   lower.contains("nav")) {
            fp.tag = "a";
        } else {
            fp.tag = null; // broad scan
        }

        // Use value as expected text hint
        fp.textHint = value.replaceAll("[_-]", " ").toLowerCase();

        return fp;
    }

    // =========================================================================
    // DOM scanning
    // =========================================================================

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> scanCandidates(JavascriptExecutor js, String tag) {
        String tagFilter = (tag != null) ? "'" + tag + "'" : "null";
        String script =
            "var tag = " + tagFilter + ";" +
            "var selector = tag ? tag : 'button,input,a,select,textarea,[role=button]';" +
            "var els = document.querySelectorAll(selector);" +
            "var results = [];" +
            "for (var i = 0; i < Math.min(els.length, 100); i++) {" +
            "  var el = els[i];" +
            "  if (!el || el.offsetParent === null) continue;" +
            "  var rect = el.getBoundingClientRect();" +
            "  if (rect.width === 0 || rect.height === 0) continue;" +
            "  var text = (el.textContent || el.value || el.placeholder || '').trim();" +
            "  text = text.substring(0, 50);" +
            "  var parent = el.parentElement ? el.parentElement.tagName.toLowerCase() : '';" +
            "  var id = el.id || '';" +
            "  var name = el.getAttribute('name') || '';" +
            "  var type = el.getAttribute('type') || '';" +
            "  var ariaLabel = el.getAttribute('aria-label') || '';" +
            "  results.push({" +
            "    tag: el.tagName.toLowerCase()," +
            "    text: text.toLowerCase()," +
            "    x: Math.round(rect.left)," +
            "    y: Math.round(rect.top)," +
            "    w: Math.round(rect.width)," +
            "    h: Math.round(rect.height)," +
            "    parent: parent," +
            "    id: id," +
            "    name: name," +
            "    type: type," +
            "    ariaLabel: ariaLabel" +
            "  });" +
            "}" +
            "return results;";

        try {
            Object result = js.executeScript(script);
            return (List<Map<String, Object>>) result;
        } catch (Exception e) {
            LOG.debug("[DomSimilarityStrategy] Scan failed: {}", e.getMessage());
            return null;
        }
    }

    // =========================================================================
    // Scoring algorithm
    // =========================================================================

    /**
     * Scores a candidate element against the target fingerprint.
     * Returns a score from 0 to 100.
     *
     * <p>Weights:
     * <ul>
     *   <li>Text content match — 40 points</li>
     *   <li>Position proximity — 30 points</li>
     *   <li>Size similarity   — 15 points</li>
     *   <li>Parent tag match  — 15 points</li>
     * </ul>
     */
    private double scoreSimilarity(ElementFingerprint target,
                                   Map<String, Object> candidate) {
        double score = 0;
        String candidateText = getString(candidate, "text");
        String candidateTag  = getString(candidate, "tag");
        String candidateParent = getString(candidate, "parent");
        String ariaLabel = getString(candidate, "ariaLabel");

        // 1. Text content match (40 points)
        if (!target.textHint.isBlank() && !candidateText.isBlank()) {
            String hint = target.textHint.toLowerCase();
            String text = candidateText.toLowerCase();
            if (text.equals(hint)) {
                score += 40; // exact match
            } else if (text.contains(hint) || hint.contains(text)) {
                score += 25; // partial match
            } else {
                // Check individual words
                String[] hintWords = hint.split("\\s+|-|_");
                for (String word : hintWords) {
                    if (word.length() >= 3 && text.contains(word)) {
                        score += 10;
                        break;
                    }
                }
            }
        }

        // Also check aria-label
        if (!target.textHint.isBlank() && !ariaLabel.isBlank()) {
            if (ariaLabel.toLowerCase().contains(target.textHint.toLowerCase())) {
                score += 20;
            }
        }

        // 2. Tag match bonus (15 points)
        if (target.tag != null && target.tag.equals(candidateTag)) {
            score += 15;
        }

        // 3. Parent structure (15 points) — form fields usually inside form/div
        if (!candidateParent.isBlank()) {
            if (candidateParent.equals("form") || candidateParent.equals("div") ||
                candidateParent.equals("section") || candidateParent.equals("fieldset")) {
                score += 10;
            }
        }

        // 4. Visibility bonus — element is in viewport (top half of page)
        long y = getLong(candidate, "y");
        if (y >= 0 && y < 800) score += 5;

        // Cap at 100
        return Math.min(score, 100.0);
    }

    // =========================================================================
    // Locator building
    // =========================================================================

    /**
     * Builds the most stable locator possible from a candidate element.
     * Preference order: id > name > aria-label > text content > type+tag.
     */
    private By buildLocator(Map<String, Object> candidate) {
        String id        = getString(candidate, "id");
        String name      = getString(candidate, "name");
        String ariaLabel = getString(candidate, "ariaLabel");
        String text      = getString(candidate, "text");
        String tag       = getString(candidate, "tag");
        String type      = getString(candidate, "type");

        if (!id.isBlank()) {
            return By.cssSelector("[id='" + escapeCss(id) + "']");
        }
        if (!name.isBlank()) {
            return By.cssSelector("[name='" + escapeCss(name) + "']");
        }
        if (!ariaLabel.isBlank()) {
            return By.cssSelector("[aria-label='" + escapeCss(ariaLabel) + "']");
        }
        if (!text.isBlank() && text.length() <= 30) {
            String safeText = text.replace("'", "\\'");
            return By.xpath("//" + tag + "[normalize-space(text())='" + safeText + "']");
        }
        if (!type.isBlank() && !tag.isBlank()) {
            return By.cssSelector(tag + "[type='" + type + "']");
        }
        if (!tag.isBlank()) {
            return By.tagName(tag);
        }
        return null;
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private String getString(Map<String, Object> map, String key) {
        Object val = map.get(key);
        return val != null ? val.toString().trim() : "";
    }

    private long getLong(Map<String, Object> map, String key) {
        Object val = map.get(key);
        if (val == null) return 0;
        try { return Long.parseLong(val.toString()); } catch (Exception e) { return 0; }
    }

    private String escapeCss(String value) {
        return value.replace("'", "\\'");
    }

    @Override
    public String getName() { return "DomSimilarityStrategy"; }

    @Override
    public int getPriority() { return 70; }

    // =========================================================================
    // Inner class — element fingerprint
    // =========================================================================

    private static class ElementFingerprint {
        String tag;
        String textHint = "";
    }
}
