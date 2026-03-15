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
package io.github.autoheal.healing.report;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.openqa.selenium.By;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.stream.Collectors;
import java.util.List;

/**
 * Collects all locator healing events during a test run and writes a JSON
 * report at suite completion. Each event represents one Page Object locator
 * that needs to be updated.
 *
 * <p><b>Report location:</b> {@code target/healing-report/healing-report-&lt;timestamp&gt;.json}
 *
 * <p><b>Usage in @AfterSuite:</b>
 * <pre>{@code
 * AutoHealingDriver driver = AutoHealingDriver.builder(new ChromeDriver()).build();
 * // ... run tests ...
 * driver.getHealingReport().writeReport();
 * }</pre>
 */
public class HealingReport {

    private static final Logger LOG = LoggerFactory.getLogger(HealingReport.class);
    private static final String REPORT_DIR = "target/healing-report";

    private final List<HealingEvent> events =
            Collections.synchronizedList(new ArrayList<>());

    private static final ThreadLocal<String> currentTestName = new ThreadLocal<>();

    /**
     * Set the currently executing test name for event attribution.
     * Call this from your TestNG {@code ITestListener.onTestStart()}.
     */
    public static void setCurrentTestName(String name) {
        currentTestName.set(name);
    }

    /**
     * Record a successful healing event.
     *
     * @param broken   the By that failed
     * @param healed   the By that succeeded
     * @param strategy the strategy name that performed the heal
     */
    public void log(By broken, By healed, String strategy) {
        String testName = currentTestName.get();
        if (testName == null) testName = "unknown";
        HealingEvent event = new HealingEvent(
                broken.toString(), healed.toString(), strategy, testName);
        events.add(event);
        LOG.warn("HEALED: {}", event);
    }

    /** Returns all recorded healing events (unmodifiable). */
    public List<HealingEvent> getEvents() {
        return Collections.unmodifiableList(events);
    }

    /**
     * Logs a per-test summary immediately after a test completes.
     *
     * <p>Called by {@link io.github.autoheal.healing.listener.AutoHealing}
     * in {@code afterInvocation()} — fires after every test method, not just
     * at suite end. Useful for parallel runs where you need to know which
     * test healed which locator in real time.
     *
     * <p><b>Example output:</b>
     * <pre>
     *   TEST SUMMARY: loginTest.loginMethodTest
     *     2 locator(s) healed:
     *     [1] broken='button#submit-old' healed='[id=submit]' via=AttributeFallbackStrategy
     *     [2] broken='input#password-old' healed='[id=password]' via=AttributeFallbackStrategy
     *
     *   TEST SUMMARY: checkoutTest.placeOrder — no healing needed
     * </pre>
     *
     * @param testName the fully qualified test name
     */
    public void logTestSummary(String testName) {
        if (testName == null) return;

        // Find all events for this specific test
        List<HealingEvent> testEvents = events.stream()
                .filter(e -> testName.equals(e.getTestName()))
                .collect(java.util.stream.Collectors.toList());

        if (testEvents.isEmpty()) {
            LOG.info("TEST SUMMARY: {} — no healing needed", testName);
            return;
        }

        StringBuilder sb = new StringBuilder();
        sb.append("TEST SUMMARY: ").append(testName).append("\n");
        sb.append("  ").append(testEvents.size()).append(" locator(s) healed:\n");
        for (int i = 0; i < testEvents.size(); i++) {
            HealingEvent e = testEvents.get(i);
            sb.append("  [").append(i + 1).append("] ")
              .append("broken=\'").append(e.getBrokenLocator()).append("\'")
              .append(" healed=\'").append(e.getHealedLocator()).append("\'")
              .append(" via=").append(e.getStrategyUsed());
            if (i < testEvents.size() - 1) sb.append("\n");
        }
        LOG.warn("{}", sb);
    }

    /** Total number of healed locators this run. */
    public int getTotalHealed() {
        return events.size();
    }

    /**
     * Write the healing report to JSON.
     * Call this from your {@code @AfterSuite} method.
     */
    public void writeReport() {
        if (events.isEmpty()) {
            LOG.info("No locator healing occurred — all locators are healthy.");
            return;
        }
        try {
            File dir = new File(REPORT_DIR);
            dir.mkdirs();
            String ts = LocalDateTime.now()
                    .format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
            File reportFile = new File(dir, "healing-report-" + ts + ".json");
            ReportWrapper wrapper = new ReportWrapper(events.size(), events);
            new ObjectMapper()
                    .enable(SerializationFeature.INDENT_OUTPUT)
                    .writeValue(reportFile, wrapper);
            LOG.warn("HEALING REPORT written to: {}", reportFile.getAbsolutePath());
            LOG.warn("{} locator(s) were auto-healed. Please update your Page Objects.",
                    events.size());
        } catch (IOException e) {
            LOG.error("Failed to write healing report: {}", e.getMessage());
        }
    }

    /** Internal wrapper for JSON serialization. */
    public static class ReportWrapper {
        public final int totalHealed;
        public final String generatedAt;
        public final List<HealingEvent> events;

        public ReportWrapper(int totalHealed, List<HealingEvent> events) {
            this.totalHealed = totalHealed;
            this.generatedAt = LocalDateTime.now()
                    .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
            this.events = events;
        }
    }
}
