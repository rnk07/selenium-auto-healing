/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 */
package io.github.autoheal.healing;

import io.github.autoheal.healing.strategy.DomSimilarityStrategy;
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

public class DomSimilarityStrategyTest {

    private DomSimilarityStrategy strategy;
    private WebDriver mockDriver;

    interface JsWebDriver extends WebDriver, JavascriptExecutor {}

    @BeforeMethod
    public void setUp() {
        strategy   = new DomSimilarityStrategy();
        mockDriver = Mockito.mock(JsWebDriver.class);
    }

    @Test
    public void priorityShouldBeSeventy() {
        Assert.assertEquals(strategy.getPriority(), 70,
                "DomSimilarityStrategy must run last — after all other strategies");
    }

    @Test
    public void nameShouldBeDomSimilarityStrategy() {
        Assert.assertEquals(strategy.getName(), "DomSimilarityStrategy");
    }

    @Test
    public void shouldReturnNullWhenNoCandidatesFound() {
        JavascriptExecutor js = (JavascriptExecutor) mockDriver;
        when(js.executeScript(anyString())).thenReturn(Collections.emptyList());

        By healed = strategy.heal(mockDriver, By.cssSelector("button#submit-xyz123"));
        Assert.assertNull(healed, "should return null when no candidates found");
    }

    @Test
    public void shouldReturnNullForNonJsDriver() {
        WebDriver nonJs = Mockito.mock(WebDriver.class);
        By healed = strategy.heal(nonJs, By.id("submit"));
        Assert.assertNull(healed, "should return null when driver is not JavascriptExecutor");
    }

    @Test
    public void shouldReturnNullForBlankLocatorValue() {
        By healed = strategy.heal(mockDriver, By.cssSelector(""));
        Assert.assertNull(healed, "should return null for blank locator value");
    }

    @Test
    public void shouldRunAfterAllOtherStrategies() {
        // DomSimilarityStrategy priority 70 must be higher than all others
        int[] otherPriorities = {5, 10, 20, 30, 40, 50, 60};
        for (int p : otherPriorities) {
            Assert.assertTrue(strategy.getPriority() > p,
                    "DomSimilarityStrategy priority " + strategy.getPriority() +
                    " must be greater than " + p);
        }
    }

    @Test
    public void shouldReturnNullWhenJsReturnsNull() {
        JavascriptExecutor js = (JavascriptExecutor) mockDriver;
        when(js.executeScript(anyString())).thenReturn(null);

        By healed = strategy.heal(mockDriver, By.id("login-button"));
        Assert.assertNull(healed, "should return null when JS scan returns null");
    }
}
