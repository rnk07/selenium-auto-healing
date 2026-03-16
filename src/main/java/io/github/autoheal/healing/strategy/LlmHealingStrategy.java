/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 */
package io.github.autoheal.healing.strategy;

import org.openqa.selenium.By;
import org.openqa.selenium.JavascriptExecutor;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * LlmHealingStrategy (priority 64) — Groq LLM healing via llama-3.1-8b-instant.
 *
 * <p>When all DOM-based strategies fail, this strategy sends the broken locator
 * and the current page's interactive DOM snapshot to the Groq API. The LLM
 * analyzes the DOM and returns the best CSS selector for the intended element.
 *
 * <p><b>Why Groq:</b>
 * <ul>
 *   <li>Free tier — no credit card required, 500k tokens/day</li>
 *   <li>Fast — llama-3.1-8b-instant responds in ~200ms</li>
 *   <li>OpenAI-compatible API — simple HTTP POST, no SDK needed</li>
 * </ul>
 *
 * <p><b>Setup — add your Groq API key one of two ways:</b>
 * <pre>
 * // Option 1: System property (recommended for CI)
 * mvn test -DGROQ_API_KEY=gsk_your_key_here
 *
 * // Option 2: Environment variable
 * export GROQ_API_KEY=gsk_your_key_here
 *
 * // Option 3: Pass directly in code
 * AutoHealingDriver driver = AutoHealingDriver.builder(new ChromeDriver())
 *         .withStrategy(new LlmHealingStrategy("gsk_your_key_here"))
 *         .build();
 * </pre>
 *
 * <p>If no API key is configured, the strategy logs a warning and skips silently.
 *
 * <p><b>Get your free Groq API key:</b> https://console.groq.com
 */
public class LlmHealingStrategy implements IHealingStrategy {

    private static final Logger LOG = LoggerFactory.getLogger(LlmHealingStrategy.class);

    private static final String GROQ_URL   = "https://api.groq.com/openai/v1/chat/completions";
    private static final String MODEL      = "llama-3.1-8b-instant";
    private static final int    MAX_TOKENS = 100;
    private static final int    TIMEOUT_MS = 8000;

    private final String apiKey;

    /**
     * Creates the strategy using GROQ_API_KEY from system properties or environment.
     */
    public LlmHealingStrategy() {
        this(resolveApiKey());
    }

    /**
     * Creates the strategy with an explicit API key.
     *
     * @param apiKey your Groq API key — get one free at https://console.groq.com
     */
    public LlmHealingStrategy(String apiKey) {
        this.apiKey = apiKey;
    }

    // =========================================================================
    // Healing
    // =========================================================================

    @Override
    public By heal(WebDriver driver, By broken) {
        if (apiKey == null || apiKey.isBlank()) {
            LOG.debug("[LlmHealingStrategy] No GROQ_API_KEY configured — skipping. " +
                      "Set system property -DGROQ_API_KEY=gsk_xxx or env var GROQ_API_KEY. " +
                      "Get a free key at https://console.groq.com");
            return null;
        }

        if (!(driver instanceof JavascriptExecutor)) return null;

        LOG.debug("[LlmHealingStrategy] Attempting Groq LLM healing for '{}'", broken);

        // Extract the raw selector value from the By toString
        String brokenSelector = extractSelector(broken.toString());

        // Capture a compact DOM snapshot of interactive elements
        String domSnapshot = captureDomSnapshot((JavascriptExecutor) driver);
        if (domSnapshot == null || domSnapshot.isBlank()) return null;

        // Ask the LLM
        String prompt = buildPrompt(brokenSelector, domSnapshot);
        String rawResponse = callGroq(prompt);
        if (rawResponse == null || rawResponse.isBlank()) return null;

        // Parse and validate the response
        By healed = parseLocator(rawResponse.trim());
        if (healed == null) return null;

        // Verify it finds a visible element
        try {
            List<WebElement> found = driver.findElements(healed);
            boolean visible = found.stream().anyMatch(e -> {
                try { return e.isDisplayed(); } catch (Exception ex) { return false; }
            });
            if (!visible) {
                LOG.debug("[LlmHealingStrategy] LLM suggested '{}' but no visible element found.",
                          healed);
                return null;
            }
        } catch (Exception e) {
            return null;
        }

        LOG.info("[LlmHealingStrategy] Groq healed '{}' -> '{}'", broken, healed);
        return healed;
    }

    // =========================================================================
    // Prompt construction
    // =========================================================================

    private String buildPrompt(String brokenSelector, String domSnapshot) {
        return "You are a Selenium test automation expert. " +
               "A locator has broken and you must find the correct CSS selector.\n\n" +
               "Broken locator: " + brokenSelector + "\n\n" +
               "Current page interactive elements (tag, id, name, type, text, aria-label):\n" +
               domSnapshot + "\n\n" +
               "Based on the broken locator name and the page elements, " +
               "which CSS selector most likely identifies the intended element?\n" +
               "Rules:\n" +
               "- Return ONLY the CSS selector string, nothing else\n" +
               "- No explanation, no markdown, no quotes\n" +
               "- Prefer id-based selectors like: input[id='username']\n" +
               "- If no match, return: NONE\n" +
               "CSS selector:";
    }

