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
package io.github.autoheal.healing.db;

/**
 * Represents one healed locator record stored in the local H2 database.
 *
 * <p>Every time a locator is successfully healed, a record is saved so that
 * on the next run the healed locator is retrieved instantly from the database
 * without running the full strategy chain again.
 */
public class LocatorRecord {

    private final String brokenLocator;
    private final String healedLocator;
    private final String pageUrl;
    private final String strategyUsed;
    private final String healedAt;

    public LocatorRecord(String brokenLocator, String healedLocator,
                         String pageUrl, String strategyUsed, String healedAt) {
        this.brokenLocator = brokenLocator;
        this.healedLocator = healedLocator;
        this.pageUrl       = pageUrl;
        this.strategyUsed  = strategyUsed;
        this.healedAt      = healedAt;
    }

    public String getBrokenLocator() { return brokenLocator; }
    public String getHealedLocator() { return healedLocator; }
    public String getPageUrl()       { return pageUrl; }
    public String getStrategyUsed()  { return strategyUsed; }
    public String getHealedAt()      { return healedAt; }

    @Override
    public String toString() {
        return String.format("LocatorRecord{broken='%s', healed='%s', page='%s'}",
                brokenLocator, healedLocator, pageUrl);
    }
}
