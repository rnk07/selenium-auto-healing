# selenium-auto-healing

> A pluggable auto-healing locator library for Selenium 4 + TestNG

[![Maven Central](https://img.shields.io/maven-central/v/io.github.rnk07/selenium-auto-healing)](https://central.sonatype.com/artifact/io.github.rnk07/selenium-auto-healing)
[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](https://www.apache.org/licenses/LICENSE-2.0)
[![Java](https://img.shields.io/badge/Java-11%2B-orange)](https://www.oracle.com/java/)
[![Selenium](https://img.shields.io/badge/Selenium-4.x-green)](https://www.selenium.dev/)

---

## The Problem

```
NoSuchElementException: no such element: {"method":"css selector","selector":"button#submit-old"}
```

The button still exists on the page. A developer just renamed `submit` to `submit-old` in a sprint.
Your test fails. CI is red. You spend an hour tracking down the change.

---

## The Solution

```java
// ONE annotation on your Base class — everything works automatically
@Listeners(AutoHealing.class)
public class Base {
    protected WebDriver driver; // no other changes needed
}
```

When a locator fails, four healing strategies run automatically and find the element
using stable attributes, text content, CSS class matching, or DOM sibling walking.
The test passes. A JSON report tells you exactly which locators need to be updated.

---

## Add to Your Project

```xml
<dependency>
    <groupId>io.github.rnk07</groupId>
    <artifactId>selenium-auto-healing</artifactId>
    <version>1.1.0</version>
</dependency>
```

---

## Quick Start — Two Ways to Use

---

### Way 1 — Zero Configuration (recommended, v1.1.0+)

Mirrors the exact simplicity of TestNG's `IRetryAnalyzer`.
Just add one annotation to your Base class — nothing else changes.

```java
import io.github.autoheal.healing.listener.AutoHealing;
import org.testng.annotations.Listeners;

@Listeners(AutoHealing.class)          // only change needed
public class Base {

    protected WebDriver driver;        // stays as plain WebDriver

    @BeforeMethod
    @Parameters("browser")
    public void setUp(@Optional("chrome") String browser) {
        if (browser.equalsIgnoreCase("chrome")) {
            driver = new ChromeDriver(); // normal driver — AutoHealing wraps it
        } else if (browser.equalsIgnoreCase("firefox")) {
            driver = new FirefoxDriver();
        }
        driver.manage().timeouts().implicitlyWait(Duration.ofSeconds(5));
        driver.manage().window().maximize();
    }

    @AfterMethod
    public void tearDown() {
        if (driver != null) driver.quit();
    }
    // No @AfterSuite needed — AutoHealing writes the report automatically
}
```

AutoHealing automatically:
- Wraps your driver with AutoHealingDriver before each test via reflection
- Injects it into all classes in your hierarchy (Base, BasePage, etc.)
- Sets the current test name for report attribution
- Writes target/healing-report/*.json at the end of the suite

---

### Way 2 — Manual Configuration (v1.0.x)

Use this if you want full control over the driver and report.

```java
import io.github.autoheal.healing.driver.AutoHealingDriver;
import io.github.autoheal.healing.listener.HealingReportListener;

public class Base {

    protected AutoHealingDriver driver;

    @BeforeMethod
    @Parameters("browser")
    public void setUp(@Optional("chrome") String browser, java.lang.reflect.Method method) {
        if (browser.equalsIgnoreCase("chrome")) {
            driver = AutoHealingDriver.builder(new ChromeDriver()).build();
        } else if (browser.equalsIgnoreCase("firefox")) {
            driver = AutoHealingDriver.builder(new FirefoxDriver()).build();
        }
        driver.manage().timeouts().implicitlyWait(Duration.ofSeconds(5));
        driver.manage().window().maximize();

        HealingReportListener.register(driver.getHealingReport());
        HealingReportListener.setCurrentTest(
                this.getClass().getSimpleName() + "." + method.getName());
    }

    @AfterMethod
    public void tearDown() {
        if (driver != null) driver.quit();
        HealingReportListener.setCurrentTest(null);
    }

    @AfterSuite
    public void writeHealingReport() {
        HealingReportListener.writeReport();
    }
}
```

---

### BasePage — one important change for both ways

Replace visibilityOfElementLocated(By) with visibilityOf(WebElement) so
findElement routes through AutoHealingDriver and healing triggers correctly:

```java
// WRONG — bypasses AutoHealingDriver, healing never triggers
protected WebElement waitForVisibility(By locator) {
    return wait.until(ExpectedConditions.visibilityOfElementLocated(locator));
}

// CORRECT — findElement goes through AutoHealingDriver first
protected WebElement waitForVisibility(By locator) {
    WebElement element = driver.findElement(locator); // healing triggers HERE
    return wait.until(ExpectedConditions.visibilityOf(element));
}

protected WebElement waitForClickable(By locator) {
    WebElement element = driver.findElement(locator); // healing triggers HERE
    return wait.until(ExpectedConditions.elementToBeClickable(element));
}
```

---

### Page Objects — zero changes needed

```java
public class LoginPage extends BasePage {
    private By submitBtn = By.cssSelector("button#submit");

    public void clickSubmit() {
        click(submitBtn); // healed transparently if locator breaks
    }
}
```

---

## Custom Driver Field Name

If your driver field is NOT named driver, add @HealingDriverField:

```java
import io.github.autoheal.healing.listener.HealingDriverField;

@Listeners(AutoHealing.class)
public class Base {

    @HealingDriverField
    protected WebDriver myCustomDriver;
}
```

---

## The Healing Report

After your test run, check:
```
target/healing-report/healing-report-20260315-011326.json
```

```json
{
  "totalHealed": 2,
  "generatedAt": "2026-03-15 01:13:26",
  "events": [
    {
      "testName": "loginTest.loginMethodTest",
      "brokenLocator": "By.cssSelector: button#submit-old",
      "healedLocator": "[id*='submit']",
      "strategyUsed": "AttributeFallbackStrategy"
    },
    {
      "testName": "loginTest.loginMethodTest",
      "brokenLocator": "By.cssSelector: input#password-old",
      "healedLocator": "[id*='password']",
      "strategyUsed": "AttributeFallbackStrategy"
    }
  ]
}
```

Each event = one locator to fix in your Page Object.

---

## Built-in Healing Strategies

| Priority | Strategy | Heals |
|----------|----------|-------|
| 10 | AttributeFallbackStrategy | ID/name renames, version bumps, camelCase, tag#id CSS selectors |
| 20 | XPathFallbackStrategy | Button/link text stable, attributes changed |
| 30 | CssFallbackStrategy | CSS class renames, BEM drift, module hashing |
| 40 | SiblingWalkStrategy | Form fields where label is stable, input id changed |

### How stem generation works

```
"button#submit-old"  ->  extracts "submit-old"  ->  stems: ["submit-old", "submit"]
"input#password-old" ->  extracts "password-old" ->  stems: ["password-old", "password"]
"loginFormV2"        ->  stems: ["loginFormV2", "login", "login-form"]  (camelCase)
"email-input-1"      ->  stems: ["email-input-1", "email-input"]        (number strip)
```

### What healing can and cannot do

| Broken locator | Heals? | Reason |
|---|---|---|
| button#submit-old | YES | stem "submit" matches real id=submit |
| input#password-old | YES | stem "password" matches real id=password |
| input#username-v2 | YES | stem "username" matches real id=username |
| input#passwordxyzabc | NO | "xyzabc" has no connection to real element |
| button#xyz123 | NO | completely random, no stem match possible |

---

## Custom Strategies

```java
public class MyDatabaseStrategy implements IHealingStrategy {

    @Override
    public By heal(WebDriver driver, By broken) {
        // Look up last-known-working locators from your test database
        // Return null to pass to the next strategy in the chain
        return null;
    }

    @Override
    public int getPriority() { return 50; }
}
```

When using Way 2 (manual), register via the builder:
```java
driver = AutoHealingDriver.builder(new ChromeDriver())
        .withStrategy(new MyDatabaseStrategy())
        .withoutStrategy(SiblingWalkStrategy.class)
        .build();
```

---

## How It Works Internally

```
@Listeners(AutoHealing.class) on Base
        |
        v
Before each test
        |
        +-- Find "driver" field via reflection in class hierarchy
        +-- Wrap: driver = AutoHealingDriver.builder(driver).build()
        +-- Inject into Base AND BasePage (all classes in hierarchy)

During test:
driver.findElement(By.cssSelector("button#submit-old"))
        |
        +-- Found?  YES -> return immediately (zero overhead)
        |
        +-- NoSuchElementException
                +-- Set implicitlyWait = ZERO
                +-- AttributeFallbackStrategy -> [id*='submit'] -> FOUND
                +-- Restore implicitlyWait (finally block)

After suite
        +-- Write target/healing-report/*.json
```

---

## Project Structure

```
src/main/java/io/github/autoheal/healing/
+-- driver/
|   +-- AutoHealingDriver.java          <- wraps WebDriver with healing
+-- strategy/
|   +-- IHealingStrategy.java           <- implement for custom strategies
|   +-- AttributeFallbackStrategy.java  <- priority 10
|   +-- XPathFallbackStrategy.java      <- priority 20
|   +-- CssFallbackStrategy.java        <- priority 30
|   +-- SiblingWalkStrategy.java        <- priority 40
+-- report/
|   +-- HealingReport.java              <- JSON report writer
|   +-- HealingEvent.java
+-- listener/
    +-- AutoHealing.java                <- @Listeners(AutoHealing.class) NEW in 1.1.0
    +-- HealingDriverField.java         <- @HealingDriverField annotation NEW in 1.1.0
    +-- HealingReportListener.java      <- manual wiring helper (Way 2)
```

---

## Backtest Results

Verified against 4 real Selenium practice sites and a real automation framework:

| Site / Framework | Scenario | Result |
|---|---|---|
| practicetestautomation.com | button#submit -> button#submit-old | Healed |
| the-internet.herokuapp.com | .radius -> .btn-radius | Healed |
| demoqa.com | id=login -> id=loginButton | Healed |
| saucedemo.com | id=login-button -> id=login-btn | Healed |
| Real TestNG framework | button#submit-old in Page Object | Healed |
| Any locator | Completely random ID | Fails gracefully |

---

## Changelog

### 1.1.0
- Added AutoHealing — zero-configuration TestNG listener
  (@Listeners(AutoHealing.class) is all you need)
- Added @HealingDriverField annotation for custom driver field names
- AutoHealing finds and wraps driver via reflection, injects into full
  class hierarchy, and writes report at suite end automatically
- Added withReport() to AutoHealingDriver.Builder for shared suite reports
- TestNG moved from test scope to provided scope

### 1.0.1
- Fixed extractCoreValue to handle tag#id CSS selector patterns
  (button#submit-old now correctly extracts submit-old)
- Fixed tag.class CSS selector patterns
- Added 3 new unit tests

### 1.0.0
- Initial release
- Four built-in healing strategies: Attribute, XPath, CSS, SiblingWalk
- Stem-based locator matching with camelCase and version suffix support
- JSON healing report written to target/healing-report/
- TestNG integration via HealingReportListener

---

## Requirements

- Java 11+
- Selenium 4.x (provided)
- TestNG 7.x (provided)

---

## License

Apache License 2.0 — see LICENSE
