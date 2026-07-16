package com.gp.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.memory.chat.ChatMemoryProvider;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.ollama.OllamaChatModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.anthropic.AnthropicChatModel;
import dev.langchain4j.service.AiServices;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.web.bind.annotation.*;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@RestController
@RequestMapping("/api")
public class ChatController {

    private static final Logger log = LoggerFactory.getLogger(ChatController.class);
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final int MAX_PROMPT_LENGTH = 4000;

    private final ChatMemoryProvider memoryProvider;
    private final VcapServicesConfig vcapConfig;

    // Agent cache — keyed by userId, rebuilt only when config changes.
    // Stores a BiFunction<memoryId, prompt, response> so either GreenplumAgent or
    // OpenMetadataAgent can be stored without a shared supertype.
    private final ConcurrentHashMap<String, java.util.function.BiFunction<String, String, String>> agentCache = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, String> configHashCache = new ConcurrentHashMap<>();

    // Admin PIN — set via admin.pin in application.yml (overridable via ADMIN_PIN env var)
    @Value("${admin.pin}")
    private String adminPinRaw;
    private String adminPinHash; // SHA-256 of adminPinRaw, computed at startup

    // No in-memory cache for global prompt — always read from disk so updates
    // apply immediately to every user and session without any restart.

    public ChatController(ChatMemoryProvider memoryProvider, VcapServicesConfig vcapConfig) {
        this.memoryProvider = memoryProvider;
        this.vcapConfig     = vcapConfig;
    }

