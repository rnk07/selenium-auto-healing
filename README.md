# selenium-auto-healing

> A pluggable auto-healing locator library for Selenium 4 + TestNG.
> Zero configuration. Zero infrastructure. Drop in one annotation and broken locators heal themselves.

[![Maven Central](https://img.shields.io/maven-central/v/io.github.rnk07/selenium-auto-healing)](https://central.sonatype.com/artifact/io.github.rnk07/selenium-auto-healing)
[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](https://www.apache.org/licenses/LICENSE-2.0)
[![Java](https://img.shields.io/badge/Java-11%2B-orange)](https://www.oracle.com/java/)
[![Selenium](https://img.shields.io/badge/Selenium-4.x-green)](https://www.selenium.dev/)

---

## The Problem

```
NoSuchElementException: no such element: {"method":"css selector","selector":"button#submit-old"}
```

The button still exists. A developer renamed it in a sprint. Your test fails. CI is red.

---

## The Solution

```java
// ONE annotation on your Base class — nothing else changes
@Listeners(AutoHealing.class)
public class Base {
    protected WebDriver driver;
}
```

When a locator fails, the library runs a chain of healing strategies to find the element automatically. The test passes. A JSON report tells you exactly which locators to fix.

---

## Add to Your Project

```xml
<dependency>
    <groupId>io.github.rnk07</groupId>
    <artifactId>selenium-auto-healing</artifactId>
    <version>2.0.9</version>
</dependency>
```

Required dependencies (add if not already present):

```xml
<dependency>
    <groupId>com.h2database</groupId>
    <artifactId>h2</artifactId>
    <version>2.2.224</version>
</dependency>
<dependency>
    <groupId>org.apache.logging.log4j</groupId>
    <artifactId>log4j-slf4j2-impl</artifactId>
    <version>2.20.0</version>
    <scope>test</scope>
</dependency>
```

---

## Quick Start

### Step 1 — Annotate your Base class

```java
import io.github.autoheal.healing.listener.AutoHealing;
import org.testng.annotations.Listeners;

@Listeners(AutoHealing.class)
public class Base {

    protected WebDriver driver;

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
    }

    @AfterMethod
    public void tearDown() {
        if (driver != null) driver.quit();
    }
}
```

### Step 2 — Write BasePage normally

```java
public class BasePage {
    protected WebDriver driver;

    public BasePage(WebDriver driver) { this.driver = driver; }

    protected WebElement waitForVisibility(By locator) {
        WebElement element = driver.findElement(locator); // healing triggers HERE
        return new WebDriverWait(driver, Duration.ofSeconds(10))
                .until(ExpectedConditions.visibilityOf(element));
    }

    protected WebElement waitForClickable(By locator) {
        WebElement element = driver.findElement(locator); // healing triggers HERE
        return new WebDriverWait(driver, Duration.ofSeconds(10))
                .until(ExpectedConditions.elementToBeClickable(element));
    }

    protected void click(By locator) { waitForClickable(locator).click(); }

    protected void type(By locator, String text) {
        WebElement el = waitForVisibility(locator);
        el.clear();
        el.sendKeys(text);
    }
}
```

### Step 3 — Write Page Objects normally

```java
public class LoginPage extends BasePage {
    private By userNameInput = By.cssSelector("input#username");
    private By passwordInput = By.cssSelector("input#password");
    private By submitBtn     = By.cssSelector("button#submit");

    public LoginPage(WebDriver driver) { super(driver); }

    public void login(String username, String password) {
        type(userNameInput, username);
        type(passwordInput, password);
        click(submitBtn);
    }
}
```

---

## LLM Healing — Groq API (Optional but Powerful)

The library includes `LlmHealingStrategy` which uses Groq's free LLM API to heal locators that no DOM-based strategy can fix.

**How it works:** When all other strategies fail, the strategy captures a compact snapshot of the page's interactive elements and asks Groq's `llama-3.1-8b-instant` model which element matches the broken locator. The LLM uses context from the broken locator name and the page DOM to identify the correct element.

```
All DOM strategies fail for '[data-username='abc123xyz']'
→ LlmHealingStrategy sends to Groq:
    Broken locator: [data-username='abc123xyz']
    Page elements:
      input | id=username | name=username | type=text | placeholder=Student
      input | id=password | name=password | type=password
      button | id=submit | type=submit | text=Submit
→ Groq returns: input[id='username']
→ verified visible → healed ✅
```

### Setup

**Step 1** — Get a free Groq API key at https://console.groq.com — no credit card required, 500k tokens/day free.

**Step 2** — Pass the key when running tests.

**Eclipse:** Right-click test → Run As → Run Configurations → Arguments tab → VM arguments:
```
-DGROQ_API_KEY=gsk_your_key_here
```

**Maven CLI:**
```bash
mvn test -DGROQ_API_KEY=gsk_your_key_here
```

**Windows system env var (permanent):**
```
Win+R → sysdm.cpl → Advanced → Environment Variables
→ New: GROQ_API_KEY = gsk_your_key_here
→ Restart Eclipse
```

If no key is configured, `LlmHealingStrategy` silently skips and the next strategy runs — no errors.

---

## Complete Strategy Chain

| Priority | Strategy | What it heals |
|----------|----------|---------------|
| 0 | HistoricalDatabase | Any previously healed locator — instant recall in ~5ms |
| 5 | StaleElementStrategy | Elements stale after React/Angular re-render |
| 10 | AttributeFallbackStrategy | Renamed IDs, version suffixes, camelCase splits |
| 20 | XPathFallbackStrategy | Text content stable, attributes changed |
| 30 | CssFallbackStrategy | CSS class renames, BEM, Tailwind JIT, React CSS Modules |
| 40 | SiblingWalkStrategy | Form fields where label is stable, input id changed |
| 50 | ShadowDomStrategy | Elements inside Shadow DOM — Web Components, Lit, Stencil |
| 60 | IframeStrategy | Elements inside iframes — Stripe, PayPal, reCAPTCHA |
| 64 | LlmHealingStrategy | Groq LLM — sends DOM snapshot, LLM returns correct selector |
| 70 | DomSimilarityStrategy | Last resort structural similarity |

---

## How It Gets Smarter Every Run

An embedded H2 database stores every healed locator. No Docker, no PostgreSQL, no external server.

```
Run 1 — locator breaks for the first time
   LlmHealingStrategy asks Groq → heals correctly (~300ms)
   saved to target/healing-db/

Run 2+ — same broken locator
   HistoricalDatabase instant recall in ~5ms
   no Groq API call needed
```

---

## Per-Test Console Summary

```
WARN  HealingReport - TEST SUMMARY: loginTest.loginMethodTest
  2 locator(s) healed:
  [1] broken='[data-username='abc123xyz']' healed='input[id='username']' via=LlmHealingStrategy
  [2] broken='[data-submit='abc123xyz']'   healed='button[id='submit']'  via=LlmHealingStrategy

INFO  HealingReport - TEST SUMMARY: checkoutTest.placeOrder — no healing needed
```

---

## Healing Report

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
      "brokenLocator": "By.cssSelector: [data-username='abc123xyz']",
      "healedLocator": "input[id='username']",
      "strategyUsed": "LlmHealingStrategy"
    }
  ]
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

## Custom Strategies

```java
public class MyStrategy implements IHealingStrategy {
    @Override
    public By heal(WebDriver driver, By broken) {
        return null; // null = pass to next strategy
    }
    @Override public String getName()     { return "MyStrategy"; }
    @Override public int    getPriority() { return 80; }
}

AutoHealingDriver driver = AutoHealingDriver.builder(new ChromeDriver())
        .withStrategy(new MyStrategy())
        .withoutStrategy(LlmHealingStrategy.class)
        .build();
```

---

## Historical Database

```
target/healing-db/
    healing.mv.db   ← all healed locators, instant recall on repeat runs
```

To clear all history, delete the `target/healing-db/` folder.

---

## Requirements

- Java 11+
- Selenium 4.x
- TestNG 7.x
- H2 2.x
- Groq API key (optional — free at console.groq.com)

---

## Changelog

### 2.0.9
- Removed DomFingerprintStrategy — replaced entirely by LlmHealingStrategy
- LlmHealingStrategy (priority 64) uses Groq llama-3.1-8b-instant — free, no credit card
- Sends compact DOM snapshot to Groq — LLM infers the correct element from context
- Gracefully skips if GROQ_API_KEY not set — next strategy runs instead
- No additional dependencies — pure Java HTTP POST, no SDK needed

### 2.0.8
- Added LlmHealingStrategy (priority 64) — Groq LLM healing
- Added DomFingerprintStrategy (priority 62) — multi-attribute DOM fingerprint healing

### 2.0.7
- Added DomFingerprintStrategy (priority 62) — industry-standard weighted attribute scoring

### 2.0.6
- Added CURRENT_USED_LOCATORS static ThreadLocal for cross-strategy coordination

### 2.0.5
- Added usedLocators Set tracking per test run

### 2.0.4
- AutoHealingDriver.findElement() records fingerprints on every successful find

### 2.0.3
- AutoHealing listener auto-registers additional strategies from classpath

### 2.0.2
- Added addStrategy() instance method and getStrategies() view

### 2.0.1
- Fixed DomSimilarityStrategy scoring and tag extraction

### 2.0.0
- Added DomSimilarityStrategy — last-resort structural healing

### 1.7.0
- Per-test healing summary in console

### 1.6.0
- StaleElementStrategy for React/Angular DOM re-render

### 1.5.0
- IframeStrategy, improved CSS/XPath/BEM handling

### 1.4.0
- Dynamic ID and CSS module hash suffix stripping

### 1.3.0
- ShadowDomStrategy for Web Components

### 1.2.0
- Historical H2 database for instant locator recall

### 1.1.0
- @Listeners(AutoHealing.class) zero-config listener

### 1.0.0
- Initial release

---

## License

Apache License 2.0
