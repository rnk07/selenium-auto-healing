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
package io.github.autoheal.healing.listener;

import io.github.autoheal.healing.report.HealingReport;

/**
 * TestNG integration helper for {@link HealingReport}.
 *
 * <p>Provides static methods to register the report and set the current
 * test name. Call these from your TestNG {@code ITestListener} or BaseTest.
 *
 * <p><b>Typical wiring in BaseTest:</b>
 * <pre>{@code
 * @BeforeMethod
 * public void setUp(ITestResult result) {
 *     driver = AutoHealingDriver.builder(new ChromeDriver()).build();
 *     HealingReportListener.register(driver.getHealingReport());
 *     HealingReportListener.setCurrentTest(
 *         result.getTestClass().getRealClass().getSimpleName()
 *         + "." + result.getMethod().getMethodName());
 * }
 *
 * @AfterSuite
 * public void writeReport() {
 *     HealingReportListener.writeReport();
 * }
 * }</pre>
 */
public final class HealingReportListener {

    private static HealingReport suiteReport;

    private HealingReportListener() { }

    /**
     * Register the {@link HealingReport} from your {@link io.github.autoheal.healing.driver.AutoHealingDriver}.
     * Call this once per test session after building the driver.
     *
     * @param report the report instance from {@code driver.getHealingReport()}
     */
    public static void register(HealingReport report) {
        suiteReport = report;
    }

    /**
     * Set the currently running test name for healing event attribution.
     * Call this in {@code @BeforeMethod} or {@code ITestListener.onTestStart()}.
     *
     * @param testName fully qualified test name, e.g. "LoginTest.shouldLoginSuccessfully"
     */
    public static void setCurrentTest(String testName) {
        HealingReport.setCurrentTestName(testName);
    }

    /**
     * Write the JSON healing report. Call this in {@code @AfterSuite}.
     * Safe to call even if no healing occurred — logs a clean message and returns.
     */
    public static void writeReport() {
        if (suiteReport != null) {
            suiteReport.writeReport();
        }
    }

    /**
     * Returns the registered report, or {@code null} if not yet registered.
     *
     * @return the current {@link HealingReport} instance
     */
    public static HealingReport getReport() {
        return suiteReport;
    }
}
