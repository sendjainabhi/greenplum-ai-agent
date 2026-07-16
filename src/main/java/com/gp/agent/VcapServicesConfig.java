package com.gp.agent;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.*;

/**
 * Reads CF VCAP_SERVICES at startup and exposes bound AI model + MCP credentials.
 *
 * ─── Supported AI Service Bindings ───────────────────────────────────────────
 * 1. Tanzu GenAI marketplace   label="ai-models" OR tags contain "genai"
 *    v2 (nested):   credentials.endpoint.{openai_api_base,api_key,model_id,...}
 *    v1 (flat):     credentials.{api_url,access_token,model_id,...}
 *
 * 2. User-provided AI service  name="gp-ai-model" OR has provider/modelName field
 *    Supports provider values: openai | anthropic | ollama
 *    Fields: provider, baseUrl/api_url/url, apiKey/api_key/access_token,
 *            modelName/model_name/model
 *
 * ─── Supported MCP Service Bindings ──────────────────────────────────────────
 * 3. Greenplum MCP             name="gp-mcp-greenplum" OR type="greenplum"
 *    Fields: url/mcpUrl, auth/mcpAuth
 *
 * 4. OpenMetadata MCP          name="gp-mcp-openmetadata" OR type="openmetadata"
 *    Fields: url/omMcpUrl, auth/omMcpAuth
 *
 * Both MCP services can be bound simultaneously (mode = "both").
 *
 * ─── CredHub Fallback ────────────────────────────────────────────────────────
 * When the Java buildpack resolves CredHub references at container startup, it
 * may expose credentials via Spring Environment (vcap.services.<name>.credentials.*)
 * populated by java-cfenv-boot. This class falls back to that path when the raw
 * VCAP_SERVICES env var still contains only a credhub-ref placeholder.
 *
 * When VCAP_SERVICES is absent or empty, the app runs in normal manual mode
 * (PIN login + Settings button visible).
 */
@Component
public class VcapServicesConfig {

