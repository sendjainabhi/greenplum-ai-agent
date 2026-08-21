package com.gp.agent.db;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.*;
import java.util.stream.Collectors;

/**
 * PostgreSQL-backed DAO — active when a Tanzu Postgres VCAP binding is present.
 * All writes use INSERT ... ON CONFLICT DO UPDATE (upsert) so the schema only
 * needs to be created once by Flyway (V1__schema.sql).
 */
public class PostgresAgentDao implements AgentDao {

    private static final Logger log = LoggerFactory.getLogger(PostgresAgentDao.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String ADMIN_ROLE = "ADMIN";
    private static final String KEY_GLOBAL_PROMPT = "global_prompt";

    private final JdbcTemplate jdbc;

    public PostgresAgentDao(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // --- Global prompt ---

    @Override
    public String loadGlobalPrompt() {
        try {
            List<String> rows = jdbc.queryForList(
                    "SELECT value FROM app_config WHERE key = ?", String.class, KEY_GLOBAL_PROMPT);
            return rows.isEmpty() ? "" : rows.get(0).trim();
        } catch (Exception e) {
            log.warn("[DAO] Could not read global prompt: {}", e.getMessage());
            return "";
        }
    }

    @Override
    public void saveGlobalPrompt(String prompt) {
        jdbc.update("""
                INSERT INTO app_config(key, value, updated_at) VALUES (?, ?, NOW())
                ON CONFLICT(key) DO UPDATE SET value = EXCLUDED.value, updated_at = NOW()
                """, KEY_GLOBAL_PROMPT, prompt == null ? "" : prompt);
    }

    // --- Allowlist ---

    @Override
    public String loadAllowlist() {
        try {
            List<String> emails = jdbc.queryForList(
                    "SELECT email FROM allowed_users ORDER BY email", String.class);
            return String.join("\n", emails);
        } catch (Exception e) {
            log.error("[DAO] Could not read allowlist: {}", e.getMessage());
            return "";
        }
    }

    @Override
    public void saveAllowlist(String text) {
        List<String> emails = parseEmails(text);
        jdbc.update("DELETE FROM allowed_users");
        for (String email : emails) {
            jdbc.update("""
                    INSERT INTO allowed_users(email, created_at) VALUES (?, NOW())
                    ON CONFLICT(email) DO NOTHING
                    """, email);
        }
        // Remove role assignments for users no longer in the allowlist
        if (!emails.isEmpty()) {
            String placeholders = emails.stream().map(e -> "LOWER(?)").collect(Collectors.joining(", "));
            List<Object> params = new ArrayList<>(emails);
            jdbc.update("DELETE FROM user_roles WHERE LOWER(email) NOT IN (" + placeholders + ")",
                    params.toArray());
        }
    }

    @Override
    public boolean isUserAllowed(String email) {
        if (email == null || email.isBlank()) return false;
        try {
            Integer count = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM allowed_users", Integer.class);
            if (count == null || count == 0) return true; // empty list = allow all
            Integer match = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM allowed_users WHERE LOWER(email) = LOWER(?)",
                    Integer.class, email.trim());
            return match != null && match > 0;
        } catch (Exception e) {
            log.warn("[DAO] Could not check allowlist, failing open: {}", e.getMessage());
            return true;
        }
    }

    // --- Known users ---

    @Override
    public List<String> loadKnownUsers() {
        try {
            return jdbc.queryForList("SELECT email FROM known_users ORDER BY email", String.class);
        } catch (Exception e) {
            log.error("[DAO] Could not read known users: {}", e.getMessage());
            return List.of();
        }
    }

    @Override
    public void addKnownUser(String email) {
        if (email == null || email.isBlank()) return;
        jdbc.update("""
                INSERT INTO known_users(email, first_seen) VALUES (?, NOW())
                ON CONFLICT(email) DO NOTHING
                """, email.toLowerCase().trim());
    }

    // --- Roles ---

    @Override
    public List<String> loadRoles() {
        List<String> roles = new ArrayList<>();
        roles.add(ADMIN_ROLE);
        try {
            List<String> custom = jdbc.queryForList(
                    "SELECT name FROM roles WHERE name != ? ORDER BY created_at",
                    String.class, ADMIN_ROLE);
            roles.addAll(custom);
        } catch (Exception e) {
            log.warn("[DAO] Could not read roles: {}", e.getMessage());
        }
        return roles;
    }

    @Override
    public void saveRoles(List<String> roles) {
        List<String> custom = roles.stream()
                .filter(r -> !r.equalsIgnoreCase(ADMIN_ROLE))
                .collect(Collectors.toList());
        jdbc.update("DELETE FROM roles WHERE name != ?", ADMIN_ROLE);
        for (String role : custom) {
            jdbc.update("""
                    INSERT INTO roles(name, created_at) VALUES (?, NOW())
                    ON CONFLICT(name) DO NOTHING
                    """, role);
        }
    }

    // --- User role map ---

    @Override
    public Map<String, String> loadUserRoleMap() {
        Map<String, String> map = new LinkedHashMap<>();
        try {
            jdbc.queryForList("SELECT email, role FROM user_roles ORDER BY email")
                    .forEach(row -> map.put((String) row.get("email"), (String) row.get("role")));
        } catch (Exception e) {
            log.warn("[DAO] Could not read user role map: {}", e.getMessage());
        }
        return map;
    }

    @Override
    public void saveUserRoleMap(Map<String, String> map) {
        jdbc.update("DELETE FROM user_roles");
        map.forEach((email, role) -> jdbc.update("""
                INSERT INTO user_roles(email, role, updated_at) VALUES (?, ?, NOW())
                ON CONFLICT(email) DO UPDATE SET role = EXCLUDED.role, updated_at = NOW()
                """, email, role));
    }

    @Override
    public void saveUserRole(String email, String role) {
        if (email == null || email.isBlank()) return;
        String em = email.toLowerCase().trim();
        if (role == null || role.isEmpty()) {
            jdbc.update("DELETE FROM user_roles WHERE email = ?", em);
        } else {
            jdbc.update("""
                    INSERT INTO user_roles(email, role, updated_at) VALUES (?, ?, NOW())
                    ON CONFLICT(email) DO UPDATE SET role = EXCLUDED.role, updated_at = NOW()
                    """, em, role);
        }
    }

    @Override
    public void deleteUserRole(String email) {
        if (email == null || email.isBlank()) return;
        jdbc.update("DELETE FROM user_roles WHERE LOWER(email) = LOWER(?)", email.trim());
    }

    // --- User config ---

    @Override
    @SuppressWarnings("unchecked")
    public Map<String, String> loadUserConfig(String userId) {
        try {
            List<String> rows = jdbc.queryForList(
                    "SELECT config_json FROM user_config WHERE user_id = ?", String.class, userId);
            if (rows.isEmpty()) return new LinkedHashMap<>();
            return MAPPER.readValue(rows.get(0), Map.class);
        } catch (Exception e) {
            log.warn("[DAO] Could not read config for {}: {}", userId, e.getMessage());
            return new LinkedHashMap<>();
        }
    }

    @Override
    public void saveUserConfig(String userId, Map<String, String> config) {
        try {
            String json = MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(config);
            jdbc.update("""
                    INSERT INTO user_config(user_id, config_json, updated_at) VALUES (?, ?, NOW())
                    ON CONFLICT(user_id) DO UPDATE SET config_json = EXCLUDED.config_json, updated_at = NOW()
                    """, userId, json);
        } catch (Exception e) {
            log.error("[DAO] Could not save config for {}: {}", userId, e.getMessage());
            throw new RuntimeException(e);
        }
    }

    // --- User prefs ---

    @Override
    public String loadUserPrefs(String userId) {
        try {
            List<String> rows = jdbc.queryForList(
                    "SELECT prefs FROM user_preferences WHERE user_id = ?", String.class, userId);
            return rows.isEmpty() ? "" : rows.get(0).trim();
        } catch (Exception e) {
            log.warn("[DAO] Could not read prefs for {}: {}", userId, e.getMessage());
            return "";
        }
    }

    @Override
    public void saveUserPrefs(String userId, String prefs) {
        jdbc.update("""
                INSERT INTO user_preferences(user_id, prefs, updated_at) VALUES (?, ?, NOW())
                ON CONFLICT(user_id) DO UPDATE SET prefs = EXCLUDED.prefs, updated_at = NOW()
                """, userId, prefs == null ? "" : prefs);
    }

    // --- User sessions ---

    @Override
    public String loadUserSessions(String userId) {
        try {
            List<String> rows = jdbc.queryForList(
                    "SELECT data FROM user_sessions WHERE user_id = ?", String.class, userId);
            return rows.isEmpty() ? "" : rows.get(0);
        } catch (Exception e) {
            log.warn("[DAO] Could not read sessions for {}: {}", userId, e.getMessage());
            return "";
        }
    }

    @Override
    public void saveUserSessions(String userId, String json) {
        jdbc.update("""
                INSERT INTO user_sessions(user_id, data, updated_at) VALUES (?, ?, NOW())
                ON CONFLICT(user_id) DO UPDATE SET data = EXCLUDED.data, updated_at = NOW()
                """, userId, json == null ? "{}" : json);
    }

    // --- User favourites ---

    @Override
    public String loadUserFavourites(String userId) {
        try {
            List<String> rows = jdbc.queryForList(
                    "SELECT data FROM user_favourites WHERE user_id = ?", String.class, userId);
            return rows.isEmpty() ? "[]" : rows.get(0);
        } catch (Exception e) {
            log.warn("[DAO] Could not read favourites for {}: {}", userId, e.getMessage());
            return "[]";
        }
    }

    @Override
    public void saveUserFavourites(String userId, String json) {
        jdbc.update("""
                INSERT INTO user_favourites(user_id, data, updated_at) VALUES (?, ?, NOW())
                ON CONFLICT(user_id) DO UPDATE SET data = EXCLUDED.data, updated_at = NOW()
                """, userId, json == null ? "[]" : json);
    }

    // --- Cleanup ---

    @Override
    public void clearUserMemory(String userId) {
        int deleted = jdbc.update("DELETE FROM user_memory WHERE user_id = ?", userId);
        log.info("[DAO] Cleared {} memory rows for user {}", deleted, userId);
    }

    @Override
    public void clearUserData(String userId) {
        jdbc.update("DELETE FROM user_config     WHERE user_id = ?", userId);
        jdbc.update("DELETE FROM user_preferences WHERE user_id = ?", userId);
        jdbc.update("DELETE FROM user_sessions   WHERE user_id = ?", userId);
        jdbc.update("DELETE FROM user_favourites WHERE user_id = ?", userId);
        jdbc.update("DELETE FROM user_memory     WHERE user_id = ?", userId);
        log.info("[DAO] Cleared all data for user {}", userId);
    }

    // --- Business glossary ---

    @Override
    public List<Map<String, String>> loadGlossary() {
        try {
            return jdbc.queryForList(
                    "SELECT term, table_ref, column_ref, rule FROM business_glossary ORDER BY term")
                    .stream()
                    .map(row -> {
                        Map<String, String> m = new LinkedHashMap<>();
                        row.forEach((k, v) -> m.put(k, v == null ? "" : v.toString()));
                        return m;
                    }).collect(Collectors.toList());
        } catch (Exception e) {
            log.warn("[DAO] Could not read glossary: {}", e.getMessage());
            return List.of();
        }
    }

    @Override
    public void saveGlossaryEntry(String term, String tableRef, String columnRef, String rule) {
        jdbc.update("""
                INSERT INTO business_glossary(term, table_ref, column_ref, rule, created_at)
                     VALUES (?, ?, ?, ?, NOW())
                ON CONFLICT(term) DO UPDATE
                   SET table_ref = EXCLUDED.table_ref,
                       column_ref = EXCLUDED.column_ref,
                       rule = EXCLUDED.rule
                """, term, tableRef, columnRef, rule);
    }

    @Override
    public void deleteGlossaryEntry(String term) {
        jdbc.update("DELETE FROM business_glossary WHERE term = ?", term);
    }

    // --- Query templates ---

    @Override
    public List<Map<String, String>> loadQueryTemplates() {
        try {
            return jdbc.queryForList(
                    "SELECT id::text AS id, name, keywords, hint_sql, description FROM query_templates ORDER BY id")
                    .stream()
                    .map(row -> {
                        Map<String, String> m = new LinkedHashMap<>();
                        row.forEach((k, v) -> m.put(k, v == null ? "" : v.toString()));
                        return m;
                    }).collect(Collectors.toList());
        } catch (Exception e) {
            log.warn("[DAO] Could not read query templates: {}", e.getMessage());
            return List.of();
        }
    }

    @Override
    public void saveQueryTemplate(String name, String keywords, String hintSql, String description) {
        jdbc.update("""
                INSERT INTO query_templates(name, keywords, hint_sql, description, created_at)
                     VALUES (?, ?, ?, ?, NOW())
                """, name, keywords, hintSql, description);
    }

    @Override
    public void deleteQueryTemplate(int id) {
        jdbc.update("DELETE FROM query_templates WHERE id = ?", id);
    }

    // --- Helper ---

    private List<String> parseEmails(String text) {
        if (text == null || text.isBlank()) return List.of();
        return Arrays.stream(text.split("[\r\n]+"))
                .map(String::trim)
                .filter(l -> !l.isEmpty() && !l.startsWith("#"))
                .map(String::toLowerCase)
                .distinct().sorted().collect(Collectors.toList());
    }
}
