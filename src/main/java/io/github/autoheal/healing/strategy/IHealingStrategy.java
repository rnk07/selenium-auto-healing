/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.github.autoheal.healing.strategy;

import org.openqa.selenium.By;
import org.openqa.selenium.WebDriver;

/**
 * IHealingStrategy — the core contract for auto-healing locators.
 *
 * <p>When {@code driver.findElement(By)} throws {@link org.openqa.selenium.NoSuchElementException},
 * {@link io.github.autoheal.healing.driver.AutoHealingDriver} iterates over registered
 * strategies in priority order. The first strategy returning a non-null {@link By} wins —
 * the element is located and the test continues transparently.
 *
 * <p><b>Implementing a custom strategy:</b>
 * <pre>{@code
 * public class MyStrategy implements IHealingStrategy {
 *     public By heal(WebDriver driver, By broken) {
 *         // inspect live DOM, return a working By or null to defer
 *         return null;
 *     }
 *     public int getPriority() { return 50; }
 * }
 *
 * AutoHealingDriver driver = AutoHealingDriver.builder(new ChromeDriver())
 *         .withStrategy(new MyStrategy())
 *         .build();
 * }</pre>
 */
public interface IHealingStrategy {

    /**
     * Attempt to heal a broken locator.
     *
     * @param driver  live {@link WebDriver} — DOM is accessible
     * @param broken  the {@link By} that caused {@code NoSuchElementException}
     * @return a healed {@link By}, or {@code null} to defer to the next strategy
     */
    By heal(WebDriver driver, By broken);

    /**
     * Human-readable name used in healing reports and logs.
     *
     * @return strategy name, defaults to simple class name
     */
    default String getName() {
        return this.getClass().getSimpleName();
    }

    /**
     * Execution priority — lower number runs first.
     * Built-in strategies use 10, 20, 30, 40.
     * Custom strategies should use 50+ to run after built-ins.
     *
     * @return priority (default: 100)
     */
    default int getPriority() {
        return 100;
    }
}
