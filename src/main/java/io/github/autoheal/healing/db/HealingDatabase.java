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

import org.openqa.selenium.By;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * HealingDatabase — a lightweight embedded H2 database that stores
 * previously healed locators for instant recall on subsequent runs.
 *
 * <p><b>How it works:</b>
 * <ul>
 *   <li>Run 1: locator fails → strategy chain runs → healed locator saved to DB</li>
 *   <li>Run 2+: locator fails → DB lookup finds saved healed locator → instant heal,
 *       no strategy chain needed (~5ms vs ~2-5 seconds)</li>
 * </ul>
 *
 * <p><b>Database location:</b>
 * {@code target/healing-db/healing} — stored alongside your test reports,
 * committed to version control so the team shares the history.
 *
 * <p><b>No Docker, no PostgreSQL, no external server.</b>
 * H2 is an embedded Java database — it runs inside your JVM with zero setup.
 *
 * <p>This class is thread-safe and uses a single shared connection per JVM.
 */
public class HealingDatabase {

    private static final Logger LOG = LoggerFactory.getLogger(HealingDatabase.class);

    private static final String DB_DIR  = "target/healing-db";
    private static final String DB_PATH = DB_DIR + "/healing";
    private static final String JDBC_URL =
            "jdbc:h2:file:./" + DB_PATH + ";AUTO_SERVER=TRUE;DB_CLOSE_ON_EXIT=TRUE";

    private static HealingDatabase instance;
    private Connection connection;

    private HealingDatabase() {
        try {
            new File(DB_DIR).mkdirs();
            Class.forName("org.h2.Driver");
            connection = DriverManager.getConnection(JDBC_URL, "sa", "");
            createTableIfNotExists();
            LOG.debug("[HealingDatabase] Connected to embedded H2 at {}", DB_PATH);
        } catch (Exception e) {
            LOG.warn("[HealingDatabase] Could not initialise DB — historical healing disabled: {}",
                    e.getMessage());
        }
    }

    /**
     * Returns the singleton instance of the database.
     * Safe to call from multiple threads.
     *
     * @return the shared {@link HealingDatabase} instance
     */
    public static synchronized HealingDatabase getInstance() {
        if (instance == null) {
            instance = new HealingDatabase();
        }
        return instance;
    }

    // =========================================================================
    // Public API
    // =========================================================================

    /**
     * Look up a previously healed locator for the given broken locator and page URL.
     *
     * <p>This is called BEFORE the strategy chain runs. If a match is found,
     * healing completes instantly without probing the DOM.
     *
     * @param broken  the broken {@link By} locator
     * @param pageUrl the current page URL (for page-scoped matching)
     * @return the previously healed {@link By}, or {@code null} if not found
     */
    public By lookup(By broken, String pageUrl) {
        if (connection == null) return null;
        try {
            String sql = "SELECT healed_locator FROM healing_history " +
                         "WHERE broken_locator = ? AND page_url = ? " +
                         "ORDER BY healed_at DESC LIMIT 1";
            try (PreparedStatement ps = connection.prepareStatement(sql)) {
                ps.setString(1, broken.toString());
                ps.setString(2, normaliseUrl(pageUrl));
                ResultSet rs = ps.executeQuery();
                if (rs.next()) {
                    String healed = rs.getString("healed_locator");
                    LOG.info("[HealingDatabase] Instant recall: '{}' -> '{}'", broken, healed);
                    return parseBy(healed);
                }
            }
        } catch (Exception e) {
            LOG.debug("[HealingDatabase] Lookup failed: {}", e.getMessage());
        }
        return null;
    }

