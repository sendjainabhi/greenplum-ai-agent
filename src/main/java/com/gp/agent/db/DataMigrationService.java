package com.gp.agent.db;

import com.gp.agent.GreenplumAgentApplication;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;

/**
 * On first boot with a postgres binding, migrates existing flat-file data
 * from the CF block-storage volume to the postgres tables.
 * Safe to run repeatedly — it checks for existing data before inserting,
 * and skips migration if the DB already has rows.
 */
@Service
@ConditionalOnProperty(name = "gp.agent.storage", havingValue = "postgres")
public class DataMigrationService {

    private static final Logger log = LoggerFactory.getLogger(DataMigrationService.class);

    private final JdbcTemplate jdbc;

    public DataMigrationService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void migrateOnStartup() {
        String dataDir = GreenplumAgentApplication.resolveDataDir();
        File root = new File(dataDir);
        if (!root.exists()) {
            log.info("[MIGRATION] No block-storage data directory found — skipping migration");
            return;
        }
        log.info("[MIGRATION] Starting block-storage → postgres migration from {}", dataDir);
        try {
            migrateGlobalPrompt(root);
            migrateAllowlist(root);
            migrateKnownUsers(root);
            migrateRoles(root);
            migrateUserRoleMap(root);
            migrateUserData(root);
            log.info("[MIGRATION] Migration completed successfully");
        } catch (Exception e) {
            log.error("[MIGRATION] Migration failed: {}", e.getMessage(), e);
        }
    }

    private void migrateGlobalPrompt(File root) throws Exception {
        File f = new File(root, "global-prompt.txt");
        if (!f.exists()) return;
        Integer existing = jdbc.queryForObject("SELECT COUNT(*) FROM app_config WHERE key = 'global_prompt'", Integer.class);
        if (existing != null && existing > 0) { log.info("[MIGRATION] global_prompt already in DB — skipping"); return; }
        String content = Files.readString(f.toPath(), StandardCharsets.UTF_8).trim();
        jdbc.update("INSERT INTO app_config(key, value, updated_at) VALUES ('global_prompt', ?, NOW())", content);
        log.info("[MIGRATION] Migrated global-prompt.txt ({} chars)", content.length());
    }

    private void migrateAllowlist(File root) throws Exception {
        File f = new File(root, "allowed-users.txt");
        if (!f.exists()) return;
        Integer existing = jdbc.queryForObject("SELECT COUNT(*) FROM allowed_users", Integer.class);
        if (existing != null && existing > 0) { log.info("[MIGRATION] allowed_users already in DB — skipping"); return; }
        List<String> lines = Files.readAllLines(f.toPath(), StandardCharsets.UTF_8);
        int count = 0;
        for (String line : lines) {
            String email = line.trim().toLowerCase();
            if (email.isEmpty() || email.startsWith("#")) continue;
            jdbc.update("INSERT INTO allowed_users(email, created_at) VALUES (?, NOW()) ON CONFLICT(email) DO NOTHING", email);
            count++;
        }
        log.info("[MIGRATION] Migrated {} emails from allowed-users.txt", count);
    }

    private void migrateKnownUsers(File root) throws Exception {
        File f = new File(root, "known-users.txt");
        if (!f.exists()) return;
        Integer existing = jdbc.queryForObject("SELECT COUNT(*) FROM known_users", Integer.class);
        if (existing != null && existing > 0) { log.info("[MIGRATION] known_users already in DB — skipping"); return; }
        List<String> lines = Files.readAllLines(f.toPath(), StandardCharsets.UTF_8);
        int count = 0;
        for (String line : lines) {
            String email = line.trim().toLowerCase();
            if (email.isEmpty() || email.startsWith("#")) continue;
            jdbc.update("INSERT INTO known_users(email, first_seen) VALUES (?, NOW()) ON CONFLICT(email) DO NOTHING", email);
            count++;
        }
        log.info("[MIGRATION] Migrated {} known users", count);
    }

    private void migrateRoles(File root) throws Exception {
        File f = new File(root, "roles-list.txt");
        if (!f.exists()) return;
        Integer existing = jdbc.queryForObject("SELECT COUNT(*) FROM roles", Integer.class);
        if (existing != null && existing > 0) { log.info("[MIGRATION] roles already in DB — skipping"); return; }
        List<String> lines = Files.readAllLines(f.toPath(), StandardCharsets.UTF_8);
        int count = 0;
        for (String line : lines) {
            String role = line.trim();
            if (role.isEmpty() || role.startsWith("#") || role.equalsIgnoreCase("ADMIN")) continue;
            jdbc.update("INSERT INTO roles(name, created_at) VALUES (?, NOW()) ON CONFLICT(name) DO NOTHING", role);
            count++;
        }
        log.info("[MIGRATION] Migrated {} custom roles", count);
    }

