package com.gp.agent.db;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gp.agent.GreenplumAgentApplication;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import java.util.stream.Collectors;

/**
 * File-based DAO — active when no PostgreSQL VCAP binding is present.
 * Reads from and writes to the CF block-storage volume (AGENT_DATA_DIR).
 */
public class FileAgentDao implements AgentDao {

    private static final Logger log = LoggerFactory.getLogger(FileAgentDao.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String ADMIN_ROLE = "ADMIN";

    // --- Global prompt ---

    @Override
    public String loadGlobalPrompt() {
        try {
            File f = dataFile("global-prompt.txt");
            return f.exists() ? Files.readString(f.toPath(), StandardCharsets.UTF_8).trim() : "";
        } catch (Exception e) {
            log.warn("[DAO] Could not read global prompt: {}", e.getMessage());
            return "";
        }
    }

    @Override
    public void saveGlobalPrompt(String prompt) {
        try {
            Files.writeString(dataFile("global-prompt.txt").toPath(),
                    prompt == null ? "" : prompt, StandardCharsets.UTF_8);
        } catch (Exception e) {
            log.error("[DAO] Could not save global prompt: {}", e.getMessage());
            throw new RuntimeException(e);
        }
    }

    // --- Allowlist ---

    @Override
    public String loadAllowlist() {
        try {
            File f = dataFile("allowed-users.txt");
            return f.exists() ? Files.readString(f.toPath(), StandardCharsets.UTF_8) : "";
        } catch (Exception e) {
            log.error("[DAO] Could not read allowlist: {}", e.getMessage());
            return "";
        }
    }

    @Override
    public void saveAllowlist(String text) {
        try {
            Files.writeString(dataFile("allowed-users.txt").toPath(),
                    text == null ? "" : text, StandardCharsets.UTF_8);
        } catch (Exception e) {
            log.error("[DAO] Could not save allowlist: {}", e.getMessage());
            throw new RuntimeException(e);
        }
    }

    @Override
    public boolean isUserAllowed(String email) {
        if (email == null || email.isBlank()) return false;
        File f = dataFile("allowed-users.txt");
        if (!f.exists()) return true;
        try {
            List<String> lines = Files.readAllLines(f.toPath(), StandardCharsets.UTF_8);
            boolean hasEntries = lines.stream()
                    .anyMatch(l -> !l.trim().isEmpty() && !l.trim().startsWith("#"));
            if (!hasEntries) return true;
            return lines.stream()
                    .map(String::trim)
                    .filter(l -> !l.isEmpty() && !l.startsWith("#"))
                    .anyMatch(l -> l.equalsIgnoreCase(email));
        } catch (Exception e) {
            log.warn("[DAO] Could not read allowlist, failing open: {}", e.getMessage());
            return true;
        }
    }

    // --- Known users ---

    @Override
    public List<String> loadKnownUsers() {
        try {
            File f = dataFile("known-users.txt");
            if (!f.exists()) return List.of();
            return Files.readAllLines(f.toPath(), StandardCharsets.UTF_8).stream()
                    .map(String::trim)
                    .filter(l -> !l.isEmpty() && !l.startsWith("#"))
                    .distinct().sorted().collect(Collectors.toList());
        } catch (Exception e) {
            log.error("[DAO] Could not read known users: {}", e.getMessage());
            return List.of();
        }
    }

    @Override
    public void addKnownUser(String email) {
        if (email == null || email.isBlank()) return;
        try {
            File f = dataFile("known-users.txt");
            List<String> existing = f.exists()
                    ? new ArrayList<>(Files.readAllLines(f.toPath(), StandardCharsets.UTF_8))
                    : new ArrayList<>();
            String norm = email.toLowerCase().trim();
            boolean present = existing.stream().map(String::trim).anyMatch(norm::equals);
            if (!present) {
                existing.add(norm);
                Files.write(f.toPath(), existing, StandardCharsets.UTF_8);
            }
        } catch (Exception e) {
            log.warn("[DAO] Could not add known user {}: {}", email, e.getMessage());
        }
    }

    // --- Roles ---

    @Override
    public List<String> loadRoles() {
        List<String> roles = new ArrayList<>();
        roles.add(ADMIN_ROLE);
        try {
            File f = dataFile("roles-list.txt");
            if (f.exists()) {
                Files.readAllLines(f.toPath(), StandardCharsets.UTF_8).stream()
                        .map(String::trim)
                        .filter(l -> !l.isEmpty() && !l.startsWith("#") && !l.equalsIgnoreCase(ADMIN_ROLE))
                        .forEach(roles::add);
            }
        } catch (Exception e) {
            log.warn("[DAO] Could not read roles: {}", e.getMessage());
        }
        return roles;
    }

    @Override
    public void saveRoles(List<String> roles) {
        try {
            List<String> toWrite = roles.stream()
                    .filter(r -> !r.equalsIgnoreCase(ADMIN_ROLE))
                    .collect(Collectors.toList());
            Files.write(dataFile("roles-list.txt").toPath(), toWrite, StandardCharsets.UTF_8);
        } catch (Exception e) {
            log.error("[DAO] Could not save roles: {}", e.getMessage());
            throw new RuntimeException(e);
        }
    }

    // --- User role map ---

    @Override
    public Map<String, String> loadUserRoleMap() {
        Map<String, String> map = new LinkedHashMap<>();
        try {
            File f = dataFile("user-role-map.txt");
            if (!f.exists()) return map;
            for (String line : Files.readAllLines(f.toPath(), StandardCharsets.UTF_8)) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#")) continue;
                int idx = line.indexOf('=');
                if (idx < 0) continue;
                String em   = line.substring(0, idx).trim().toLowerCase();
                String role = line.substring(idx + 1).trim();
                if (!em.isEmpty() && !role.isEmpty()) map.put(em, role);
            }
        } catch (Exception e) {
            log.warn("[DAO] Could not read user role map: {}", e.getMessage());
        }
        return map;
    }

