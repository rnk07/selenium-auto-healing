/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 */
package io.github.autoheal.healing;

import io.github.autoheal.healing.strategy.CssFallbackStrategy;
import org.testng.Assert;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import java.util.List;

public class CssFallbackStrategyTest {

    private CssFallbackStrategy strategy;

    @BeforeMethod
    public void setUp() {
        strategy = new CssFallbackStrategy();
    }

    @Test
    public void priorityShouldBeThirty() {
        Assert.assertEquals(strategy.getPriority(), 30);
    }

    @Test
    public void shouldHandleReactCssModuleTripleUnderscore() {
        List<String> candidates = strategy.buildCssCandidates("LoginForm__input___2abc");
        Assert.assertTrue(candidates.contains("LoginForm__input"),
                "React CSS Module triple underscore should be stripped");
    }

    @Test
    public void shouldHandleBemWithHash() {
        List<String> candidates = strategy.buildCssCandidates("form__field--error_abc123");
        Assert.assertTrue(candidates.stream().anyMatch(c -> c.contains("form__field")),
                "BEM element should be extracted");
    }

    @Test
    public void shouldHandleBemBlockExtraction() {
        List<String> candidates = strategy.buildCssCandidates("form__field--error");
        Assert.assertTrue(candidates.contains("form"),
                "BEM block should be extracted");
        Assert.assertTrue(candidates.contains("form__field"),
                "BEM element should be extracted");
    }

    @Test
    public void shouldHandleTailwindJitHash() {
        List<String> candidates = strategy.buildCssCandidates("text-blue-500_xK9m");
        Assert.assertTrue(candidates.contains("text-blue-500"),
                "Tailwind JIT hash should be stripped");
    }

    @Test
    public void shouldHandleCamelCaseToKebab() {
        List<String> candidates = strategy.buildCssCandidates("LoginFormInput");
        Assert.assertTrue(candidates.stream().anyMatch(c -> c.contains("login")),
                "camelCase should be converted to kebab");
    }

    @Test
    public void shouldHandleCamelCasePrefixStem() {
        List<String> candidates = strategy.buildCssCandidates("submitButton");
        Assert.assertTrue(candidates.contains("submit"),
                "camelCase prefix stem should be included");
    }

    @Test
    public void shouldHandleMultiWordCamelCase() {
        List<String> candidates = strategy.buildCssCandidates("LoginFormInput");
        Assert.assertTrue(candidates.stream().anyMatch(c -> c.equals("login") || c.equals("login-form")),
                "multi-word camelCase should produce prefix stems");
    }

    @Test
    public void shouldNotReturnBlankOrShortCandidates() {
        List<String> candidates = strategy.buildCssCandidates("btn_x");
        candidates.forEach(c ->
            Assert.assertTrue(c.length() >= 2,
                "all candidates should be at least 2 chars: " + c));
    }
}
