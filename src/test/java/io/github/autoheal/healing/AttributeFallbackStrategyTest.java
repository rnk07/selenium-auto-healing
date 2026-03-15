/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 */
package io.github.autoheal.healing;

import io.github.autoheal.healing.strategy.AttributeFallbackStrategy;
import org.mockito.Mockito;
import org.openqa.selenium.By;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;
import org.testng.Assert;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

import java.util.Collections;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

public class AttributeFallbackStrategyTest {

    private AttributeFallbackStrategy strategy;
    private WebDriver mockDriver;
    private WebElement mockElement;

    @BeforeMethod
    public void setUp() {
        strategy    = new AttributeFallbackStrategy();
        mockDriver  = Mockito.mock(WebDriver.class);
        mockElement = Mockito.mock(WebElement.class);
        when(mockElement.isDisplayed()).thenReturn(true);
    }

    @Test(dataProvider = "extractionData")
    public void extractCoreValueShouldParseLocatorTypes(String byString, String expected) {
        Assert.assertEquals(AttributeFallbackStrategy.extractCoreValue(byString), expected);
    }

    @DataProvider(name = "extractionData")
    public Object[][] extractionData() {
        return new Object[][]{
                {"By.id: submit-btn",               "submit-btn"},
                {"By.name: username",               "username"},
                {"By.cssSelector: #login-form",     "login-form"},
                {"By.cssSelector: .submit-button",  "submit-button"},
        };
    }

    @Test
    public void buildStemsShouldGenerateProgressiveVariants() {
        List<String> stems = strategy.buildStems("submit-btn-old");
        Assert.assertTrue(stems.contains("submit-btn-old"), "original value");
        Assert.assertTrue(stems.contains("submit-btn"),     "version-stripped");
        Assert.assertTrue(stems.contains("submit"),         "prefix segment");
    }

    @Test
    public void buildStemsShouldHandleCamelCase() {
        List<String> stems = strategy.buildStems("loginButton");
        Assert.assertTrue(stems.contains("loginButton"), "original");
        Assert.assertTrue(stems.contains("login"),       "camelCase prefix");
    }

    @Test
    public void buildStemsShouldStripTrailingDash() {
        List<String> stems = strategy.buildStems("username-");
        Assert.assertTrue(stems.contains("username"), "trailing dash should be stripped");
    }

    @Test
    public void buildStemsShouldStripTrailingUnderscore() {
        List<String> stems = strategy.buildStems("password_");
        Assert.assertTrue(stems.contains("password"), "trailing underscore should be stripped");
    }

    @Test
    public void buildStemsShouldStripMultipleTrailingDashes() {
        List<String> stems = strategy.buildStems("submit--");
        Assert.assertTrue(stems.contains("submit"), "multiple trailing dashes should be stripped");
    }

    @Test
    public void buildStemsShouldStripCssModuleHash() {
        List<String> stems = strategy.buildStems("input_abc123xyz");
        Assert.assertTrue(stems.contains("input"), "CSS module hash should be stripped");
    }

    @Test
    public void buildStemsShouldStripShortRandomHash() {
        List<String> stems = strategy.buildStems("field_xK9mP2");
        Assert.assertTrue(stems.contains("field"), "short random hash should be stripped");
    }

    @Test
    public void buildStemsShouldStripAngularMaterialIndex() {
        List<String> stems = strategy.buildStems("mat-input-0");
        Assert.assertTrue(stems.contains("mat-input"), "Angular Material index should be stripped");
    }

    @Test
    public void buildStemsShouldHandleVuetifyGeneratedClass() {
        List<String> stems = strategy.buildStems("v-input--is-dirty-456");
        Assert.assertTrue(stems.contains("v-input--is-dirty"), "Vuetify suffix should be stripped");
    }

    @Test
    public void healShouldReturnLocatorWhenAttributeMatchFound() {
        when(mockDriver.findElements(any(By.class)))
                .thenReturn(Collections.singletonList(mockElement));

        By healed = strategy.heal(mockDriver, By.id("submit-btn-old"));

        Assert.assertNotNull(healed, "should return healed locator");
    }

    @Test
    public void healShouldReturnNullWhenNoMatchFound() {
        when(mockDriver.findElements(any(By.class)))
                .thenReturn(Collections.emptyList());

        By healed = strategy.heal(mockDriver, By.id("xyz-nonexistent-12345"));

        Assert.assertNull(healed, "should return null when element not found");
    }

    @Test
    public void healShouldSkipHiddenElements() {
        WebElement hidden = Mockito.mock(WebElement.class);
        when(hidden.isDisplayed()).thenReturn(false);
        WebElement visible = Mockito.mock(WebElement.class);
        when(visible.isDisplayed()).thenReturn(true);

        when(mockDriver.findElements(any(By.class)))
                .thenReturn(Collections.singletonList(hidden))
                .thenReturn(Collections.singletonList(visible));

        By healed = strategy.heal(mockDriver, By.id("my-btn"));
        Assert.assertNotNull(healed, "should find the visible element");
    }

    @Test
    public void priorityShouldBeTen() {
        Assert.assertEquals(strategy.getPriority(), 10);
    }
}