    @PostConstruct
    void init() {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(adminPinRaw.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : hash) sb.append(String.format("%02x", b));
            adminPinHash = sb.toString();
            log.info("[ADMIN] Admin PIN hash loaded");
        } catch (Exception e) {
            throw new IllegalStateException("Failed to hash admin PIN at startup", e);
        }
    }

    // -------------------------------------------------------------------------
    // Deployment mode — tells the frontend whether CF service bindings are active
    // -------------------------------------------------------------------------

    @GetMapping("/deployment-mode")
    ResponseEntity<Map<String, Object>> deploymentMode() {
        if (vcapConfig.isCfMode()) {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("mode",       "cf");
            result.put("model",      vcapConfig.getModelSummary());
            result.put("mcpServers", vcapConfig.getMcpServerNames());
            return ResponseEntity.ok(result);
        }
        return ResponseEntity.ok(Map.of("mode", "manual"));
    }

    // -------------------------------------------------------------------------
    // Auth status — returns SSO identity from Spring SecurityContext
    // -------------------------------------------------------------------------

    @GetMapping("/auth/status")
    ResponseEntity<Map<String, Object>> authStatus(Authentication authentication) {
        Map<String, Object> response = new LinkedHashMap<>();

        if (vcapConfig.isCfMode()) {
            response.put("cfMode",     true);
            response.put("model",      vcapConfig.getModelSummary());
            response.put("mcpServers", vcapConfig.getMcpServerNames());
        }

        if (authentication != null && authentication.getPrincipal() instanceof OidcUser oidcUser) {
            String userId = oidcUser.getSubject();
            String email  = oidcUser.getAttribute("user_name");
            if (email == null || email.isBlank()) email = oidcUser.getEmail();
            if (email == null) email = userId;
            response.put("authenticated", true);
            response.put("userId",        userId);
            response.put("email",         email);
            log.debug("[AUTH] Status: user {} (sub={})", email, userId);
        } else {
            // Local dev mode (DevSecurityConfig — no SSO): return a default user
            response.put("authenticated", true);
            response.put("userId",        "local-dev-user");
            response.put("email",         "local-dev");
        }

        return ResponseEntity.ok(response);
    }

    // -------------------------------------------------------------------------
    // Load settings from server filesystem → sent to browser on every boot
    // -------------------------------------------------------------------------

    @GetMapping("/settings/load")
    ResponseEntity<Map<String, Object>> loadSettingsFromFile(@RequestParam String userId) {
        if (userId == null || userId.trim().isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("success", false, "error", "userId required"));
        }
        try {
            File configFile = getConfigFile(userId.trim());
            if (!configFile.exists()) {
                return ResponseEntity.ok(Map.of("success", false));
            }
            Map<String, String> config = loadOrSeedConfig(userId.trim(), null);
            // Never send PIN hash to the browser
            Map<String, Object> safe = new LinkedHashMap<>(config);
            safe.remove("pinHash");
            safe.remove("pinHint");
            log.debug("[SETTINGS] Loaded from file for user {}", userId);
            return ResponseEntity.ok(Map.of("success", true, "config", safe));
        } catch (Exception e) {
            log.error("[SETTINGS] Load failed for {}: {}", userId, e.getMessage());
            return ResponseEntity.internalServerError().body(Map.of("success", false, "error", e.getMessage()));
        }
    }

    // -------------------------------------------------------------------------
    // Save settings
    // -------------------------------------------------------------------------

    @PostMapping("/settings")
    ResponseEntity<Map<String, Object>> saveSettings(@RequestBody Map<String, String> req) {
        String userId = req.getOrDefault("userId", "default-user");
        try {
            File configFile = getConfigFile(userId);
            // Preserve existing PIN fields — read current config first, then merge new settings on top
            Map<String, String> data = configFile.exists()
                    ? new LinkedHashMap<>(loadOrSeedConfig(userId, null))
                    : new LinkedHashMap<>();
            req.forEach((k, v) -> { if (!k.equals("userId")) data.put(k, v); });
            Files.writeString(configFile.toPath(),
                    OBJECT_MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(data),
                    StandardCharsets.UTF_8);
            agentCache.remove(userId);
            configHashCache.remove(userId);
            log.info("[CONFIG] Saved for user {}", userId);
            return ResponseEntity.ok(Map.of("success", true));
        } catch (Exception e) {
            log.error("[CONFIG] Save failed for user {}: {}", userId, e.getMessage());
            return ResponseEntity.internalServerError()
                    .body(Map.of("success", false, "error", e.getMessage()));
        }
    }

    // -------------------------------------------------------------------------
    // Chat
    // -------------------------------------------------------------------------

    @PostMapping("/chat")
    ResponseEntity<Map<String, Object>> chat(@RequestBody Map<String, Object> request) {
        String prompt    = (String) request.get("prompt");
        String userId    = (String) request.getOrDefault("userId", "default-user");
        String sessionId = (String) request.getOrDefault("sessionId", "default-session");

        if (userId    == null || userId.trim().isEmpty())    userId    = "default-user";
        if (sessionId == null || sessionId.trim().isEmpty()) sessionId = "default-session";

        if (prompt == null || prompt.trim().isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("response", "Please enter a message."));
        }
        if (prompt.length() > MAX_PROMPT_LENGTH) {
            return ResponseEntity.badRequest().body(Map.of("response",
                    "⚠️ Message too long (" + prompt.length() + " chars). Keep under " + MAX_PROMPT_LENGTH + "."));
        }

        @SuppressWarnings("unchecked")
        Map<String, String> reqConfig = (Map<String, String>) request.get("config");
        Map<String, String> config = loadOrSeedConfig(userId, reqConfig);

        // CF mode: fill in any missing fields from VCAP-bound credentials
        if (vcapConfig.isCfMode()) {
            if (config.getOrDefault("modelName", "").trim().isEmpty()) {
                config.putAll(vcapConfig.getModelConfig());
            }
            vcapConfig.getMcpConfig().forEach(config::putIfAbsent);
        }

        String modelName = config.getOrDefault("modelName", "").trim();
        if (modelName.isEmpty()) {
            return ResponseEntity.ok(Map.of("response",
                    "⚠️ **Configuration Required:** Please upload your credential file and configure an AI Provider before chatting."));
        }

        try {
            String provider   = config.getOrDefault("provider",    "ollama").toLowerCase();
            String apiKey     = config.getOrDefault("apiKey",      "");
            String baseUrl    = config.getOrDefault("baseUrl",     "");
            String mcpUrl     = config.getOrDefault("mcpUrl",      "");
            String mcpAuth    = config.getOrDefault("mcpAuth",     "");
            String activeMode = config.getOrDefault("activeMode",  "greenplum").toLowerCase();
            String omMcpUrl   = config.getOrDefault("omMcpUrl",    "");
            String omMcpAuth  = config.getOrDefault("omMcpAuth",   "");
            String sysPrompt  = config.getOrDefault("systemPrompt","");

            java.util.function.BiFunction<String, String, String> chatFn = getOrBuildChatFn(
                    userId, provider, modelName, apiKey, baseUrl,
                    mcpUrl, mcpAuth, activeMode, omMcpUrl, omMcpAuth);

            String memoryId = userId + "::" + sessionId;

            String globalPrompt = loadGlobalPrompt();
            StringBuilder promptBuilder = new StringBuilder(prompt);
            if (!globalPrompt.isEmpty()) {
                promptBuilder.append("\n\n[GLOBAL POLICY INSTRUCTIONS — apply to all responses:\n")
                             .append(globalPrompt).append("]");
            }
            if (!sysPrompt.trim().isEmpty()) {
                promptBuilder.append("\n\n[USER CUSTOM INSTRUCTIONS:\n").append(sysPrompt).append("]");
            }
            if ("both".equals(activeMode)) {
                promptBuilder.append("\n\n[DUAL MODE: You have access to BOTH Greenplum database tools "
                        + "(executeQuery, getClusterStatus, checkTableBloat) AND OpenMetadata catalog tools "
                        + "(searchAssets, getTableDetails, getLineage, listDatabases, getDataQualityResults). "
                        + "Use Greenplum tools for SQL queries and live data retrieval. "
                        + "Use OpenMetadata tools for asset discovery, metadata, lineage, and data quality.]");
            }
            String finalPrompt = promptBuilder.toString();

            String raw      = chatFn.apply(memoryId, finalPrompt);
            String response = sanitizeResponse(raw);
            log.info("[CHAT] user={} session={} length={}", userId, sessionId, response.length());

            // Pre-validate that our static ObjectMapper can serialize this string.
            // ESCAPE_NON_ASCII on Spring's Jackson handles the actual HTTP write,
            // but this catches anything sanitizeResponse missed.
            try {
                OBJECT_MAPPER.writeValueAsString(response);
            } catch (Exception serEx) {
                log.warn("[CHAT] Pre-serialization check failed, ASCII fallback: {}", serEx.getMessage());
                response = response.replaceAll("[^\\x09\\x0A\\x0D\\x20-\\x7E]", "");
            }

            return ResponseEntity.ok(Map.of("response", response));

        } catch (Exception e) {
            if (isCausedByTimeout(e)) {
                log.warn("[CHAT] Timeout for user {}", userId);
                return ResponseEntity.ok(Map.of("response",
                        "⚠️ **Request Timed Out.** The model took too long to respond. "
                        + "Try a simpler question, or wait a moment and ask again."));
            }
            log.error("[CHAT] Error for user {}: {}", userId, e.getMessage(), e);
            return ResponseEntity.internalServerError()
                    .body(Map.of("response", "⚠️ **Server Error:** " + e.getMessage()));
        }
    }

    // -------------------------------------------------------------------------
    // Test connectivity (model + MCP)
    // -------------------------------------------------------------------------

    @PostMapping("/test")
    ResponseEntity<Map<String, Object>> testConnection(@RequestBody Map<String, String> request) {
        String provider  = request.getOrDefault("provider",  "ollama").toLowerCase();
        String modelName = request.getOrDefault("modelName", "").trim();
        String apiKey    = request.getOrDefault("apiKey",    "");
        String baseUrl   = request.getOrDefault("baseUrl",   "");
        String mcpUrl     = request.getOrDefault("mcpUrl",     "");
        String mcpAuth    = request.getOrDefault("mcpAuth",    "");
        String activeMode = request.getOrDefault("activeMode", "greenplum").toLowerCase();
        String omMcpUrl   = request.getOrDefault("omMcpUrl",   "");
        String omMcpAuth  = request.getOrDefault("omMcpAuth",  "");

        if (modelName.isEmpty()) {
            return ResponseEntity.badRequest()
                    .body(Map.of("status", "error", "message", "Model Name cannot be empty."));
        }

        // Test AI model
        String modelStatus, modelMessage;
        try {
            ChatLanguageModel model = buildModel(provider, modelName, apiKey, baseUrl, 90);
            String resp = model.generate("Respond with the exact word: OK");
            modelStatus  = "success";
            modelMessage = "Connected — model responded: " + resp.trim();
        } catch (Exception e) {
            modelStatus  = "error";
            modelMessage = e.getMessage();
        }

        // Test Greenplum MCP (skip when mode is openmetadata-only)
        String mcpStatus = "skipped", mcpMessage = "";
        if (!"openmetadata".equals(activeMode)) {
            Map<String, String> gpResult = GreenplumMcpTools.testConnection(mcpUrl, mcpAuth);
            mcpStatus  = gpResult.get("status");
            mcpMessage = gpResult.get("message");
        }

        // Test OpenMetadata MCP (skip when mode is greenplum-only)
        String omMcpStatus = "skipped", omMcpMessage = "";
        if (!"greenplum".equals(activeMode)) {
            Map<String, String> omResult = OpenMetadataMcpTools.testConnection(omMcpUrl, omMcpAuth);
            omMcpStatus  = omResult.get("status");
            omMcpMessage = omResult.get("message");
        }

        boolean allOk = "success".equals(modelStatus)
                && ("success".equals(mcpStatus)   || "skipped".equals(mcpStatus))
                && ("success".equals(omMcpStatus) || "skipped".equals(omMcpStatus));

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status",       allOk ? "success" : "error");
        result.put("modelStatus",  modelStatus);
        result.put("modelMessage", modelMessage);
        result.put("mcpStatus",    mcpStatus);
        result.put("mcpMessage",   mcpMessage);
        result.put("omMcpStatus",  omMcpStatus);
        result.put("omMcpMessage", omMcpMessage);
        log.info("[TEST] model={} gp-mcp={} om-mcp={} mode={}", modelStatus, mcpStatus, omMcpStatus, activeMode);
        return ResponseEntity.ok(result);
    }

    // -------------------------------------------------------------------------
    // Test CF-bound connectivity (uses VCAP credentials, no request body needed)
    // -------------------------------------------------------------------------

    @GetMapping("/test/cf")
    ResponseEntity<Map<String, Object>> testCfConnection() {
        if (!vcapConfig.isCfMode()) {
            return ResponseEntity.ok(Map.of("status", "error",
                    "message", "Not running in CF mode — no VCAP_SERVICES bound"));
        }
        Map<String, String> model = vcapConfig.getModelConfig();
        Map<String, String> mcp   = vcapConfig.getMcpConfig();

        String provider   = model.getOrDefault("provider",   "openai");
        String modelName  = model.getOrDefault("modelName",  "");
        String apiKey     = model.getOrDefault("apiKey",     "");
        String baseUrl    = model.getOrDefault("baseUrl",    "");
        String mcpUrl     = mcp.getOrDefault("mcpUrl",     "");
        String mcpAuth    = mcp.getOrDefault("mcpAuth",    "");
        String activeMode = mcp.getOrDefault("activeMode", "greenplum");
        String omMcpUrl   = mcp.getOrDefault("omMcpUrl",   "");
        String omMcpAuth  = mcp.getOrDefault("omMcpAuth",  "");

        String modelStatus, modelMessage;
        try {
            ChatLanguageModel m = buildModel(provider, modelName, apiKey, baseUrl, 90);
            String resp  = m.generate("Respond with the exact word: OK");
            modelStatus  = "success";
            modelMessage = "Connected — " + resp.trim();
        } catch (Exception e) {
            modelStatus  = "error";
            modelMessage = e.getMessage();
        }

        String mcpStatus = "skipped", mcpMessage = "";
        if (!"openmetadata".equals(activeMode)) {
            Map<String, String> r = GreenplumMcpTools.testConnection(mcpUrl, mcpAuth);
            mcpStatus  = r.get("status");
            mcpMessage = r.get("message");
        }
        String omMcpStatus = "skipped", omMcpMessage = "";
        if (!"greenplum".equals(activeMode)) {
            Map<String, String> r = OpenMetadataMcpTools.testConnection(omMcpUrl, omMcpAuth);
            omMcpStatus  = r.get("status");
            omMcpMessage = r.get("message");
        }

        boolean allOk = "success".equals(modelStatus)
                && ("success".equals(mcpStatus)   || "skipped".equals(mcpStatus))
                && ("success".equals(omMcpStatus) || "skipped".equals(omMcpStatus));

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status",       allOk ? "success" : "error");
        result.put("modelStatus",  modelStatus);
        result.put("modelMessage", modelMessage);
        result.put("mcpStatus",    mcpStatus);
        result.put("mcpMessage",   mcpMessage);
        result.put("omMcpStatus",  omMcpStatus);
        result.put("omMcpMessage", omMcpMessage);
        log.info("[CF-TEST] model={} gp-mcp={} om-mcp={}", modelStatus, mcpStatus, omMcpStatus);
        return ResponseEntity.ok(result);
    }

    // -------------------------------------------------------------------------
    // Clear session memory (single session or all)
    // -------------------------------------------------------------------------

    @PostMapping("/memory/clear")
    ResponseEntity<Map<String, Object>> clearMemory(@RequestBody Map<String, String> request) {
        String userId    = request.getOrDefault("userId",    "").trim();
        String sessionId = request.getOrDefault("sessionId", "").trim();
        if (userId.isEmpty()) {
            return ResponseEntity.badRequest()
                    .body(Map.of("success", false, "error", "userId required"));
        }
        try {
            if (!sessionId.isEmpty()) {
                File f = new File(GreenplumAgentApplication.resolveDataDir()
                        + File.separator + "users" + File.separator + userId
                        + File.separator + "memory" + File.separator + sessionId + ".json");
                if (f.exists()) f.delete();
                log.info("[MEMORY] Cleared session {} for user {}", sessionId, userId);
            } else {
                File dir = new File(GreenplumAgentApplication.resolveDataDir()
                        + File.separator + "users" + File.separator + userId
                        + File.separator + "memory");
                deleteDirectory(dir);
                log.info("[MEMORY] Cleared all memory for user {}", userId);
            }
            return ResponseEntity.ok(Map.of("success", true));
        } catch (Exception e) {
            log.error("[MEMORY] Clear failed for {}: {}", userId, e.getMessage());
            return ResponseEntity.internalServerError()
                    .body(Map.of("success", false, "error", e.getMessage()));
        }
    }

    // -------------------------------------------------------------------------
    // Delete all user data
    // -------------------------------------------------------------------------

    @PostMapping("/data/clear")
    ResponseEntity<Map<String, Object>> clearAllData(@RequestBody Map<String, String> request) {
        String userId = request.getOrDefault("userId", "");
        if (userId.trim().isEmpty()) {
            return ResponseEntity.badRequest()
                    .body(Map.of("success", false, "error", "userId is required"));
        }
        try {
            File userDir = new File(GreenplumAgentApplication.resolveDataDir()
                    + File.separator + "users" + File.separator + userId);
            deleteDirectory(userDir);
            agentCache.remove(userId);
            configHashCache.remove(userId);
            log.info("[DATA] Cleared all data for user {}", userId);
            return ResponseEntity.ok(Map.of("success", true));
        } catch (Exception e) {
            log.error("[DATA] Clear failed for user {}: {}", userId, e.getMessage());
            return ResponseEntity.internalServerError()
                    .body(Map.of("success", false, "error", e.getMessage()));
        }
    }

    // -------------------------------------------------------------------------
    // Admin — global pre-training prompt
    // -------------------------------------------------------------------------

    @PostMapping("/admin/verify")
    ResponseEntity<Map<String, Object>> adminVerify(@RequestBody Map<String, String> request) {
        String pinHash = request.getOrDefault("pinHash", "").trim();
        if (!adminPinHash.equals(pinHash)) {
            log.warn("[ADMIN] Failed admin PIN attempt");
            return ResponseEntity.ok(Map.of("success", false, "error", "Incorrect admin PIN."));
        }
        String globalPrompt = loadGlobalPrompt();
        log.info("[ADMIN] Admin authenticated");
        return ResponseEntity.ok(Map.of("success", true, "globalPrompt", globalPrompt));
    }

    @PostMapping("/admin/save")
    ResponseEntity<Map<String, Object>> adminSave(@RequestBody Map<String, String> request) {
        String pinHash = request.getOrDefault("pinHash", "").trim();
        String prompt  = request.getOrDefault("prompt",  "").trim();
        if (!adminPinHash.equals(pinHash)) {
            return ResponseEntity.ok(Map.of("success", false, "error", "Incorrect admin PIN."));
        }
        try {
            Files.writeString(getGlobalPromptFile().toPath(), prompt, StandardCharsets.UTF_8);
            log.info("[ADMIN] Global prompt updated ({} chars)", prompt.length());
            return ResponseEntity.ok(Map.of("success", true));
        } catch (Exception e) {
            log.error("[ADMIN] Save failed: {}", e.getMessage());
            return ResponseEntity.internalServerError().body(Map.of("success", false, "error", e.getMessage()));
        }
    }

    // -------------------------------------------------------------------------
    // Admin — allowed-users.txt (SSO access control)
    // -------------------------------------------------------------------------

    @GetMapping("/admin/allowlist")
    ResponseEntity<Map<String, Object>> loadAllowlist(@RequestParam String pinHash) {
        if (!adminPinHash.equals(pinHash.trim())) {
            return ResponseEntity.ok(Map.of("success", false, "error", "Incorrect admin PIN."));
        }
        try {
            File f = getAllowlistFile();
            String content = f.exists() ? Files.readString(f.toPath(), StandardCharsets.UTF_8) : "";
            return ResponseEntity.ok(Map.of("success", true, "allowlist", content));
        } catch (Exception e) {
            log.error("[ADMIN] Allowlist load failed: {}", e.getMessage());
            return ResponseEntity.internalServerError().body(Map.of("success", false, "error", e.getMessage()));
        }
    }

    @PostMapping("/admin/allowlist")
    ResponseEntity<Map<String, Object>> saveAllowlist(@RequestBody Map<String, String> request) {
        String pinHash  = request.getOrDefault("pinHash",   "").trim();
        String content  = request.getOrDefault("allowlist", "");
        if (!adminPinHash.equals(pinHash)) {
            return ResponseEntity.ok(Map.of("success", false, "error", "Incorrect admin PIN."));
        }
        try {
            Files.writeString(getAllowlistFile().toPath(), content, StandardCharsets.UTF_8);
            log.info("[ADMIN] Allowlist updated");
            return ResponseEntity.ok(Map.of("success", true));
        } catch (Exception e) {
            log.error("[ADMIN] Allowlist save failed: {}", e.getMessage());
            return ResponseEntity.internalServerError().body(Map.of("success", false, "error", e.getMessage()));
        }
    }

    @GetMapping("/admin/known-users")
    ResponseEntity<Map<String, Object>> knownUsers(@RequestParam String pinHash) {
        if (!adminPinHash.equals(pinHash.trim())) {
            return ResponseEntity.ok(Map.of("success", false, "error", "Incorrect admin PIN."));
        }
        try {
            File f = new File(GreenplumAgentApplication.resolveDataDir(), "known-users.txt");
            List<String> users = f.exists()
                ? Files.readAllLines(f.toPath(), StandardCharsets.UTF_8).stream()
                    .map(String::trim).filter(l -> !l.isEmpty() && !l.startsWith("#"))
                    .distinct().sorted().collect(java.util.stream.Collectors.toList())
                : java.util.List.of();
            return ResponseEntity.ok(Map.of("success", true, "users", users));
        } catch (Exception e) {
            log.error("[ADMIN] Known users load failed: {}", e.getMessage());
            return ResponseEntity.internalServerError().body(Map.of("success", false, "error", e.getMessage()));
        }
    }

    /**
     * Live user-directory search via UAA SCIM API.
     * Tries client_credentials token, then queries /Users?filter=...
     * Returns empty list gracefully if scope is unavailable or UAA is unreachable.
     */
    @GetMapping("/admin/search-users")
    ResponseEntity<Map<String, Object>> searchUsers(
            @RequestParam String q,
            @RequestParam String pinHash) {
        if (!adminPinHash.equals(pinHash.trim())) {
            return ResponseEntity.ok(Map.of("success", false, "error", "Incorrect admin PIN."));
        }
        String query = q == null ? "" : q.trim().toLowerCase();
        if (query.length() < 2) {
            return ResponseEntity.ok(Map.of("success", true, "users", List.of()));
        }
        try {
            List<String> found = scimSearch(query);
            return ResponseEntity.ok(Map.of("success", true, "users", found));
        } catch (Exception e) {
            log.debug("[ADMIN] SCIM search failed for '{}': {}", query, e.getMessage());
            return ResponseEntity.ok(Map.of("success", true, "users", List.of()));
        }
    }

    private List<String> scimSearch(String q) throws Exception {
        String vcap = System.getenv("VCAP_SERVICES");
        if (vcap == null || vcap.isBlank()) return List.of();

        com.fasterxml.jackson.databind.JsonNode root = OBJECT_MAPPER.readTree(vcap);
        String authDomain = "", clientId = "", clientSecret = "";
        outer:
        for (com.fasterxml.jackson.databind.JsonNode services : root) {
            for (com.fasterxml.jackson.databind.JsonNode svc : services) {
                if ("p-identity".equals(svc.path("label").asText())) {
                    com.fasterxml.jackson.databind.JsonNode creds = svc.path("credentials");
                    for (String key : new String[]{"auth_domain", "auth-domain"}) {
                        String v = creds.path(key).asText("").trim();
                        if (!v.isEmpty()) { authDomain = v; break; }
                    }
                    for (String key : new String[]{"client_id", "client-id"}) {
                        String v = creds.path(key).asText("").trim();
                        if (!v.isEmpty()) { clientId = v; break; }
                    }
                    for (String key : new String[]{"client_secret", "client-secret"}) {
                        String v = creds.path(key).asText("").trim();
                        if (!v.isEmpty()) { clientSecret = v; break; }
                    }
                    break outer;
                }
            }
        }
        if (authDomain.isEmpty() || clientId.isEmpty() || clientSecret.isEmpty()) return List.of();

        java.net.http.HttpClient http = java.net.http.HttpClient.newBuilder()
                .connectTimeout(java.time.Duration.ofSeconds(5)).build();

        // Step 1 — client_credentials token
        String tokenBody = "grant_type=client_credentials"
                + "&client_id="     + java.net.URLEncoder.encode(clientId,     "UTF-8")
                + "&client_secret=" + java.net.URLEncoder.encode(clientSecret, "UTF-8");
        java.net.http.HttpRequest tokenReq = java.net.http.HttpRequest.newBuilder()
                .uri(java.net.URI.create(authDomain + "/oauth/token"))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .timeout(java.time.Duration.ofSeconds(5))
                .POST(java.net.http.HttpRequest.BodyPublishers.ofString(tokenBody))
                .build();
        java.net.http.HttpResponse<String> tokenRes = http.send(tokenReq,
                java.net.http.HttpResponse.BodyHandlers.ofString());
        if (tokenRes.statusCode() != 200) return List.of();
        String accessToken = OBJECT_MAPPER.readTree(tokenRes.body()).path("access_token").asText("").trim();
        if (accessToken.isEmpty()) return List.of();

        // Step 2 — SCIM search: match by email prefix OR userName prefix
        String filter  = "email sw \"" + q + "\" or userName sw \"" + q + "\"";
        String scimUrl = authDomain + "/Users?count=15&attributes=emails,userName"
                + "&filter=" + java.net.URLEncoder.encode(filter, "UTF-8");
        java.net.http.HttpRequest scimReq = java.net.http.HttpRequest.newBuilder()
                .uri(java.net.URI.create(scimUrl))
                .header("Authorization", "Bearer " + accessToken)
                .header("Accept", "application/json")
                .timeout(java.time.Duration.ofSeconds(8))
                .GET().build();
        java.net.http.HttpResponse<String> scimRes = http.send(scimReq,
                java.net.http.HttpResponse.BodyHandlers.ofString());
        if (scimRes.statusCode() != 200) return List.of();

        // Step 3 — parse Resources array (UAA uses capital-R "Resources")
        com.fasterxml.jackson.databind.JsonNode body = OBJECT_MAPPER.readTree(scimRes.body());
        com.fasterxml.jackson.databind.JsonNode resources = body.path("Resources");
        if (!resources.isArray() || resources.isEmpty()) resources = body.path("resources");

        List<String> result = new ArrayList<>();
        for (com.fasterxml.jackson.databind.JsonNode r : resources) {
            com.fasterxml.jackson.databind.JsonNode emailsNode = r.path("emails");
            if (emailsNode.isArray()) {
                for (com.fasterxml.jackson.databind.JsonNode e : emailsNode) {
                    String val = e.path("value").asText("").trim().toLowerCase();
                    if (!val.isEmpty() && !result.contains(val)) result.add(val);
                }
            }
            if (result.isEmpty()) {
                String un = r.path("userName").asText("").trim().toLowerCase();
                if (!un.isEmpty() && !result.contains(un)) result.add(un);
            }
        }
        return result;
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    private File getGlobalPromptFile() {
        return new File(GreenplumAgentApplication.resolveDataDir(), "global-prompt.txt");
    }

    private File getAllowlistFile() {
        return new File(GreenplumAgentApplication.resolveDataDir(), "allowed-users.txt");
    }

    private String loadGlobalPrompt() {
        try {
            File gf = getGlobalPromptFile();
            return gf.exists() ? Files.readString(gf.toPath(), StandardCharsets.UTF_8).trim() : "";
        } catch (Exception e) {
            log.warn("[ADMIN] Could not read global prompt: {}", e.getMessage());
            return "";
        }
    }

    private File getConfigFile(String userId) {
        File dir = new File(GreenplumAgentApplication.resolveDataDir()
                + File.separator + "users" + File.separator + userId);
        dir.mkdirs();
        return new File(dir, "config.json");
    }

    @SuppressWarnings("unchecked")
    private Map<String, String> loadOrSeedConfig(String userId, Map<String, String> fallback) {
        File f = getConfigFile(userId);
        if (f.exists()) {
            try {
                return OBJECT_MAPPER.readValue(
                        Files.readString(f.toPath(), StandardCharsets.UTF_8), LinkedHashMap.class);
            } catch (Exception e) {
                log.warn("[CONFIG] Cannot read config for {}: {}", userId, e.getMessage());
            }
        }
        // Cloud Foundry restart recovery: browser sends config in every request
        if (fallback != null && !fallback.isEmpty()) {
            try {
                Files.writeString(f.toPath(),
                        OBJECT_MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(fallback),
                        StandardCharsets.UTF_8);
                log.info("[CONFIG] Re-seeded config for user {} from browser payload", userId);
            } catch (Exception e) {
                log.warn("[CONFIG] Cannot seed config for {}: {}", userId, e.getMessage());
            }
            return fallback;
        }
        return new LinkedHashMap<>();
    }

    private java.util.function.BiFunction<String, String, String> getOrBuildChatFn(
            String userId, String provider, String modelName,
            String apiKey, String baseUrl,
            String mcpUrl, String mcpAuth,
            String activeMode, String omMcpUrl, String omMcpAuth) {

        String hash = provider + "|" + modelName + "|" + apiKey + "|" + baseUrl + "|"
                    + mcpUrl + "|" + mcpAuth + "|" + activeMode + "|" + omMcpUrl + "|" + omMcpAuth;

        if (!hash.equals(configHashCache.get(userId))) {
            ChatLanguageModel model = buildModel(provider, modelName, apiKey, baseUrl, 600);

            java.util.function.BiFunction<String, String, String> fn;

            if ("openmetadata".equals(activeMode)) {
                OpenMetadataMcpTools omTools = new OpenMetadataMcpTools(omMcpUrl, omMcpAuth);
                OpenMetadataAgent agent = AiServices.builder(OpenMetadataAgent.class)
                        .chatLanguageModel(model)
                        .chatMemoryProvider(memoryProvider)
                        .tools(omTools)
                        .build();
                fn = agent::chat;
            } else if ("both".equals(activeMode)) {
                GreenplumMcpTools    gpTools = new GreenplumMcpTools(mcpUrl, mcpAuth);
                OpenMetadataMcpTools omTools = new OpenMetadataMcpTools(omMcpUrl, omMcpAuth);
                GreenplumAgent agent = AiServices.builder(GreenplumAgent.class)
                        .chatLanguageModel(model)
                        .chatMemoryProvider(memoryProvider)
                        .tools(gpTools, omTools)
                        .build();
                fn = agent::chat;
            } else {
                GreenplumMcpTools gpTools = new GreenplumMcpTools(mcpUrl, mcpAuth);
                GreenplumAgent agent = AiServices.builder(GreenplumAgent.class)
                        .chatLanguageModel(model)
                        .chatMemoryProvider(memoryProvider)
                        .tools(gpTools)
                        .build();
                fn = agent::chat;
            }

            agentCache.put(userId, fn);
            configHashCache.put(userId, hash);
            log.info("[AGENT] Built new agent for user {} (mode={} provider={} model={})",
                    userId, activeMode, provider, modelName);
        }
        return agentCache.get(userId);
    }

    private ChatLanguageModel buildModel(String provider, String modelName,
                                          String apiKey, String baseUrl, int timeoutSeconds) {
        Duration timeout = Duration.ofSeconds(timeoutSeconds);
        switch (provider) {
            case "openai": {
                var b = OpenAiChatModel.builder()
                        .apiKey(apiKey).modelName(modelName).temperature(0.0)
                        .timeout(timeout).maxRetries(1);
                if (baseUrl != null && !baseUrl.trim().isEmpty()) b.baseUrl(baseUrl);
                return b.build();
            }
            case "anthropic": {
                var b = AnthropicChatModel.builder()
                        .apiKey(apiKey).modelName(modelName).temperature(0.0)
                        .timeout(timeout).maxRetries(1);
                if (baseUrl != null && !baseUrl.trim().isEmpty()) b.baseUrl(baseUrl);
                return b.build();
            }
            default: { // ollama
                String url = (baseUrl != null && !baseUrl.trim().isEmpty())
                        ? baseUrl : "http://localhost:11434";
                return OllamaChatModel.builder()
                        .baseUrl(url).modelName(modelName).temperature(0.0)
                        .timeout(timeout).maxRetries(1)
                        .build();
            }
        }
    }

    // Strips thinking/reasoning blocks produced by models like Qwen3, DeepSeek-R1, Llama-thinking.
    // Also removes invalid control chars, Unicode line terminators, and lone surrogates that
    // break Jackson serialization or browser JSON.parse/JSON.stringify.
    private static final java.util.regex.Pattern THINKING_BLOCK =
        java.util.regex.Pattern.compile(
            "<(think|thinking|reasoning|reflection)>[\\s\\S]*?</(think|thinking|reasoning|reflection)>\\s*",
            java.util.regex.Pattern.CASE_INSENSITIVE);

    private static String sanitizeResponse(String response) {
        if (response == null) return "";

        // 1. Strip ASCII control chars invalid in JSON (keep tab=\x09, LF=\x0A, CR=\x0D)
        response = response.replaceAll("[\\x00-\\x08\\x0B\\x0C\\x0E-\\x1F]", "");

        // 2. Replace Unicode line/paragraph separators with newline.
        //    Jackson emits them as raw bytes; some JS engines treat them as line terminators,
        //    silently breaking JSON.parse() in the browser.
        response = response.replace(" ", "\n").replace(" ", "\n");

        // 3. Remove lone Unicode surrogates — Jackson throws mid-stream when writing them.
        response = stripLoneSurrogates(response);

        // 4. Try stripping thinking blocks entirely
        String stripped = THINKING_BLOCK.matcher(response).replaceAll("").trim();

        // Use the stripped version when it still contains real content (SQL, table, or long text).
        // If the model buried its SQL/data INSIDE <think>, the stripped text would be too short —
        // fall back to keeping the content but removing only the wrapper tags.
        boolean hasSQL   = stripped.contains("```sql") || stripped.contains("```SQL");
        boolean hasTable = stripped.contains("| ");
        boolean isLong   = stripped.length() > 200;
        if (hasSQL || hasTable || isLong) return stripped;

        // Fallback: strip only wrapper tags, keep inner content
        return response
            .replaceAll("(?si)<(think|thinking|reasoning|reflection)>\\s*", "")
            .replaceAll("(?si)</(think|thinking|reasoning|reflection)>\\s*", "")
            .trim();
    }

    private static String stripLoneSurrogates(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (Character.isHighSurrogate(c)) {
                if (i + 1 < s.length() && Character.isLowSurrogate(s.charAt(i + 1))) {
                    sb.append(c);               // high surrogate of valid pair
                    sb.append(s.charAt(i + 1)); // low surrogate of valid pair
                    i++;                        // skip low surrogate on next iteration
                }
                // else: lone high surrogate — drop it
            } else if (Character.isLowSurrogate(c)) {
                // lone low surrogate (no preceding high) — drop it
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    // -------------------------------------------------------------------------
    // Sessions — chat history persisted on server filesystem per user
    // -------------------------------------------------------------------------

    @GetMapping("/sessions/load")
    ResponseEntity<Map<String, Object>> loadSessions(@RequestParam String userId) {
        if (userId == null || userId.trim().isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("success", false, "error", "userId required"));
        }
        try {
            File sessionsFile = getSessionsFile(userId.trim());
            if (!sessionsFile.exists()) {
                return ResponseEntity.ok(Map.of("success", false));
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> data = OBJECT_MAPPER.readValue(
                    Files.readString(sessionsFile.toPath(), StandardCharsets.UTF_8), Map.class);
            data.put("success", true);
            return ResponseEntity.ok(data);
        } catch (Exception e) {
            log.error("[SESSIONS] Load failed for {}: {}", userId, e.getMessage());
            return ResponseEntity.internalServerError().body(Map.of("success", false, "error", e.getMessage()));
        }
    }

    @PostMapping("/sessions/save")
    ResponseEntity<Map<String, Object>> saveSessions(@RequestBody Map<String, Object> request) {
        String userId = (String) request.getOrDefault("userId", "");
        if (userId.trim().isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("success", false, "error", "userId required"));
        }
        try {
            Map<String, Object> data = new LinkedHashMap<>(request);
            data.remove("userId");
            Files.writeString(getSessionsFile(userId.trim()).toPath(),
                    OBJECT_MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(data),
                    StandardCharsets.UTF_8);
            log.debug("[SESSIONS] Saved for user {}", userId);
            return ResponseEntity.ok(Map.of("success", true));
        } catch (Exception e) {
            log.error("[SESSIONS] Save failed for {}: {}", userId, e.getMessage());
            return ResponseEntity.internalServerError().body(Map.of("success", false, "error", e.getMessage()));
        }
    }

    private File getSessionsFile(String userId) {
        File dir = new File(GreenplumAgentApplication.resolveDataDir()
                + File.separator + "users" + File.separator + userId);
        dir.mkdirs();
        return new File(dir, "sessions.json");
    }

    // -------------------------------------------------------------------------
    // Favourites — saved prompts per user
    // -------------------------------------------------------------------------

    @PostMapping("/favourites/list")
    ResponseEntity<Map<String, Object>> listFavourites(@RequestBody Map<String, String> request) {
        String userId = request.getOrDefault("userId", "").trim();
        if (userId.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("success", false, "error", "userId required"));
        }
        try {
            List<Map<String, String>> favs = loadFavourites(userId);
            return ResponseEntity.ok(Map.of("success", true, "favourites", favs));
        } catch (Exception e) {
            log.error("[FAV] List failed for {}: {}", userId, e.getMessage());
            return ResponseEntity.internalServerError().body(Map.of("success", false, "error", e.getMessage()));
        }
    }

    @PostMapping("/favourites/save")
    ResponseEntity<Map<String, Object>> saveFavourite(@RequestBody Map<String, String> request) {
        String userId = request.getOrDefault("userId", "").trim();
        String id     = request.getOrDefault("id",     "").trim();
        String label  = request.getOrDefault("label",  "").trim();
        String prompt = request.getOrDefault("prompt", "").trim();

        if (userId.isEmpty() || prompt.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("success", false, "error", "userId and prompt required"));
        }
        if (label.isEmpty()) label = prompt.length() > 50 ? prompt.substring(0, 50) + "..." : prompt;
        if (id.isEmpty())    id    = "fav-" + System.currentTimeMillis();

        try {
            List<Map<String, String>> favs = loadFavourites(userId);
            String finalId    = id;
            String finalLabel = label;
            boolean updated   = false;

            for (int i = 0; i < favs.size(); i++) {
                if (finalId.equals(favs.get(i).get("id"))) {
                    Map<String, String> upd = new LinkedHashMap<>(favs.get(i));
                    upd.put("label", finalLabel);
                    favs.set(i, upd);
                    updated = true;
                    break;
                }
            }
            if (!updated) {
                Map<String, String> newFav = new LinkedHashMap<>();
                newFav.put("id",        finalId);
                newFav.put("label",     finalLabel);
                newFav.put("prompt",    prompt);
                newFav.put("createdAt", LocalDateTime.now().toString());
                favs.add(0, newFav);
            }
            persistFavourites(userId, favs);
            log.info("[FAV] Saved '{}' for user {}", finalLabel, userId);
            return ResponseEntity.ok(Map.of("success", true, "id", finalId));
        } catch (Exception e) {
            log.error("[FAV] Save failed for {}: {}", userId, e.getMessage());
            return ResponseEntity.internalServerError().body(Map.of("success", false, "error", e.getMessage()));
        }
    }

    @PostMapping("/favourites/delete")
    ResponseEntity<Map<String, Object>> deleteFavourite(@RequestBody Map<String, String> request) {
        String userId = request.getOrDefault("userId", "").trim();
        String id     = request.getOrDefault("id",     "").trim();
        if (userId.isEmpty() || id.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("success", false, "error", "userId and id required"));
        }
        try {
            List<Map<String, String>> favs = loadFavourites(userId);
            favs.removeIf(f -> id.equals(f.get("id")));
            persistFavourites(userId, favs);
            log.info("[FAV] Deleted {} for user {}", id, userId);
            return ResponseEntity.ok(Map.of("success", true));
        } catch (Exception e) {
            log.error("[FAV] Delete failed for {}: {}", userId, e.getMessage());
            return ResponseEntity.internalServerError().body(Map.of("success", false, "error", e.getMessage()));
        }
    }

    private File getFavouritesFile(String userId) {
        File dir = new File(GreenplumAgentApplication.resolveDataDir()
                + File.separator + "users" + File.separator + userId);
        dir.mkdirs();
        return new File(dir, "favourites.json");
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, String>> loadFavourites(String userId) {
        File f = getFavouritesFile(userId);
        if (!f.exists()) return new ArrayList<>();
        try {
            return OBJECT_MAPPER.readValue(Files.readString(f.toPath(), StandardCharsets.UTF_8), List.class);
        } catch (Exception e) {
            log.warn("[FAV] Cannot read favourites for {}: {}", userId, e.getMessage());
            return new ArrayList<>();
        }
    }

    private void persistFavourites(String userId, List<Map<String, String>> favs) throws Exception {
        Files.writeString(getFavouritesFile(userId).toPath(),
                OBJECT_MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(favs),
                StandardCharsets.UTF_8);
    }

    private static boolean isCausedByTimeout(Throwable t) {
        while (t != null) {
            if (t instanceof java.net.SocketTimeoutException) return true;
            t = t.getCause();
        }
        return false;
    }

    private void deleteDirectory(File dir) {
        if (dir == null || !dir.exists()) return;
        File[] files = dir.listFiles();
        if (files != null) {
            for (File f : files) {
                if (f.isDirectory()) deleteDirectory(f);
                else f.delete();
            }
        }
        dir.delete();
    }
}