    private void migrateUserRoleMap(File root) throws Exception {
        File f = new File(root, "user-role-map.txt");
        if (!f.exists()) return;
        Integer existing = jdbc.queryForObject("SELECT COUNT(*) FROM user_roles", Integer.class);
        if (existing != null && existing > 0) { log.info("[MIGRATION] user_roles already in DB — skipping"); return; }
        List<String> lines = Files.readAllLines(f.toPath(), StandardCharsets.UTF_8);
        int count = 0;
        for (String line : lines) {
            line = line.trim();
            if (line.isEmpty() || line.startsWith("#")) continue;
            int idx = line.indexOf('=');
            if (idx < 0) continue;
            String email = line.substring(0, idx).trim().toLowerCase();
            String role  = line.substring(idx + 1).trim();
            if (!email.isEmpty() && !role.isEmpty()) {
                jdbc.update("INSERT INTO user_roles(email, role, updated_at) VALUES (?, ?, NOW()) ON CONFLICT(email) DO NOTHING", email, role);
                count++;
            }
        }
        log.info("[MIGRATION] Migrated {} user role assignments", count);
    }

    private void migrateUserData(File root) throws Exception {
        File usersDir = new File(root, "users");
        if (!usersDir.exists() || !usersDir.isDirectory()) return;
        File[] userDirs = usersDir.listFiles(File::isDirectory);
        if (userDirs == null) return;

        for (File userDir : userDirs) {
            String userId = userDir.getName();
            try {
                migrateUserConfig(userId, userDir);
                migrateUserPrefs(userId, userDir);
                migrateUserSessions(userId, userDir);
                migrateUserFavourites(userId, userDir);
                migrateUserMemory(userId, userDir);
            } catch (Exception e) {
                log.warn("[MIGRATION] Failed migrating user {}: {}", userId, e.getMessage());
            }
        }
    }

    private void migrateUserConfig(String userId, File userDir) throws Exception {
        File f = new File(userDir, "config.json");
        if (!f.exists()) return;
        Integer existing = jdbc.queryForObject("SELECT COUNT(*) FROM user_config WHERE user_id = ?", Integer.class, userId);
        if (existing != null && existing > 0) return;
        String json = Files.readString(f.toPath(), StandardCharsets.UTF_8);
        jdbc.update("INSERT INTO user_config(user_id, config_json, updated_at) VALUES (?, ?, NOW())", userId, json);
        log.debug("[MIGRATION] Migrated config for user {}", userId);
    }

    private void migrateUserPrefs(String userId, File userDir) throws Exception {
        File f = new File(userDir, "user-prefs.txt");
        if (!f.exists()) return;
        Integer existing = jdbc.queryForObject("SELECT COUNT(*) FROM user_preferences WHERE user_id = ?", Integer.class, userId);
        if (existing != null && existing > 0) return;
        String prefs = Files.readString(f.toPath(), StandardCharsets.UTF_8);
        jdbc.update("INSERT INTO user_preferences(user_id, prefs, updated_at) VALUES (?, ?, NOW())", userId, prefs);
        log.debug("[MIGRATION] Migrated prefs for user {}", userId);
    }

    private void migrateUserSessions(String userId, File userDir) throws Exception {
        File f = new File(userDir, "sessions.json");
        if (!f.exists()) return;
        Integer existing = jdbc.queryForObject("SELECT COUNT(*) FROM user_sessions WHERE user_id = ?", Integer.class, userId);
        if (existing != null && existing > 0) return;
        String json = Files.readString(f.toPath(), StandardCharsets.UTF_8);
        jdbc.update("INSERT INTO user_sessions(user_id, data, updated_at) VALUES (?, ?, NOW())", userId, json);
        log.debug("[MIGRATION] Migrated sessions for user {}", userId);
    }

    private void migrateUserFavourites(String userId, File userDir) throws Exception {
        File f = new File(userDir, "favourites.json");
        if (!f.exists()) return;
        Integer existing = jdbc.queryForObject("SELECT COUNT(*) FROM user_favourites WHERE user_id = ?", Integer.class, userId);
        if (existing != null && existing > 0) return;
        String json = Files.readString(f.toPath(), StandardCharsets.UTF_8);
        jdbc.update("INSERT INTO user_favourites(user_id, data, updated_at) VALUES (?, ?, NOW())", userId, json);
        log.debug("[MIGRATION] Migrated favourites for user {}", userId);
    }

    private void migrateUserMemory(String userId, File userDir) throws Exception {
        File memDir = new File(userDir, "memory");
        if (!memDir.exists() || !memDir.isDirectory()) return;
        File[] memFiles = memDir.listFiles((d, n) -> n.endsWith(".json"));
        if (memFiles == null) return;
        for (File mf : memFiles) {
            String sessionId = mf.getName().replace(".json", "");
            Integer existing = jdbc.queryForObject(
                "SELECT COUNT(*) FROM user_memory WHERE user_id = ? AND session_id = ?",
                Integer.class, userId, sessionId);
            if (existing != null && existing > 0) continue;
            String json = Files.readString(mf.toPath(), StandardCharsets.UTF_8);
            jdbc.update("INSERT INTO user_memory(user_id, session_id, messages, updated_at) VALUES (?, ?, ?, NOW())",
                userId, sessionId, json);
            log.debug("[MIGRATION] Migrated memory session {} for user {}", sessionId, userId);
        }
    }
}
