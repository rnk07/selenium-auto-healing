# selenium-auto-healing

> A pluggable auto-healing locator library for Selenium 4 + TestNG

[![Maven Central](https://img.shields.io/maven-central/v/io.github.your-github-username/selenium-auto-healing)](https://central.sonatype.com/artifact/io.github.your-github-username/selenium-auto-healing)
[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](https://www.apache.org/licenses/LICENSE-2.0)
[![Java](https://img.shields.io/badge/Java-11%2B-orange)](https://www.oracle.com/java/)
[![Selenium](https://img.shields.io/badge/Selenium-4.x-green)](https://www.selenium.dev/)

---

## The Problem

```
NoSuchElementException: no such element: {"method":"id","selector":"submit-btn"}
```

The button still exists. A developer just renamed `submit-btn` → `submit-button` in a sprint.
Your test fails. CI is red. You spend an hour tracking down the change.

---

## The Solution

```java
// ONE line change in your BaseTest — everything else stays the same
AutoHealingDriver driver = AutoHealingDriver.builder(new ChromeDriver()).build();

// Your existing Page Objects work unchanged
driver.findElement(By.id("submit-btn")).click(); // healed automatically if renamed
```

When a locator fails, four healing strategies run in priority order and find the element using stable attributes, text content, CSS class matching, or DOM sibling walking. The test passes. A JSON report tells you exactly which locators need to be updated.

---

## Add to Your Project

```xml
<dependency>
    <groupId>io.github.your-github-username</groupId>
    <artifactId>selenium-auto-healing</artifactId>
    <version>1.0.0</version>
</dependency>
```

---

## Quick Start

### 1. Change one line in BaseTest

```java
@BeforeMethod
public void setUp(ITestResult result) {
    driver = AutoHealingDriver.builder(new ChromeDriver()).build();
    driver.manage().timeouts().implicitlyWait(Duration.ofSeconds(5));

    HealingReportListener.register(driver.getHealingReport());
    HealingReportListener.setCurrentTest(
        result.getTestClass().getRealClass().getSimpleName()
        + "." + result.getMethod().getMethodName());
}

@AfterMethod
public void tearDown() {
    if (driver != null) driver.quit();
}

@AfterSuite
public void writeReport() {
    HealingReportListener.writeReport();
}
```

### 2. Your Page Objects — zero changes needed

```java
public class LoginPage {
    private final By submitBtn = By.id("submit-btn");  // even if this breaks, healing kicks in

    public void clickSubmit() {
        driver.findElement(submitBtn).click();  // healed transparently
    }
}
```

### 3. Check the healing report after the run

```
target/healing-report/healing-report-20240315-103045.json
```

```json
{
  "totalHealed": 1,
  "events": [{
    "testName": "LoginTest.shouldLoginSuccessfully",
    "brokenLocator": "By.id: submit-btn",
    "healedLocator": "[id*='submit-btn']",
    "strategyUsed": "AttributeFallbackStrategy"
  }]
}
```

Each event = one locator to fix in your Page Object.

---

## Built-in Healing Strategies

| Priority | Strategy | Heals |
|----------|----------|-------|
| 10 | `AttributeFallbackStrategy` | ID/name renames, version bumps, camelCase changes |
| 20 | `XPathFallbackStrategy` | Button/link text stable, attributes changed |
| 30 | `CssFallbackStrategy` | CSS class renames, BEM drift, module hashing |
| 40 | `SiblingWalkStrategy` | Form fields where label is stable, input id changed |

### How stem generation works

The key insight: `submit-btn-old` and `submit-btn-new` share the stem `submit-btn`.

```
"submit-btn-old"  →  ["submit-btn-old", "submit-btn", "submit"]
"loginFormV2"     →  ["loginFormV2", "login", "login-form"]    ← camelCase split
"email-input-1"   →  ["email-input-1", "email-input"]          ← number strip
```

Each stem is probed against `data-testid`, `id`, `name`, `aria-label`, and 8 other attributes.

---

## Custom Strategies

```java
public class MyDatabaseStrategy implements IHealingStrategy {

    @Override
    public By heal(WebDriver driver, By broken) {
        // Look up last-known-working locators from your test database
        String lastWorking = db.getLastWorkingLocator(broken.toString());
        return lastWorking != null ? By.cssSelector(lastWorking) : null;
    }

    @Override
    public int getPriority() { return 50; } // runs after all built-ins
}

// Register:
AutoHealingDriver driver = AutoHealingDriver.builder(new ChromeDriver())
        .withStrategy(new MyDatabaseStrategy())
        .build();
```

Remove slow strategies when speed matters:

```java
AutoHealingDriver driver = AutoHealingDriver.builder(new ChromeDriver())
        .withoutStrategy(SiblingWalkStrategy.class)
        .build();
```

---

## Backtest Results

Verified against 4 real Selenium practice sites:

| Site | Scenario | Result |
|------|----------|--------|
| practicetestautomation.com | `id=submit` → `id=submit-btn` | `[id*='submit']` ✅ |
| the-internet.herokuapp.com | `.radius` → `.btn-radius` | `[class*='radius']` ✅ |
| demoqa.com | `id=login` → `id=loginButton` | `[id*='login']` ✅ |
| saucedemo.com | `id=login-button` → `id=login-btn` | `[data-testid='login-button']` ✅ |

**25/25 scenarios pass** across synthetic and real-site tests.

---

## How It Works Internally

```
driver.findElement(By.id("submit-btn-old"))
    │
    ├── Found?  YES → return immediately (zero overhead)
    │
    └── NoSuchElementException
            │
            ├── Set implicitlyWait = ZERO  (prevents per-probe timeout hang)
            │
            ├── AttributeFallbackStrategy.heal()
            │       stems: ["submit-btn-old", "submit-btn", "submit"]
            │       probe: [id*='submit-btn'] → FOUND ✅
            │
            └── Restore implicitlyWait (finally block)
                Return healed element → test continues
```

The implicit wait is always restored via a `finally` block, even if a strategy throws.

---

## Project Structure

```
src/main/java/io/github/autoheal/healing/
├── driver/
│   └── AutoHealingDriver.java       ← main entry point + Builder
├── strategy/
│   ├── IHealingStrategy.java        ← implement this for custom strategies
│   ├── AttributeFallbackStrategy.java
│   ├── XPathFallbackStrategy.java
│   ├── CssFallbackStrategy.java
│   └── SiblingWalkStrategy.java
├── report/
│   ├── HealingReport.java           ← JSON report writer
│   └── HealingEvent.java
└── listener/
    └── HealingReportListener.java   ← TestNG wiring helper
```

---

## Requirements

- Java 11+
- Selenium 4.x (provided — bring your own version)
- TestNG 7.x (optional — only needed for HealingReportListener)

---

## License

Apache License 2.0 — see [LICENSE](LICENSE)