    @Override
    public void saveUserRoleMap(Map<String, String> map) {
        try {
            List<String> lines = new ArrayList<>();
            map.forEach((em, role) -> lines.add(em + " = " + role));
            Files.write(dataFile("user-role-map.txt").toPath(), lines, StandardCharsets.UTF_8);
        } catch (Exception e) {
            log.error("[DAO] Could not save user role map: {}", e.getMessage());
            throw new RuntimeException(e);
        }
    }

    @Override
    public void saveUserRole(String email, String role) {
        if (email == null || email.isBlank()) return;
        Map<String, String> map = new LinkedHashMap<>(loadUserRoleMap());
        String em = email.toLowerCase().trim();
        if (role == null || role.isEmpty()) map.remove(em);
        else map.put(em, role);
        saveUserRoleMap(map);
    }

    @Override
    public void deleteUserRole(String email) {
        if (email == null || email.isBlank()) return;
        Map<String, String> map = new LinkedHashMap<>(loadUserRoleMap());
        map.remove(email.toLowerCase().trim());
        saveUserRoleMap(map);
    }

    // --- User config ---

    @Override
    @SuppressWarnings("unchecked")
    public Map<String, String> loadUserConfig(String userId) {
        try {
            File f = userFile(userId, "config.json");
            if (!f.exists()) return new LinkedHashMap<>();
            return MAPPER.readValue(Files.readString(f.toPath(), StandardCharsets.UTF_8), Map.class);
        } catch (Exception e) {
            log.warn("[DAO] Could not read config for {}: {}", userId, e.getMessage());
            return new LinkedHashMap<>();
        }
    }

    @Override
    public void saveUserConfig(String userId, Map<String, String> config) {
        try {
            Files.writeString(userFile(userId, "config.json").toPath(),
                    MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(config),
                    StandardCharsets.UTF_8);
        } catch (Exception e) {
            log.error("[DAO] Could not save config for {}: {}", userId, e.getMessage());
            throw new RuntimeException(e);
        }
    }

    // --- User prefs ---

