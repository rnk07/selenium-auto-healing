/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 */
package io.github.autoheal.healing;

import io.github.autoheal.healing.report.HealingEvent;
import io.github.autoheal.healing.report.HealingReport;
import org.openqa.selenium.By;
import org.testng.Assert;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import java.io.File;

public class HealingReportTest {

    private HealingReport report;

    @BeforeMethod
    public void setUp() {
        report = new HealingReport();
        HealingReport.setCurrentTestName("HealingReportTest.test");
    }

    @Test
    public void shouldStartEmpty() {
        Assert.assertEquals(report.getTotalHealed(), 0);
        Assert.assertTrue(report.getEvents().isEmpty());
    }

    @Test
    public void shouldRecordHealingEvent() {
        report.log(By.id("old"), By.cssSelector("[id*='old']"), "AttributeFallbackStrategy");

        Assert.assertEquals(report.getTotalHealed(), 1);
        HealingEvent event = report.getEvents().get(0);
        Assert.assertEquals(event.getBrokenLocator(),  "By.id: old");
        Assert.assertEquals(event.getStrategyUsed(),   "AttributeFallbackStrategy");
        Assert.assertEquals(event.getTestName(),       "HealingReportTest.test");
        Assert.assertNotNull(event.getTimestamp());
    }

    @Test
    public void shouldRecordMultipleEvents() {
        report.log(By.id("a"), By.cssSelector("[id*='a']"), "AttributeFallbackStrategy");
        report.log(By.id("b"), By.xpath("//button[1]"),    "XPathFallbackStrategy");
        Assert.assertEquals(report.getTotalHealed(), 2);
    }

    @Test
    public void eventListShouldBeUnmodifiable() {
        report.log(By.id("x"), By.cssSelector("[id*='x']"), "AttributeFallbackStrategy");
        Assert.assertThrows(UnsupportedOperationException.class,
                () -> report.getEvents().clear());
    }

    @Test
    public void writeReportShouldCreateJsonFile() {
        report.log(By.id("btn"), By.cssSelector("[id*='btn']"), "AttributeFallbackStrategy");
        report.writeReport();

        File dir = new File("target/healing-report");
        Assert.assertTrue(dir.exists(), "report directory should be created");

        File[] files = dir.listFiles(
                (d, n) -> n.startsWith("healing-report-") && n.endsWith(".json"));
        Assert.assertNotNull(files);
        Assert.assertTrue(files.length > 0, "at least one JSON report should exist");
    }

    @Test
    public void writeReportShouldHandleEmptyGracefully() {
        // No exception expected when nothing was healed
        report.writeReport();
        Assert.assertEquals(report.getTotalHealed(), 0);
    }
}
