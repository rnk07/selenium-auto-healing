# selenium-auto-healing

> A pluggable auto-healing locator library for Selenium 4 + TestNG

[![Maven Central](https://img.shields.io/maven-central/v/io.github.rnk07/selenium-auto-healing)](https://central.sonatype.com/artifact/io.github.rnk07/selenium-auto-healing)
[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](https://www.apache.org/licenses/LICENSE-2.0)
[![Java](https://img.shields.io/badge/Java-11%2B-orange)](https://www.oracle.com/java/)
[![Selenium](https://img.shields.io/badge/Selenium-4.x-green)](https://www.selenium.dev/)
[![GitHub Sponsors](https://img.shields.io/github/sponsors/rnk07?style=flat&label=Sponsors&color=EA4AAA)](https://github.com/sponsors/rnk07)

---

## The Problem

```
NoSuchElementException: no such element: {"method":"css selector","selector":"button#submit-old"}
```

The button still exists on the page. A developer just renamed submit to submit-old in a sprint.
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

When a locator fails, the library first checks its historical database for an instant answer.
If not found, eight healing strategies run and find the element automatically.
The test passes. A JSON report tells you exactly which locators need to be updated.

---

## Add to Your Project

```xml
<dependency>
    <groupId>io.github.rnk07</groupId>
    <artifactId>selenium-auto-healing</artifactId>
    <version>2.0.3</version>
</dependency>
```

---

## How It Gets Smarter Every Run

No Docker. No PostgreSQL. No external server.
An embedded H2 database runs inside your JVM and stores every healed locator on disk.

```
Run 1 — locator breaks for the first time
   button#submit-old fails
   strategy chain runs (~2-5 seconds)
   healed to [id='submit']
   saved to target/healing-db/healing.mv.db

Run 2+ — same broken locator
   database lookup (~5 milliseconds)
   healed instantly, no DOM scanning needed
   INFO: Healed instantly from history: 'button#submit-old' -> '[id='submit']'
```

---

## Quick Start — Two Ways to Use

---

### Way 1 — Zero Configuration (recommended, v1.1.0+)

```java
import io.github.autoheal.healing.listener.AutoHealing;
import org.testng.annotations.Listeners;

@Listeners(AutoHealing.class)
public class Base {

    protected WebDriver driver;
    protected LoginPage loginPG;

    @BeforeMethod
    @Parameters("browser")
    public void setUp(@Optional("chrome") String browser) {
        if (browser.equalsIgnoreCase("chrome")) {
            driver = new ChromeDriver();
        } else if (browser.equalsIgnoreCase("firefox")) {
            driver = new FirefoxDriver();
        }
        driver.manage().timeouts().implicitlyWait(Duration.ofSeconds(5));
        driver.manage().window().maximize();
        loginPG = new LoginPage(driver);
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
- Injects the wrapped driver into Base, BasePage, and all Page Object fields
- Checks the historical database for instant healing on repeated runs
- Writes target/healing-report/*.json at the end of the suite
- Logs a per-test summary after every test

---

### Way 2 — Manual Configuration (v1.0.x)

```java
protected AutoHealingDriver driver;

HealingReportListener.register(driver.getHealingReport());
HealingReportListener.setCurrentTest(
        this.getClass().getSimpleName() + "." + method.getName());

@AfterSuite
public void writeHealingReport() {
    HealingReportListener.writeReport();
}
```

---

### BasePage — one important change

```java
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

```java
@Listeners(AutoHealing.class)
public class Base {
    @HealingDriverField
    protected WebDriver myCustomDriver;
}
```

---

## Per-Test Healing Summary (v1.7.0+)

After each test completes you see this immediately in the console:

```
[main] WARN  HealingReport - TEST SUMMARY: loginTest.loginMethodTest
  2 locator(s) healed:
  [1] broken='button#submit-old' healed='[id=submit]' via=AttributeFallbackStrategy
  [2] broken='input#password-old' healed='[id=password]' via=AttributeFallbackStrategy

[main] INFO  HealingReport - TEST SUMMARY: checkoutTest.placeOrder — no healing needed
```

---

## The Healing Report

After your full test run:
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
    }
  ]
}
```

---

## The Historical Database

```
target/healing-db/
    healing.mv.db     <- H2 embedded database, stores all healed locators
```

To clear all history:
```java
HealingDatabase.getInstance().clearHistory();
```

---

## Complete Strategy Chain

| Priority | Strategy | Heals |
|----------|----------|-------|
| 0 | HistoricalDatabase | Any previously healed locator — instant recall |
| 5 | StaleElementStrategy | Elements that became stale after React/Angular re-render |
| 10 | AttributeFallbackStrategy | ID/name renames, version bumps, camelCase, tag#id |
| 20 | XPathFallbackStrategy | Button/link text stable, attributes changed |
| 30 | CssFallbackStrategy | CSS class renames, BEM, Tailwind JIT, React CSS Modules |
| 40 | SiblingWalkStrategy | Form fields where label is stable, input id changed |
| 50 | ShadowDomStrategy | Elements inside Shadow DOM — Web Components, Lit, Stencil |
| 60 | IframeStrategy | Elements inside iframes — Stripe, PayPal, reCAPTCHA |
| 70 | DomSimilarityStrategy | Last resort — finds structurally similar elements |

---

## How Stem Generation Works

```
"button#submit-old"  ->  extracts "submit-old"  ->  stems: ["submit-old", "submit"]
"input#password-old" ->  extracts "password-old" ->  stems: ["password-old", "password"]
"input_abc123xyz"    ->  stems: ["input_abc123xyz", "input"]   (CSS module hash)
"loginFormV2"        ->  stems: ["loginFormV2", "login", "login-form"]  (camelCase)
```

---

## What Healing Can and Cannot Do

| Broken locator | Heals? | Reason |
|---|---|---|
| button#submit-old | YES | stem "submit" matches real id=submit |
| input#password-old | YES | stem "password" matches real id=password |
| input_abc123xyz | YES | hash stripped to "input" |
| button#fake-id (run 1) | YES | DomSimilarityStrategy finds by tag+structure |
| button#fake-id (run 2+) | YES instantly | saved in historical database |
| button#xyz123 | NO | completely random, no stem match |

---

## Custom Strategies

```java
public class MyStrategy implements IHealingStrategy {
    @Override
    public By heal(WebDriver driver, By broken) {
        return null; // return null to pass to next strategy
    }

    @Override
    public int getPriority() { return 80; }
}

// Register:
driver = AutoHealingDriver.builder(new ChromeDriver())
        .withStrategy(new MyStrategy())
        .withoutStrategy(SiblingWalkStrategy.class)
        .build();
```

---

## Backtest Results

| Site / Framework | Scenario | Result |
|---|---|---|
| practicetestautomation.com | button#submit -> button#submit-old | Healed |
| the-internet.herokuapp.com | .radius -> .btn-radius | Healed |
| demoqa.com | id=login -> id=loginButton | Healed |
| saucedemo.com | id=login-button -> id=login-btn | Healed |
| Real TestNG POM framework | button#submit-old in Page Object | Healed |
| CSS module hash | input_abc123xyz -> username | Healed |
| Completely random ID | button#fake-id | Healed via DomSimilarity |
| Truly random no signal | button#xyz123 | Fails gracefully |

---

## Project Structure

```
src/main/java/io/github/autoheal/healing/
+-- driver/
|   +-- AutoHealingDriver.java
+-- db/
|   +-- HealingDatabase.java
|   +-- LocatorRecord.java
+-- strategy/
|   +-- IHealingStrategy.java
|   +-- StaleElementStrategy.java       <- priority 5
|   +-- AttributeFallbackStrategy.java  <- priority 10
|   +-- XPathFallbackStrategy.java      <- priority 20
|   +-- CssFallbackStrategy.java        <- priority 30
|   +-- SiblingWalkStrategy.java        <- priority 40
|   +-- ShadowDomStrategy.java          <- priority 50
|   +-- IframeStrategy.java             <- priority 60
|   +-- DomSimilarityStrategy.java      <- priority 70
+-- report/
|   +-- HealingReport.java
|   +-- HealingEvent.java
+-- listener/
    +-- AutoHealing.java
    +-- HealingDriverField.java
    +-- HealingReportListener.java
```

---

## Changelog

### 2.0.3
- AutoHealing listener now auto-registers visual healing strategies
  if selenium-auto-healing-visual jar is on the classpath
- Uses reflection — zero compile-time dependency on paid library
- Pro users get visual healing with zero code changes in Base class
- Free users see no change — silently skips if visual jar absent

### 2.0.2
- Added addStrategy(IHealingStrategy) instance method to AutoHealingDriver
  Allows external modules to register strategies at runtime without rebuilding the driver
  Used by selenium-auto-healing-visual to register pHash and OCR strategies
- Added getStrategies() to return unmodifiable view of current strategy chain

### 2.0.1
- Fixed DomSimilarityStrategy tag extraction from CSS selectors
- Increased tag match score from 15 to 30 points
- Lowered minimum score threshold from 40 to 25

### 2.0.0
- Added DomSimilarityStrategy (priority 70) — last resort structural healing
- No screenshot needed, works fully headless, zero new dependencies

### 1.7.0
- Added per-test healing summary after every test method

### 1.6.0
- Added StaleElementStrategy for React/Angular DOM re-render healing

### 1.5.0
- Added IframeStrategy, improved CSS/XPath/BEM handling, reactid warning

### 1.4.0
- Fixed dynamic ID and CSS module hash suffix stripping

### 1.3.0
- Added ShadowDomStrategy for Web Component support

### 1.2.0
- Added historical H2 database for instant locator recall

### 1.1.0
- Added @Listeners(AutoHealing.class) zero-config listener

### 1.0.0
- Initial release — 4 strategies, JSON report, TestNG wiring

---

## Pro Visual Healing (v2.1.0+)

> **Available to Pro sponsors ($29/month) and Enterprise sponsors ($99/month)**
> via private Maven repository after joining [GitHub Sponsors](https://github.com/sponsors/rnk07)

Adds two additional healing strategies on top of the free library:

### VisualHealingStrategy (priority 65) — Perceptual Hash

Heals locators by comparing how elements look visually — no screenshot baseline needed.

On every successful `findElement()` the element's screenshot region is hashed and stored.
On failure, the page is scanned and the element that looks most similar is returned.

```
Run 1: button#submit found → screenshot cropped → pHash fingerprint stored
Dev renames to button#submit-v2
Run 2: button#submit fails
  → pHash scan finds button#submit-v2 (Hamming distance: 3/64)
  → healed ✓
```

### OcrHealingStrategy (priority 68) — Tesseract OCR

Heals locators by reading visible text from the page screenshot using Tesseract OCR.
Works even for elements rendered as images, canvas, or custom fonts.

```
button#submit-old fails
  → Tesseract reads screenshot → finds text "Sign In" at (400, 320)
  → XPath: //button[normalize-space(text())='Sign In']
  → healed ✓
```

### Pro tier setup

```xml
<!-- Add BOTH to your pom.xml -->

<!-- Free — Maven Central (always works) -->
<dependency>
    <groupId>io.github.rnk07</groupId>
    <artifactId>selenium-auto-healing</artifactId>
    <version>2.0.3</version>
</dependency>

<!-- Paid — GitHub Packages (Pro sponsors only) -->
<dependency>
    <groupId>io.github.rnk07</groupId>
    <artifactId>selenium-auto-healing-visual</artifactId>
    <version>2.1.0</version>
</dependency>
```

Add to `~/.m2/settings.xml`:
```xml
<server>
    <id>github</id>
    <username>YOUR_GITHUB_USERNAME</username>
    <password>YOUR_GITHUB_PERSONAL_ACCESS_TOKEN</password>
</server>
```

Add to your `pom.xml` repositories section:
```xml
<repository>
    <id>github</id>
    <url>https://maven.pkg.github.com/rnk07/selenium-auto-healing-visual</url>
</repository>
```

Enable visual healing in your Base class:
```java
@Listeners(AutoHealing.class)
public class Base {
    protected WebDriver driver;

    @BeforeMethod
    public void setUp() {
        driver = new ChromeDriver();
        // Visual healing registers automatically via @Listeners
    }
}
```

---

## Support This Project

selenium-auto-healing is completely free and open source.
If it saves you time, please consider sponsoring:

[![Sponsor on GitHub](https://img.shields.io/badge/Sponsor-%E2%9D%A4-EA4AAA?style=for-the-badge&logo=github)](https://github.com/sponsors/rnk07)

Your sponsorship funds visual healing features (v2.1.0 pHash, v2.2.0 Tesseract OCR),
ongoing maintenance, and new healing strategies.

**Pro sponsors ($29/month)** get access to visual healing via private Maven repository.
**Enterprise sponsors ($99/month)** get direct email support and integration help.

---

## Requirements

- Java 11+
- Selenium 4.x (provided)
- TestNG 7.x (provided)
- H2 2.x (bundled)

---

## License

Apache License 2.0 — see LICENSE
