/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 */
package io.github.autoheal.healing;

import io.github.autoheal.healing.strategy.IframeStrategy;
import org.mockito.Mockito;
import org.openqa.selenium.By;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;
import org.testng.Assert;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import java.util.Collections;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

public class IframeStrategyTest {

    private IframeStrategy strategy;
    private WebDriver mockDriver;
    private WebDriver.TargetLocator mockTargetLocator;

    @BeforeMethod
    public void setUp() {
        strategy           = new IframeStrategy();
        mockDriver         = Mockito.mock(WebDriver.class);
        mockTargetLocator  = Mockito.mock(WebDriver.TargetLocator.class);
        when(mockDriver.switchTo()).thenReturn(mockTargetLocator);
        when(mockTargetLocator.defaultContent()).thenReturn(mockDriver);
    }

    @Test
    public void priorityShouldBeSixty() {
        Assert.assertEquals(strategy.getPriority(), 60);
    }

    @Test
    public void nameShouldBeIframeStrategy() {
        Assert.assertEquals(strategy.getName(), "IframeStrategy");
    }

    @Test
    public void shouldReturnNullWhenNoIframesFound() {
        when(mockDriver.findElements(By.tagName("iframe")))
                .thenReturn(Collections.emptyList());

        By healed = strategy.heal(mockDriver, By.cssSelector("input#card-number-old"));
        Assert.assertNull(healed, "should return null when no iframes exist");
    }

    @Test
    public void iframeByToStringShouldBeDescriptive() {
        IframeStrategy.IframeBy iframeBy =
                new IframeStrategy.IframeBy(0, "[id*='card-number']");
        String str = iframeBy.toString();
        Assert.assertTrue(str.contains("frame=0"), "should contain frame index");
        Assert.assertTrue(str.contains("[id*='card-number']"), "should contain inner selector");
    }

    @Test
    public void iframeByFindElementsShouldReturnEmptyForNonWebDriver() {
        IframeStrategy.IframeBy iframeBy =
                new IframeStrategy.IframeBy(0, "[id*='card']");
        WebElement mockElement = Mockito.mock(WebElement.class);
        Assert.assertTrue(
                iframeBy.findElements(mockElement).isEmpty(),
                "should return empty list when context is not WebDriver");
    }

    @Test
    public void shouldReturnNullForBlankLocatorValue() {
        By healed = strategy.heal(mockDriver, By.cssSelector(""));
        Assert.assertNull(healed, "should return null for blank locator");
    }
}