    @Override
    public String loadUserPrefs(String userId) {
        try {
            File f = userFile(userId, "user-prefs.txt");
            return f.exists() ? Files.readString(f.toPath(), StandardCharsets.UTF_8).trim() : "";
        } catch (Exception e) {
            log.warn("[DAO] Could not read prefs for {}: {}", userId, e.getMessage());
            return "";
        }
    }

    @Override
    public void saveUserPrefs(String userId, String prefs) {
        try {
            Files.writeString(userFile(userId, "user-prefs.txt").toPath(),
                    prefs == null ? "" : prefs, StandardCharsets.UTF_8);
        } catch (Exception e) {
            log.error("[DAO] Could not save prefs for {}: {}", userId, e.getMessage());
            throw new RuntimeException(e);
        }
    }

    // --- User sessions ---

    @Override
    public String loadUserSessions(String userId) {
        try {
            File f = userFile(userId, "sessions.json");
            return f.exists() ? Files.readString(f.toPath(), StandardCharsets.UTF_8) : "";
        } catch (Exception e) {
            log.warn("[DAO] Could not read sessions for {}: {}", userId, e.getMessage());
            return "";
        }
    }

    @Override
    public void saveUserSessions(String userId, String json) {
        try {
            Files.writeString(userFile(userId, "sessions.json").toPath(),
                    json == null ? "{}" : json, StandardCharsets.UTF_8);
        } catch (Exception e) {
            log.error("[DAO] Could not save sessions for {}: {}", userId, e.getMessage());
            throw new RuntimeException(e);
        }
    }

    // --- User favourites ---

    @Override
    public String loadUserFavourites(String userId) {
        try {
            File f = userFile(userId, "favourites.json");
            return f.exists() ? Files.readString(f.toPath(), StandardCharsets.UTF_8) : "[]";
        } catch (Exception e) {
            log.warn("[DAO] Could not read favourites for {}: {}", userId, e.getMessage());
            return "[]";
        }
    }

    @Override
    public void saveUserFavourites(String userId, String json) {
        try {
            Files.writeString(userFile(userId, "favourites.json").toPath(),
                    json == null ? "[]" : json, StandardCharsets.UTF_8);
        } catch (Exception e) {
            log.error("[DAO] Could not save favourites for {}: {}", userId, e.getMessage());
            throw new RuntimeException(e);
        }
    }

    // --- Cleanup ---

    @Override
    public void clearUserMemory(String userId) {
        File memDir = new File(GreenplumAgentApplication.resolveDataDir()
                + File.separator + "users" + File.separator + userId
                + File.separator + "memory");
        deleteDir(memDir);
        log.info("[DAO] Cleared memory for user {}", userId);
    }

    @Override
    public void clearUserData(String userId) {
        File userDir = new File(GreenplumAgentApplication.resolveDataDir()
                + File.separator + "users" + File.separator + userId);
        deleteDir(userDir);
        log.info("[DAO] Cleared all data for user {}", userId);
    }

    // --- Business glossary (not supported in file mode — return empty) ---

    @Override
    public List<Map<String, String>> loadGlossary() { return List.of(); }

    @Override
    public void saveGlossaryEntry(String term, String tableRef, String columnRef, String rule) {}

    @Override
    public void deleteGlossaryEntry(String term) {}

    // --- Query templates (not supported in file mode — return empty) ---

    @Override
    public List<Map<String, String>> loadQueryTemplates() { return List.of(); }

    @Override
    public void saveQueryTemplate(String name, String keywords, String hintSql, String description) {}

    @Override
    public void deleteQueryTemplate(int id) {}

    // --- Helpers ---

    private File dataFile(String filename) {
        return new File(GreenplumAgentApplication.resolveDataDir(), filename);
    }

    private File userFile(String userId, String filename) {
        File dir = new File(GreenplumAgentApplication.resolveDataDir()
                + File.separator + "users" + File.separator + userId);
        dir.mkdirs();
        return new File(dir, filename);
    }

    private void deleteDir(File dir) {
        if (dir == null || !dir.exists()) return;
        File[] files = dir.listFiles();
        if (files != null) {
            for (File f : files) {
                if (f.isDirectory()) deleteDir(f);
                else f.delete();
            }
        }
        dir.delete();
    }
}