    /**
     * Save a successfully healed locator to the database for future runs.
     *
     * <p>Uses INSERT OR REPLACE so re-running with a new healed locator
     * always stores the most recent result.
     *
     * @param broken      the broken locator
     * @param healed      the healed locator
     * @param pageUrl     the page where healing occurred
     * @param strategyUsed the strategy that performed the heal
     */
    public void save(By broken, By healed, String pageUrl, String strategyUsed) {
        if (connection == null) return;
        try {
            String sql = "MERGE INTO healing_history " +
                         "(broken_locator, healed_locator, page_url, strategy_used, healed_at) " +
                         "KEY (broken_locator, page_url) " +
                         "VALUES (?, ?, ?, ?, ?)";
            try (PreparedStatement ps = connection.prepareStatement(sql)) {
                ps.setString(1, broken.toString());
                ps.setString(2, healed.toString());
                ps.setString(3, normaliseUrl(pageUrl));
                ps.setString(4, strategyUsed);
                ps.setString(5, LocalDateTime.now()
                        .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));
                ps.executeUpdate();
                LOG.debug("[HealingDatabase] Saved: '{}' -> '{}'", broken, healed);
            }
        } catch (Exception e) {
            LOG.warn("[HealingDatabase] Could not save healed locator: {}", e.getMessage());
        }
    }

    /**
     * Returns total number of healed locators stored in the database.
     * Useful for reporting.
     *
     * @return total record count
     */
    public int getTotalRecords() {
        if (connection == null) return 0;
        try (Statement st = connection.createStatement();
             ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM healing_history")) {
            if (rs.next()) return rs.getInt(1);
        } catch (Exception e) {
            LOG.debug("[HealingDatabase] Count failed: {}", e.getMessage());
        }
        return 0;
    }

    /**
     * Clears all stored locator history.
     * Useful when you want to start fresh after a major UI redesign.
     */
    public void clearHistory() {
        if (connection == null) return;
        try (Statement st = connection.createStatement()) {
            st.execute("DELETE FROM healing_history");
            LOG.info("[HealingDatabase] Healing history cleared.");
        } catch (Exception e) {
            LOG.warn("[HealingDatabase] Could not clear history: {}", e.getMessage());
        }
    }

    // =========================================================================
    // Private helpers
    // =========================================================================

    private void createTableIfNotExists() throws SQLException {
        String sql = "CREATE TABLE IF NOT EXISTS healing_history (" +
                     "id              BIGINT AUTO_INCREMENT PRIMARY KEY, " +
                     "broken_locator  VARCHAR(512) NOT NULL, " +
                     "healed_locator  VARCHAR(512) NOT NULL, " +
                     "page_url        VARCHAR(512) NOT NULL, " +
                     "strategy_used   VARCHAR(128), " +
                     "healed_at       VARCHAR(32), " +
                     "UNIQUE (broken_locator, page_url)" +
                     ")";
        try (Statement st = connection.createStatement()) {
            st.execute(sql);
        }
    }

    /**
     * Parses a stored By.toString() representation back into a By object.
     * Supports id, name, cssSelector, and xpath.
     */
    private By parseBy(String byString) {
        if (byString == null) return null;
        if (byString.startsWith("By.id:"))          return By.id(byString.substring(7).trim());
        if (byString.startsWith("By.name:"))        return By.name(byString.substring(8).trim());
        if (byString.startsWith("By.cssSelector:")) return By.cssSelector(byString.substring(16).trim());
        if (byString.startsWith("By.xpath:"))       return By.xpath(byString.substring(9).trim());
        if (byString.startsWith("By.className:"))   return By.className(byString.substring(14).trim());
        // Default: treat as CSS selector (covers [attr*='val'] healed locators)
        int colon = byString.indexOf(": ");
        return By.cssSelector(colon == -1 ? byString : byString.substring(colon + 2).trim());
    }

    /**
     * Strips query parameters and fragments from the URL for stable matching.
     * e.g. "https://example.com/login?ref=home#top" → "https://example.com/login"
     */
    private String normaliseUrl(String url) {
        if (url == null) return "";
        int q = url.indexOf('?');
        int h = url.indexOf('#');
        int end = url.length();
        if (q > 0) end = Math.min(end, q);
        if (h > 0) end = Math.min(end, h);
        return url.substring(0, end);
    }
}
