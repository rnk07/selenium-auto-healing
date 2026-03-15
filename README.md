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

The button still exists on the page. A developer just renamed `submit` → `submit-old` in a sprint.
Your test fails. CI is red. You spend an hour tracking down the change.

---

## The Solution

```java
// ONE line change in your BaseTest — everything else stays the same
AutoHealingDriver driver = AutoHealingDriver.builder(new ChromeDriver()).build();

// Your existing Page Objects work unchanged
driver.findElement(By.cssSelector("button#submit-old")).click(); // healed automatically
```

When a locator fails, four healing strategies run in priority order and find the element
using stable attributes, text content, CSS class matching, or DOM sibling walking.
The test passes. A JSON report tells you exactly which locators need to be updated.

---

## Add to Your Project

```xml
<dependency>
    <groupId>io.github.rnk07</groupId>
    <artifactId>selenium-auto-healing</artifactId>
    <version>1.0.1</version>
</dependency>
```

---

## Quick Start

### 1. Change one line in BaseTest

```java
import io.github.autoheal.healing.driver.AutoHealingDriver;
import io.github.autoheal.healing.listener.HealingReportListener;

public class BaseTest {

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

### 2. Update BasePage to route through AutoHealingDriver

The key change is using `visibilityOf(WebElement)` instead of
`visibilityOfElementLocated(By)` — this ensures `findElement` goes through
`AutoHealingDriver` so healing triggers correctly:

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

### 3. Your Page Objects — zero changes needed

```java
public class LoginPage extends BasePage {
    private By submitBtn = By.cssSelector("button#submit");

    public void clickSubmit() {
        click(submitBtn); // healed transparently if locator breaks
    }
}
```

### 4. Check the healing report after the run

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
| 10 | `AttributeFallbackStrategy` | ID/name renames, version bumps, camelCase changes, tag#id CSS selectors |
| 20 | `XPathFallbackStrategy` | Button/link text stable, attributes changed |
| 30 | `CssFallbackStrategy` | CSS class renames, BEM drift, module hashing |
| 40 | `SiblingWalkStrategy` | Form fields where label is stable, input id changed |

### How stem generation works

The key insight: `submit-old` and `submit-new` share the stem `submit`.

```
"button#submit-old"  →  extracts "submit-old"  →  stems: ["submit-old", "submit"]
"input#password-old" →  extracts "password-old" →  stems: ["password-old", "password"]
"loginFormV2"        →  stems: ["loginFormV2", "login", "login-form"]  (camelCase)
"email-input-1"      →  stems: ["email-input-1", "email-input"]        (number strip)
```

Each stem is probed against `data-testid`, `id`, `name`, `aria-label`, and 8 other attributes.

### What healing can and cannot do

| Broken locator | Heals? | Reason |
|---|---|---|
| `button#submit-old` | ✅ Yes | stem `submit` matches real `id=submit` |
| `input#password-old` | ✅ Yes | stem `password` matches real `id=password` |
| `input#username-v2` | ✅ Yes | stem `username` matches real `id=username` |
| `input#passwordxyzabc` | ❌ No | `xyzabc` has no connection to real element |
| `button#xyz123` | ❌ No | completely random — no stem match possible |

Healing fixes **realistic renames** that happen in real projects — version bumps,
suffix changes, abbreviation expansions. It cannot guess completely random IDs.

---

## How It Works Internally

```
driver.findElement(By.cssSelector("button#submit-old"))
    │
    ├── Found?  YES → return immediately (zero overhead on stable locators)
    │
    └── NoSuchElementException
            │
            ├── Set implicitlyWait = ZERO  (prevents per-probe timeout hang)
            │
            ├── AttributeFallbackStrategy.heal()
            │       extract: "button#submit-old" → "submit-old"
            │       stems:   ["submit-old", "submit"]
            │       probe:   [id*='submit'] → FOUND ✅
            │
            └── Restore implicitlyWait (finally block — always runs)
                Return healed element → test continues
```

The implicit wait is always restored via a `finally` block, even if a strategy throws.

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
    public int getPriority() { return 50; } // runs after all built-ins
}

// Register:
AutoHealingDriver driver = AutoHealingDriver.builder(new ChromeDriver())
        .withStrategy(new MyDatabaseStrategy())
        .build();
```

Remove slow strategies for speed-sensitive runs:

```java
AutoHealingDriver driver = AutoHealingDriver.builder(new ChromeDriver())
        .withoutStrategy(SiblingWalkStrategy.class)
        .build();
```

---

## Backtest Results

Verified against 4 real Selenium practice sites and a real automation framework:

| Site / Framework | Scenario | Result |
|---|---|---|
| practicetestautomation.com | `button#submit` → `button#submit-old` | ✅ Healed |
| the-internet.herokuapp.com | `.radius` → `.btn-radius` | ✅ Healed |
| demoqa.com | `id=login` → `id=loginButton` | ✅ Healed |
| saucedemo.com | `id=login-button` → `id=login-btn` | ✅ Healed |
| Real TestNG framework | `button#submit-old` in POM | ✅ Healed |
| Any locator | Completely random ID | ❌ Fails gracefully |

---

## Project Structure

```
src/main/java/io/github/autoheal/healing/
├── driver/
│   └── AutoHealingDriver.java         ← main entry point + Builder
├── strategy/
│   ├── IHealingStrategy.java          ← implement for custom strategies
│   ├── AttributeFallbackStrategy.java ← priority 10
│   ├── XPathFallbackStrategy.java     ← priority 20
│   ├── CssFallbackStrategy.java       ← priority 30
│   └── SiblingWalkStrategy.java       ← priority 40
├── report/
│   ├── HealingReport.java             ← JSON report writer
│   └── HealingEvent.java
└── listener/
    └── HealingReportListener.java     ← TestNG wiring helper
```

---

## Changelog

### 1.0.1
- Fixed `extractCoreValue` to correctly handle `tag#id` CSS selector patterns
  (e.g. `button#submit-old` now correctly extracts `submit-old` instead of `button#submit-old`)
- Fixed `tag.class` CSS selector patterns (e.g. `button.submit-btn` → `submit-btn`)
- Added 3 new unit tests covering these patterns

### 1.0.0
- Initial release
- Four built-in healing strategies: Attribute, XPath, CSS, SiblingWalk
- Stem-based locator matching with camelCase and version suffix support
- JSON healing report written to `target/healing-report/`
- TestNG integration via `HealingReportListener`

---

## Requirements

- Java 11+
- Selenium 4.x (provided — bring your own version)
- TestNG 7.x (optional — only needed for `HealingReportListener`)

---

## License

Apache License 2.0 — see [LICENSE](LICENSE)
