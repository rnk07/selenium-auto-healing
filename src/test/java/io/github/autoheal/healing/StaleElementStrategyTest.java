/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 */
package io.github.autoheal.healing;

import io.github.autoheal.healing.strategy.StaleElementStrategy;
import org.mockito.Mockito;
import org.openqa.selenium.By;
import org.openqa.selenium.JavascriptExecutor;
import org.openqa.selenium.StaleElementReferenceException;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;
import org.testng.Assert;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import java.util.Collections;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

public class StaleElementStrategyTest {

    private StaleElementStrategy strategy;
    private WebDriver mockDriver;
    private WebElement mockElement;

    interface JsWebDriver extends WebDriver, JavascriptExecutor {}

    @BeforeMethod
    public void setUp() {
        strategy    = new StaleElementStrategy();
        mockDriver  = Mockito.mock(JsWebDriver.class);
        mockElement = Mockito.mock(WebElement.class);
    }

    @Test
    public void priorityShouldBeFive() {
        Assert.assertEquals(strategy.getPriority(), 5,
                "StaleElementStrategy must run before all other strategies");
    }

    @Test
    public void nameShouldBeStaleElementStrategy() {
        Assert.assertEquals(strategy.getName(), "StaleElementStrategy");
    }

    @Test
    public void shouldReturnSameLocatorWhenElementReFound() {
        JavascriptExecutor js = (JavascriptExecutor) mockDriver;
        when(js.executeScript(anyString())).thenReturn("complete");
        when(mockElement.isDisplayed()).thenReturn(true);
        when(mockDriver.findElements(any(By.class)))
                .thenReturn(Collections.singletonList(mockElement));

        By broken = By.id("submit");
        By healed = strategy.heal(mockDriver, broken);

        Assert.assertNotNull(healed, "should return a locator when element re-found");
        Assert.assertEquals(healed, broken,
                "should return the SAME locator — stale element just needs re-finding");
    }

    @Test
    public void shouldReturnNullWhenElementCannotBeReFound() {
        JavascriptExecutor js = (JavascriptExecutor) mockDriver;
        when(js.executeScript(anyString())).thenReturn("complete");
        when(mockDriver.findElements(any(By.class)))
                .thenReturn(Collections.emptyList());

        By healed = strategy.heal(mockDriver, By.id("submit"));
        Assert.assertNull(healed,
                "should return null when element cannot be re-found after DOM refresh");
    }

    @Test
    public void shouldReturnNullWhenElementRemainsStale() {
        JavascriptExecutor js = (JavascriptExecutor) mockDriver;
        when(js.executeScript(anyString())).thenReturn("complete");
        when(mockDriver.findElements(any(By.class)))
                .thenThrow(new StaleElementReferenceException("still stale"));

        By healed = strategy.heal(mockDriver, By.id("submit"));
        Assert.assertNull(healed,
                "should return null when element remains stale after all retries");
    }

    @Test
    public void shouldSkipHiddenElements() {
        JavascriptExecutor js = (JavascriptExecutor) mockDriver;
        when(js.executeScript(anyString())).thenReturn("complete");
        when(mockElement.isDisplayed()).thenReturn(false);
        when(mockDriver.findElements(any(By.class)))
                .thenReturn(Collections.singletonList(mockElement));

        By healed = strategy.heal(mockDriver, By.id("hidden-btn"));
        Assert.assertNull(healed,
                "should return null when re-found element is not visible");
    }
}