    private static final Logger log = LoggerFactory.getLogger(VcapServicesConfig.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final Environment springEnv;

    public VcapServicesConfig(Environment springEnv) {
        this.springEnv = springEnv;
    }

    private boolean cfMode = false;
    private final Map<String, String> modelConfig = new LinkedHashMap<>();
    private final Map<String, String> mcpConfig   = new LinkedHashMap<>();

    // Service instance names collected during parsing (used for Spring Env fallback)
    private final List<String> aiServiceNames  = new ArrayList<>();
    private final List<String> gpMcpNames      = new ArrayList<>();
    private final List<String> omMcpNames      = new ArrayList<>();

    // ─────────────────────────────────────────────────────────────────────────
    // Startup initialisation
    // ─────────────────────────────────────────────────────────────────────────

    @PostConstruct
    void init() {
        String vcap = System.getenv("VCAP_SERVICES");
        if (vcap == null || vcap.isBlank() || vcap.trim().equals("{}")) {
            log.info("[CF] VCAP_SERVICES not present or empty — running in manual mode");
            return;
        }
        // We are on CF — settings UI must be hidden regardless of what services are bound
        cfMode = true;

        try {
            Map<String, List<Map<String, Object>>> services =
                    MAPPER.readValue(vcap, new TypeReference<>() {});

            for (Map.Entry<String, List<Map<String, Object>>> entry : services.entrySet()) {
                String serviceLabel = entry.getKey();
                for (Map<String, Object> instance : entry.getValue()) {
                    processServiceInstance(serviceLabel, instance);
                }
            }

            // ── CredHub fallback: if raw VCAP had only a credhub-ref (empty creds),
            //    try Spring Environment which java-cfenv-boot may have resolved.
            if (!aiServiceNames.isEmpty() && modelConfig.getOrDefault("baseUrl", "").isEmpty()) {
                log.info("[CF] No credentials in raw VCAP — trying Spring Env (CredHub fallback)");
                for (String svcName : aiServiceNames) {
                    resolveAiFromSpringEnv(svcName);
                    if (!modelConfig.getOrDefault("baseUrl", "").isEmpty()) break;
                }
            }
            for (String svcName : gpMcpNames) {
                if (mcpConfig.getOrDefault("mcpUrl", "").isEmpty()) resolveGpMcpFromSpringEnv(svcName);
            }
            for (String svcName : omMcpNames) {
                if (mcpConfig.getOrDefault("omMcpUrl", "").isEmpty()) resolveOmMcpFromSpringEnv(svcName);
            }

            if (!modelConfig.isEmpty()) {
                // Auto-discover model name if not present in credentials
                if (modelConfig.getOrDefault("modelName", "").isEmpty()
                        && !modelConfig.getOrDefault("baseUrl", "").isEmpty()) {
                    String discovered = discoverModelName(
                            modelConfig.get("baseUrl"), modelConfig.get("apiKey"));
                    if (!discovered.isEmpty()) modelConfig.put("modelName", discovered);
                }

                boolean hasGp = !mcpConfig.getOrDefault("mcpUrl",   "").isEmpty();
                boolean hasOm = !mcpConfig.getOrDefault("omMcpUrl", "").isEmpty();
                mcpConfig.put("activeMode",
                        hasGp && hasOm ? "both" : hasOm ? "openmetadata" : "greenplum");

                log.info("[CF] CF mode active — provider={} model={} baseUrl={} mcp={}",
                        modelConfig.get("provider"), modelConfig.get("modelName"),
                        modelConfig.get("baseUrl"), mcpConfig.get("activeMode"));
            }

        } catch (Exception e) {
            log.error("[CF] Failed to parse VCAP_SERVICES: {}", e.getMessage());
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Per-instance parsing
    // ─────────────────────────────────────────────────────────────────────────

    private void processServiceInstance(String serviceLabel, Map<String, Object> instance) {
        String name = String.valueOf(instance.getOrDefault("name", ""));

        @SuppressWarnings("unchecked")
        List<String> tags = instance.get("tags") instanceof List
                ? (List<String>) instance.get("tags") : List.of();

        @SuppressWarnings("unchecked")
        Map<String, Object> rawCreds = instance.get("credentials") instanceof Map
                ? (Map<String, Object>) instance.get("credentials") : Map.of();

        // Flatten credential values to strings for simple field lookups
        Map<String, String> flat = flatten(rawCreds);

        // ── 1. Tanzu GenAI (label="ai-models" or tags contain "genai")
        boolean isTanzuGenAi = "ai-models".equals(serviceLabel)
                || tags.stream().anyMatch(t ->
                        t.equalsIgnoreCase("genai") || t.equalsIgnoreCase("ai-models")
                        || t.equalsIgnoreCase("llm") || t.equalsIgnoreCase("tanzu-ai-models"));

        if (isTanzuGenAi && modelConfig.isEmpty()) {
            aiServiceNames.add(name);
            modelConfig.put("provider", "openai"); // Tanzu GenAI proxy is OpenAI-compatible
            extractModelCredentials(rawCreds, flat, name);
            log.info("[CF] Tanzu GenAI service detected: name={} provider=openai url={}",
                    name, modelConfig.get("baseUrl"));
            return;
        }

        // ── 2. User-provided AI service (explicit name or recognised credential fields)
        boolean isExplicitAiService = name.equals("gp-ai-model");
        boolean hasModelFields = flat.containsKey("modelName") || flat.containsKey("model_name")
                || flat.containsKey("model") || flat.containsKey("provider");
        if ((isExplicitAiService || (modelConfig.isEmpty() && hasModelFields))
                && modelConfig.isEmpty()) {
            aiServiceNames.add(name);
            // Detect provider: explicit field or infer from credential shapes
            String provider = pickFirst(flat,
                    "provider", "model_provider", "llm_provider");
            if (provider.isEmpty()) provider = inferProvider(flat);
            modelConfig.put("provider",  provider.isEmpty() ? "openai" : provider);
            extractModelCredentials(rawCreds, flat, name);
            log.info("[CF] User-provided AI model: name={} provider={} url={}",
                    name, modelConfig.get("provider"), modelConfig.get("baseUrl"));
            return;
        }

        // ── 3. Greenplum MCP
        if (name.equals("gp-mcp-greenplum") || "greenplum".equals(flat.get("type"))) {
            gpMcpNames.add(name);
            mcpConfig.put("mcpUrl",  pickFirst(flat, "url", "mcpUrl", "mcp_url", "endpoint"));
            mcpConfig.put("mcpAuth", normalizeAuth(pickFirst(flat, "auth", "mcpAuth", "token", "api_key")));
            log.info("[CF] Greenplum MCP bound: {}", mcpConfig.get("mcpUrl"));
        }

        // ── 4. OpenMetadata MCP
        if (name.equals("gp-mcp-openmetadata") || "openmetadata".equals(flat.get("type"))) {
            omMcpNames.add(name);
            mcpConfig.put("omMcpUrl",  pickFirst(flat, "url", "omMcpUrl", "om_url", "endpoint"));
            mcpConfig.put("omMcpAuth", normalizeAuth(pickFirst(flat, "auth", "omMcpAuth", "token", "api_key")));
            log.info("[CF] OpenMetadata MCP bound: {}", mcpConfig.get("omMcpUrl"));
        }
    }

    /**
     * Extract baseUrl, apiKey, modelName from either a nested "endpoint" map (Tanzu GenAI v2)
     * or flat credential fields (v1 / user-provided).
     */
    private void extractModelCredentials(Map<String, Object> rawCreds,
                                          Map<String, String> flat, String serviceName) {
        if (rawCreds.get("endpoint") instanceof Map) {
            // Tanzu GenAI v2 — credentials.endpoint is a nested object
            @SuppressWarnings("unchecked")
            Map<String, Object> ep = (Map<String, Object>) rawCreds.get("endpoint");
            Map<String, String> epFlat = flatten(ep);

            modelConfig.put("baseUrl", pickFirst(epFlat,
                    "openai_api_base", "api_base", "base_url", "url", "uri"));
            modelConfig.put("apiKey",  pickFirst(epFlat,
                    "api_key", "access_token", "token", "openai_api_key"));
            // model_id / model_name in creds takes priority; leave empty to trigger auto-discovery
            modelConfig.put("modelName", pickFirst(epFlat, "model_id", "model_name", "modelName", "model"));
            // store endpoint.name as hint in case /models discovery fails
            modelConfig.put("endpointName", epFlat.getOrDefault("name", ""));
            modelConfig.put("configUrl",    epFlat.getOrDefault("config_url", ""));

        } else {
            // Flat credentials (Tanzu GenAI v1 or user-provided)
            modelConfig.put("baseUrl", pickFirst(flat,
                    "openai_api_base", "api_base", "api_url", "apiUrl", "base_url", "baseUrl",
                    "endpoint", "uri", "url"));
            modelConfig.put("apiKey",  pickFirst(flat,
                    "api_key", "apiKey", "access_token", "api_token", "token", "openai_api_key"));
            modelConfig.put("modelName", pickFirst(flat,
                    "model_id", "model_name", "modelName", "model"));
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Provider inference
    // ─────────────────────────────────────────────────────────────────────────

    /** Infer provider from credential field shapes when no explicit "provider" key. */
    private static String inferProvider(Map<String, String> flat) {
        if (flat.containsKey("anthropic_api_key") || flat.containsKey("anthropic_version")) return "anthropic";
        String url = pickFirst(flat, "base_url", "api_url", "url", "endpoint", "baseUrl").toLowerCase();
        if (url.contains("anthropic")) return "anthropic";
        if (url.contains("ollama") || flat.containsKey("ollama_url")) return "ollama";
        return "openai"; // default — covers vLLM, Tanzu GenAI, OpenAI, Azure OpenAI
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Model name auto-discovery
    // ─────────────────────────────────────────────────────────────────────────

    // Patterns that indicate a model is NOT a generative/chat model
    private static final List<String> EMBEDDING_PATTERNS = List.of(
            "embed", "bge-", "bge_", "e5-", "rerank", "text-similarity",
            "ada-", "davinci-002", "babbage-002"
    );

    /** Query {baseUrl}/models (OpenAI API) and return the first chat/generative model id. */
    private String discoverModelName(String baseUrl, String apiKey) {
        try {
            String url = baseUrl.endsWith("/") ? baseUrl + "models" : baseUrl + "/models";
            HttpClient client = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(5)).build();
            HttpRequest.Builder req = HttpRequest.newBuilder()
                    .uri(URI.create(url)).GET()
                    .timeout(Duration.ofSeconds(10));
            if (apiKey != null && !apiKey.isBlank()) {
                req.header("Authorization", "Bearer " + apiKey);
            }
            HttpResponse<String> resp = client.send(req.build(),
                    HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() == 200) {
                Map<String, Object> body = MAPPER.readValue(resp.body(),
                        new TypeReference<Map<String, Object>>() {});
                Object data = body.get("data");
                if (data instanceof List<?> list) {
                    // Collect all model ids
                    List<String> all = new ArrayList<>();
                    for (Object item : list) {
                        if (item instanceof Map<?,?> m) {
                            Object idObj = m.get("id");
                            String id = idObj != null ? String.valueOf(idObj) : "";
                            if (!id.isBlank()) all.add(id);
                        }
                    }
                    log.info("[CF] /models returned: {}", all);
                    // Prefer a chat/generative model — skip embedding/rerank models
                    String chat = all.stream()
                            .filter(id -> EMBEDDING_PATTERNS.stream()
                                    .noneMatch(p -> id.toLowerCase().contains(p)))
                            .findFirst().orElse("");
                    if (!chat.isEmpty()) {
                        log.info("[CF] Auto-discovered chat model: {}", chat);
                        return chat;
                    }
                    // Last resort — return first model even if it looks like an embedding
                    if (!all.isEmpty()) {
                        log.warn("[CF] Only embedding models found — using: {}", all.get(0));
                        return all.get(0);
                    }
                }
            } else {
                log.warn("[CF] /models returned HTTP {}", resp.statusCode());
            }
        } catch (Exception e) {
            log.warn("[CF] Model auto-discovery failed: {}", e.getMessage());
        }
        return "";
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Spring Environment fallback (for CredHub-ref cases)
    // ─────────────────────────────────────────────────────────────────────────

    private void resolveAiFromSpringEnv(String svcName) {
        String p = "vcap.services." + svcName + ".credentials.";
        String baseUrl = firstEnvKey(p, "openai_api_base", "api_base", "api_url", "apiUrl",
                "base_url", "baseUrl", "uri", "url");
        String apiKey  = firstEnvKey(p, "api_key", "apiKey", "access_token", "api_token", "token");
        String model   = firstEnvKey(p, "model_id", "model_name", "modelName", "model");
        if (!baseUrl.isEmpty()) {
            modelConfig.put("baseUrl",   baseUrl);
            modelConfig.put("apiKey",    apiKey);
            modelConfig.put("modelName", model);
            log.info("[CF] Spring Env resolved AI creds for {}: url={}", svcName, baseUrl);
        } else {
            log.warn("[CF] Spring Env fallback found nothing for service={}", svcName);
        }
    }

    private void resolveGpMcpFromSpringEnv(String svcName) {
        String p = "vcap.services." + svcName + ".credentials.";
        String url  = firstEnvKey(p, "url", "mcpUrl", "mcp_url", "endpoint");
        String auth = firstEnvKey(p, "auth", "mcpAuth", "token", "api_key");
        if (!url.isEmpty()) {
            mcpConfig.put("mcpUrl",  url);
            mcpConfig.put("mcpAuth", normalizeAuth(auth));
            log.info("[CF] Spring Env resolved Greenplum MCP for {}: {}", svcName, url);
        }
    }

    private void resolveOmMcpFromSpringEnv(String svcName) {
        String p = "vcap.services." + svcName + ".credentials.";
        String url  = firstEnvKey(p, "url", "omMcpUrl", "om_url", "endpoint");
        String auth = firstEnvKey(p, "auth", "omMcpAuth", "token", "api_key");
        if (!url.isEmpty()) {
            mcpConfig.put("omMcpUrl",  url);
            mcpConfig.put("omMcpAuth", normalizeAuth(auth));
            log.info("[CF] Spring Env resolved OpenMetadata MCP for {}: {}", svcName, url);
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Utility helpers
    // ─────────────────────────────────────────────────────────────────────────

    /** Flatten a Map<String,Object> to Map<String,String>, skipping nested maps. */
    private static Map<String, String> flatten(Map<String, Object> raw) {
        Map<String, String> out = new LinkedHashMap<>();
        if (raw == null) return out;
        raw.forEach((k, v) -> {
            if (v != null && !(v instanceof Map)) out.put(k, String.valueOf(v));
        });
        return out;
    }

    /** Return the first non-empty value found for the given keys in the flat map. */
    private static String pickFirst(Map<String, String> flat, String... keys) {
        for (String k : keys) {
            String v = flat.get(k);
            if (v != null && !v.isBlank()) return v;
        }
        return "";
    }

    /** Return the first non-empty Spring Environment property for the given key suffixes. */
    private String firstEnvKey(String prefix, String... keys) {
        for (String k : keys) {
            String v = springEnv.getProperty(prefix + k);
            if (v != null && !v.isBlank()) return v;
        }
        return "";
    }

    /** Prepend "Bearer " to raw JWT tokens that don't already have an auth scheme. */
    private static String normalizeAuth(String auth) {
        if (auth == null || auth.isBlank()) return "";
        String t = auth.trim();
        if (!t.toLowerCase().startsWith("bearer ") && !t.toLowerCase().startsWith("basic "))
            return "Bearer " + t;
        return t;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Public API
    // ─────────────────────────────────────────────────────────────────────────

    public boolean isCfMode()                    { return cfMode; }
    public Map<String, String> getModelConfig()  { return Collections.unmodifiableMap(modelConfig); }
    public Map<String, String> getMcpConfig()    { return Collections.unmodifiableMap(mcpConfig); }

    /** Display name for the bound model (used in header status). */
    public String getModelSummary() {
        String name = modelConfig.getOrDefault("modelName", "");
        if (!name.isEmpty()) return name;
        String hint = modelConfig.getOrDefault("endpointName", "");
        return hint.isEmpty() ? "GenAI" : hint;
    }

    /** Names of bound MCP servers (e.g. ["Greenplum", "OpenMetadata"]). */
    public List<String> getMcpServerNames() {
        List<String> names = new ArrayList<>();
        if (!mcpConfig.getOrDefault("mcpUrl",   "").isEmpty()) names.add("Greenplum");
        if (!mcpConfig.getOrDefault("omMcpUrl", "").isEmpty()) names.add("OpenMetadata");
        return names;
    }
}
