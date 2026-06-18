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
import org.springframework.web.bind.annotation.*;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@RestController
@RequestMapping("/api")
public class ChatController {

    private static final Logger log = LoggerFactory.getLogger(ChatController.class);
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final int MAX_PROMPT_LENGTH = 4000;

    private final ChatMemoryProvider memoryProvider;

    // Agent cache — keyed by userId, rebuilt only when config changes
    private final ConcurrentHashMap<String, GreenplumAgent> agentCache = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, String>         configHashCache = new ConcurrentHashMap<>();

    // Admin PIN — set via admin.pin in application.yml (overridable via ADMIN_PIN env var)
    @Value("${admin.pin}")
    private String adminPinRaw;
    private String adminPinHash; // SHA-256 of adminPinRaw, computed at startup

    // No in-memory cache for global prompt — always read from disk so updates
    // apply immediately to every user and session without any restart.

    public ChatController(ChatMemoryProvider memoryProvider) {
        this.memoryProvider = memoryProvider;
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
    // PIN auth — setup (save hash) and verify (recover after cache clear)
    // -------------------------------------------------------------------------

    @PostMapping("/auth/setup")
    ResponseEntity<Map<String, Object>> authSetup(@RequestBody Map<String, String> request) {
        String userId  = request.getOrDefault("userId",  "").trim();
        String pinHash = request.getOrDefault("pinHash", "").trim();
        String pinHint = request.getOrDefault("pinHint", "").trim();

        if (userId.isEmpty() || !userId.matches("[a-zA-Z0-9_-]{3,50}")) {
            return ResponseEntity.badRequest().body(Map.of("success", false,
                    "error", "Username must be 3–50 characters (letters, numbers, - or _)."));
        }
        if (pinHash.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("success", false, "error", "PIN hash required."));
        }
        try {
            File configFile = getConfigFile(userId);
            Map<String, String> config = configFile.exists()
                    ? loadOrSeedConfig(userId, null) : new LinkedHashMap<>();
            config.put("pinHash", pinHash);
            if (!pinHint.isEmpty()) config.put("pinHint", pinHint);
            Files.writeString(configFile.toPath(),
                    OBJECT_MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(config),
                    StandardCharsets.UTF_8);
            log.info("[AUTH] PIN saved for user {}", userId);
            return ResponseEntity.ok(Map.of("success", true));
        } catch (Exception e) {
            log.error("[AUTH] PIN setup failed for {}: {}", userId, e.getMessage());
            return ResponseEntity.internalServerError().body(Map.of("success", false, "error", e.getMessage()));
        }
    }

    @PostMapping("/auth/verify")
    ResponseEntity<Map<String, Object>> authVerify(@RequestBody Map<String, String> request) {
        String userId  = request.getOrDefault("userId",  "").trim();
        String pinHash = request.getOrDefault("pinHash", "").trim();

        if (userId.isEmpty() || pinHash.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("success", false, "error", "userId and pinHash required."));
        }
        try {
            File configFile = getConfigFile(userId);
            if (!configFile.exists()) {
                return ResponseEntity.ok(Map.of("success", false, "error", "Account not found. Check your username."));
            }
            Map<String, String> config = loadOrSeedConfig(userId, null);
            String stored = config.getOrDefault("pinHash", "");
            if (stored.isEmpty()) {
                return ResponseEntity.ok(Map.of("success", false, "error", "No PIN registered for this account."));
            }
            if (pinHash.equals(stored)) {
                log.info("[AUTH] Verified login for user {}", userId);
                return ResponseEntity.ok(Map.of("success", true, "userId", userId));
            }
            return ResponseEntity.ok(Map.of("success", false, "error", "Incorrect PIN."));
        } catch (Exception e) {
            log.error("[AUTH] Verify failed for {}: {}", userId, e.getMessage());
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
            Map<String, String> data = new LinkedHashMap<>(req);
            data.remove("userId");
            String json = OBJECT_MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(data);
            Files.writeString(getConfigFile(userId).toPath(), json, StandardCharsets.UTF_8);
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

        String modelName = config.getOrDefault("modelName", "").trim();
        if (modelName.isEmpty()) {
            return ResponseEntity.ok(Map.of("response",
                    "⚠️ **Configuration Required:** Please upload your credential file and configure an AI Provider before chatting."));
        }

        try {
            String provider  = config.getOrDefault("provider",      "ollama").toLowerCase();
            String apiKey    = config.getOrDefault("apiKey",         "");
            String baseUrl   = config.getOrDefault("baseUrl",        "");
            String mcpUrl    = config.getOrDefault("mcpUrl",         "");
            String mcpAuth   = config.getOrDefault("mcpAuth",        "");
            String sysPrompt = config.getOrDefault("systemPrompt",   "");

            GreenplumAgent agent = getOrBuildAgent(
                    userId, provider, modelName, apiKey, baseUrl, mcpUrl, mcpAuth);

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
            String finalPrompt = promptBuilder.toString();

            String raw      = agent.chat(memoryId, finalPrompt);
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
        String mcpUrl    = request.getOrDefault("mcpUrl",    "");
        String mcpAuth   = request.getOrDefault("mcpAuth",   "");

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

        // Test MCP server
        Map<String, String> mcpResult = GreenplumMcpTools.testConnection(mcpUrl, mcpAuth);
        String mcpStatus  = mcpResult.get("status");
        String mcpMessage = mcpResult.get("message");

        boolean allOk = "success".equals(modelStatus)
                && ("success".equals(mcpStatus) || "skipped".equals(mcpStatus));

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status",       allOk ? "success" : "error");
        result.put("modelStatus",  modelStatus);
        result.put("modelMessage", modelMessage);
        result.put("mcpStatus",    mcpStatus);
        result.put("mcpMessage",   mcpMessage);
        log.info("[TEST] model={} mcp={} provider={}", modelStatus, mcpStatus, provider);
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
    // Private helpers
    // -------------------------------------------------------------------------

    private File getGlobalPromptFile() {
        return new File(GreenplumAgentApplication.resolveDataDir(), "global-prompt.txt");
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

    private GreenplumAgent getOrBuildAgent(String userId, String provider, String modelName,
                                            String apiKey, String baseUrl,
                                            String mcpUrl, String mcpAuth) {
        String hash = provider + "|" + modelName + "|" + apiKey + "|" + baseUrl + "|" + mcpUrl + "|" + mcpAuth;
        if (!hash.equals(configHashCache.get(userId))) {
            ChatLanguageModel   model    = buildModel(provider, modelName, apiKey, baseUrl, 600);
            GreenplumMcpTools   mcpTools = new GreenplumMcpTools(mcpUrl, mcpAuth);
            GreenplumAgent      agent    = AiServices.builder(GreenplumAgent.class)
                    .chatLanguageModel(model)
                    .chatMemoryProvider(memoryProvider)
                    .tools(mcpTools)
                    .build();
            agentCache.put(userId, agent);
            configHashCache.put(userId, hash);
            log.info("[AGENT] Built new agent for user {} (provider={} model={})", userId, provider, modelName);
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