    // =========================================================================
    // DOM snapshot — compact text representation of interactive elements
    // =========================================================================

    @SuppressWarnings("unchecked")
    private String captureDomSnapshot(JavascriptExecutor js) {
        try {
            String script =
                "var els = document.querySelectorAll('input,button,a,select,textarea,label');" +
                "var lines = [];" +
                "for (var i = 0; i < Math.min(els.length, 50); i++) {" +
                "  var el = els[i];" +
                "  if (!el.offsetParent && el.tagName.toLowerCase() !== 'body') continue;" +
                "  var parts = [el.tagName.toLowerCase()];" +
                "  if (el.id)                            parts.push('id=' + el.id);" +
                "  if (el.getAttribute('name'))          parts.push('name=' + el.getAttribute('name'));" +
                "  if (el.getAttribute('type'))          parts.push('type=' + el.getAttribute('type'));" +
                "  if (el.getAttribute('placeholder'))   parts.push('placeholder=' + el.getAttribute('placeholder'));" +
                "  if (el.getAttribute('aria-label'))    parts.push('aria=' + el.getAttribute('aria-label'));" +
                "  var txt = (el.textContent || '').trim().substring(0, 30);" +
                "  if (txt) parts.push('text=' + txt);" +
                "  lines.push(parts.join(' | '));" +
                "}" +
                "return lines.join('\\n');";
            Object result = js.executeScript(script);
            return result != null ? result.toString() : null;
        } catch (Exception e) {
            LOG.debug("[LlmHealingStrategy] DOM snapshot failed: {}", e.getMessage());
            return null;
        }
    }

    // =========================================================================
    // Groq API call — pure Java HTTP, no external SDK
    // =========================================================================

    private String callGroq(String prompt) {
        try {
            String body = "{" +
                "\"model\":\"" + MODEL + "\"," +
                "\"max_tokens\":" + MAX_TOKENS + "," +
                "\"temperature\":0," +
                "\"messages\":[{\"role\":\"user\",\"content\":" +
                jsonEscape(prompt) + "}]}";

            URL url = new URL(GROQ_URL);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setRequestProperty("Authorization", "Bearer " + apiKey);
            conn.setDoOutput(true);
            conn.setConnectTimeout(TIMEOUT_MS);
            conn.setReadTimeout(TIMEOUT_MS);

            try (OutputStream os = conn.getOutputStream()) {
                os.write(body.getBytes(StandardCharsets.UTF_8));
            }

            int status = conn.getResponseCode();
            if (status == 429) {
                LOG.warn("[LlmHealingStrategy] Groq rate limit hit. Skipping.");
                return null;
            }
            if (status != 200) {
                LOG.debug("[LlmHealingStrategy] Groq returned HTTP {}", status);
                return null;
            }

            String response = new String(
                    conn.getInputStream().readAllBytes(), StandardCharsets.UTF_8);

            // Parse: {"choices":[{"message":{"content":"<selector>"}}]}
            return extractContent(response);

        } catch (java.net.SocketTimeoutException e) {
            LOG.debug("[LlmHealingStrategy] Groq request timed out after {}ms", TIMEOUT_MS);
            return null;
        } catch (Exception e) {
            LOG.debug("[LlmHealingStrategy] Groq API error: {}", e.getMessage());
            return null;
        }
    }

    // =========================================================================
    // Response parsing
    // =========================================================================

    private By parseLocator(String raw) {
        if (raw.equalsIgnoreCase("NONE") || raw.isBlank()) return null;

        // Strip any accidental markdown backticks or quotes
        String clean = raw.replaceAll("^[`'\"]+|[`'\"]+$", "").trim();
        if (clean.isBlank() || clean.equalsIgnoreCase("NONE")) return null;

        try {
            // Validate it is a plausible CSS selector
            if (clean.contains("<") || clean.contains("\n") || clean.length() > 200) return null;

            // XPath-style response — convert
            if (clean.startsWith("//")) return By.xpath(clean);

            return By.cssSelector(clean);
        } catch (Exception e) {
            LOG.debug("[LlmHealingStrategy] Could not parse LLM response as locator: '{}'", raw);
            return null;
        }
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private static String resolveApiKey() {
        String prop = System.getProperty("GROQ_API_KEY");
        if (prop != null && !prop.isBlank()) return prop;
        String env = System.getenv("GROQ_API_KEY");
        if (env != null && !env.isBlank()) return env;
        return null;
    }

    private String extractSelector(String byString) {
        if (byString.contains(": "))
            return byString.substring(byString.indexOf(": ") + 2).trim();
        return byString;
    }

    private String extractContent(String json) {
        // Simple extraction without Jackson dependency
        int idx = json.indexOf("\"content\":");
        if (idx < 0) return null;
        int start = json.indexOf('"', idx + 10) + 1;
        int end   = json.indexOf('"', start);
        if (start <= 0 || end <= start) return null;
        return json.substring(start, end)
                   .replace("\\n", "\n")
                   .replace("\\\"", "\"")
                   .trim();
    }

    private String jsonEscape(String s) {
        return "\"" + s
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t") + "\"";
    }

    @Override public String getName()    { return "LlmHealingStrategy"; }
    @Override public int    getPriority(){ return 64; }
}
