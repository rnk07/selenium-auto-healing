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

import io.github.autoheal.healing.driver.AutoHealingDriver;
import io.github.autoheal.healing.report.HealingReport;
import org.openqa.selenium.WebDriver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testng.IInvokedMethod;
import org.testng.IInvokedMethodListener;
import org.testng.ISuite;
import org.testng.ISuiteListener;
import org.testng.ITestResult;

import java.lang.reflect.Field;

/**
 * AutoHealing — a zero-configuration TestNG listener that automatically
 * wraps any {@link WebDriver} field named {@code driver} with
 * {@link AutoHealingDriver}.
 *
 * <p>Mirrors the simplicity of TestNG's {@code IRetryAnalyzer} — just add
 * one annotation to your Base class and healing works everywhere:
 *
 * <pre>{@code
 * @Listeners(AutoHealing.class)
 * public class Base {
 *     protected WebDriver driver; // no other changes needed
 * }
 * }</pre>
 *
 * <p><b>What it does automatically:</b>
 * <ul>
 *   <li>Before each test: finds the {@code driver} field anywhere in the
 *       class hierarchy, wraps it with {@link AutoHealingDriver}</li>
 *   <li>After each test: sets the current test name for report attribution</li>
 *   <li>After the suite: writes the JSON healing report to
 *       {@code target/healing-report/}</li>
 * </ul>
 *
 * <p><b>Custom field name:</b> If your driver field is not named {@code driver},
 * use {@link HealingDriverField} annotation:
 * <pre>{@code
 * @Listeners(AutoHealing.class)
 * public class Base {
 *     @HealingDriverField
 *     protected WebDriver myWebDriver;
 * }
 * }</pre>
 */
public class AutoHealing implements IInvokedMethodListener, ISuiteListener {

    private static final Logger LOG = LoggerFactory.getLogger(AutoHealing.class);

    // Shared report across the entire suite
    private static final HealingReport SUITE_REPORT = new HealingReport();

    // Default field name to look for
    private static final String DEFAULT_DRIVER_FIELD = "driver";

    // =========================================================================
    // IInvokedMethodListener — fires before and after every test method
    // =========================================================================

    @Override
    public void beforeInvocation(IInvokedMethod method, ITestResult testResult) {
        if (!method.isTestMethod()) return;

        Object testInstance = testResult.getInstance();
        String testName = testResult.getTestClass().getRealClass().getSimpleName()
                + "." + method.getTestMethod().getMethodName();

        // Set test name for healing report attribution
        HealingReport.setCurrentTestName(testName);

        try {
            // Find the driver field anywhere in the class hierarchy
            Field driverField = findDriverField(testInstance.getClass());
            if (driverField == null) {
                LOG.debug("[AutoHealing] No driver field found in {}", testInstance.getClass().getName());
                return;
            }

            driverField.setAccessible(true);
            Object fieldValue = driverField.get(testInstance);

            // Only wrap if it is a plain WebDriver — not already wrapped
            if (fieldValue instanceof WebDriver && !(fieldValue instanceof AutoHealingDriver)) {
                WebDriver originalDriver = (WebDriver) fieldValue;
                AutoHealingDriver healingDriver = AutoHealingDriver
                        .builder(originalDriver)
                        .withReport(SUITE_REPORT)
                        .build();

                // Set the wrapped driver back into ALL classes in the hierarchy
                // that have a field with the same name (e.g. both Base and BasePage)
                injectDriverIntoHierarchy(testInstance, driverField.getName(), healingDriver);

                LOG.debug("[AutoHealing] Wrapped driver for test: {}", testName);
            }

        } catch (Exception e) {
            LOG.warn("[AutoHealing] Could not wrap driver: {}", e.getMessage());
        }
    }

    @Override
    public void afterInvocation(IInvokedMethod method, ITestResult testResult) {
        // Clear test name after each test
        if (method.isTestMethod()) {
            HealingReport.setCurrentTestName(null);
        }
    }

    // =========================================================================
    // ISuiteListener — fires at end of suite to write the report
    // =========================================================================

    @Override
    public void onStart(ISuite suite) {
        // nothing needed at suite start
    }

    @Override
    public void onFinish(ISuite suite) {
        SUITE_REPORT.writeReport();
    }

    // =========================================================================
    // Private helpers
    // =========================================================================

    /**
     * Searches the class hierarchy for a WebDriver field.
     * First looks for a field annotated with {@link HealingDriverField},
     * then falls back to the default name {@code "driver"}.
     *
     * @param clazz the test class to search
     * @return the matching {@link Field}, or {@code null} if not found
     */
    private Field findDriverField(Class<?> clazz) {
        Class<?> current = clazz;
        while (current != null && current != Object.class) {
            // First priority: look for @HealingDriverField annotation
            for (Field field : current.getDeclaredFields()) {
                if (field.isAnnotationPresent(HealingDriverField.class)
                        && WebDriver.class.isAssignableFrom(field.getType())) {
                    return field;
                }
            }
            current = current.getSuperclass();
        }

        // Second priority: look for field named "driver"
        current = clazz;
        while (current != null && current != Object.class) {
            try {
                Field field = current.getDeclaredField(DEFAULT_DRIVER_FIELD);
                if (WebDriver.class.isAssignableFrom(field.getType())) {
                    return field;
                }
            } catch (NoSuchFieldException ignored) { }
            current = current.getSuperclass();
        }

        return null;
    }

    /**
     * Injects the {@link AutoHealingDriver} into every class in the hierarchy
     * that declares a field with the given name.
     *
     * <p>This handles the common pattern where {@code driver} is declared in
     * both {@code Base} and {@code BasePage} — both get the wrapped driver.
     *
     * @param instance      the test instance
     * @param fieldName     the field name to inject into
     * @param healingDriver the wrapped driver to inject
     */
    private void injectDriverIntoHierarchy(Object instance,
                                            String fieldName,
                                            AutoHealingDriver healingDriver) {
        Class<?> current = instance.getClass();
        while (current != null && current != Object.class) {
            try {
                Field field = current.getDeclaredField(fieldName);
                if (WebDriver.class.isAssignableFrom(field.getType())) {
                    field.setAccessible(true);
                    field.set(instance, healingDriver);
                    LOG.debug("[AutoHealing] Injected into {}.{}",
                            current.getSimpleName(), fieldName);
                }
            } catch (NoSuchFieldException ignored) {
                // This class in hierarchy doesn't have the field — move on
            } catch (IllegalAccessException e) {
                LOG.warn("[AutoHealing] Could not inject into {}.{}: {}",
                        current.getSimpleName(), fieldName, e.getMessage());
            }
            current = current.getSuperclass();
        }
    }
}
