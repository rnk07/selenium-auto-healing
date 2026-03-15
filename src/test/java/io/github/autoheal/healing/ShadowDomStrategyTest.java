/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 */
package io.github.autoheal.healing;

import io.github.autoheal.healing.strategy.ShadowDomStrategy;
import org.mockito.Mockito;
import org.openqa.selenium.By;
import org.openqa.selenium.JavascriptExecutor;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;
import org.testng.Assert;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import java.util.Collections;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

public class ShadowDomStrategyTest {

    private ShadowDomStrategy strategy;
    private WebDriver mockDriver;
    private WebElement mockHost;
    private WebElement mockElement;

    interface JsWebDriver extends WebDriver, JavascriptExecutor {}

    @BeforeMethod
    public void setUp() {
        strategy    = new ShadowDomStrategy();
        mockDriver  = Mockito.mock(JsWebDriver.class);
        mockHost    = Mockito.mock(WebElement.class);
        mockElement = Mockito.mock(WebElement.class);
    }

    @Test
    public void priorityShouldBeFifty() {
        Assert.assertEquals(strategy.getPriority(), 50);
    }

    @Test
    public void nameShouldBeShadowDomStrategy() {
        Assert.assertEquals(strategy.getName(), "ShadowDomStrategy");
    }

    @Test
    public void shouldReturnNullWhenNoShadowHostsFound() {
        JavascriptExecutor js = (JavascriptExecutor) mockDriver;
        when(js.executeScript(anyString())).thenReturn(Collections.emptyList());

        By healed = strategy.heal(mockDriver, By.cssSelector("button#submit-old"));
        Assert.assertNull(healed, "should return null when no shadow hosts exist");
    }

    @Test
    public void shouldReturnNullForNonJsDriver() {
        WebDriver nonJsDriver = Mockito.mock(WebDriver.class);
        By healed = strategy.heal(nonJsDriver, By.id("submit"));
        Assert.assertNull(healed, "should return null when driver is not JavascriptExecutor");
    }

    @Test
    public void shadowByToStringShouldBeDescriptive() {
        JavascriptExecutor js = (JavascriptExecutor) mockDriver;
        ShadowDomStrategy.ShadowBy shadowBy =
                new ShadowDomStrategy.ShadowBy("my-login", "[id*='submit']", js);
        String str = shadowBy.toString();
        Assert.assertTrue(str.contains("my-login"), "should contain host selector");
        Assert.assertTrue(str.contains("[id*='submit']"), "should contain inner selector");
    }

    @Test
    public void shadowByFindElementsShouldReturnEmptyOnFailure() {
        JavascriptExecutor js = (JavascriptExecutor) mockDriver;
        when(js.executeScript(anyString(), any(), any())).thenReturn(null);

        ShadowDomStrategy.ShadowBy shadowBy =
                new ShadowDomStrategy.ShadowBy("my-login", "[id*='submit']", js);
        Assert.assertTrue(shadowBy.findElements((org.openqa.selenium.SearchContext) mockDriver).isEmpty(),
                "should return empty list when JS returns null");
    }
}
