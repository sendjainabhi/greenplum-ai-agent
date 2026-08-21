package com.gp.agent.db;

import java.util.List;
import java.util.Map;

/**
 * DAO interface for all persistent application data.
 * Two implementations exist: {@link FileAgentDao} (file-based, default fallback)
 * and {@link PostgresAgentDao} (JDBC, used when a postgres VCAP binding is present).
 */
public interface AgentDao {

    // --- Global configuration ---
    String loadGlobalPrompt();
    void saveGlobalPrompt(String prompt);

    // --- Access control ---
    /** Returns raw allowlist text (newline-separated emails, may include # comments). */
    String loadAllowlist();
    /** Parses and persists the raw allowlist text. */
    void saveAllowlist(String text);
    /** Returns true when the email is in the allowlist, or when no allowlist exists. */
    boolean isUserAllowed(String email);

    List<String> loadKnownUsers();
    void addKnownUser(String email);

    // --- RBAC ---
    /** Always returns ADMIN as the first entry; custom roles follow. */
    List<String> loadRoles();
    /** Persists custom roles; ADMIN is implicit and never stored. */
    void saveRoles(List<String> roles);

    Map<String, String> loadUserRoleMap();
    void saveUserRoleMap(Map<String, String> map);
    void saveUserRole(String email, String role);
    void deleteUserRole(String email);

    // --- Per-user data ---
    Map<String, String> loadUserConfig(String userId);
    void saveUserConfig(String userId, Map<String, String> config);

    String loadUserPrefs(String userId);
    void saveUserPrefs(String userId, String prefs);

    /** Returns raw sessions JSON blob, or empty string if not found. */
    String loadUserSessions(String userId);
    void saveUserSessions(String userId, String json);

    /** Returns raw favourites JSON array, or "[]" if not found. */
    String loadUserFavourites(String userId);
    void saveUserFavourites(String userId, String json);

    // --- Cleanup ---
    /** Deletes all LangChain4j chat-memory records for the given user. */
    void clearUserMemory(String userId);
    /** Wipes all per-user data (config, prefs, sessions, favourites, memory). */
    void clearUserData(String userId);

    // --- Business glossary ---
    List<Map<String, String>> loadGlossary();
    void saveGlossaryEntry(String term, String tableRef, String columnRef, String rule);
    void deleteGlossaryEntry(String term);

    // --- Query templates ---
    List<Map<String, String>> loadQueryTemplates();
    void saveQueryTemplate(String name, String keywords, String hintSql, String description);
    void deleteQueryTemplate(int id);
}
