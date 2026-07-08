package com.gp.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.Tool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;

import java.util.Map;
import java.util.UUID;

public class OpenMetadataMcpTools {

    private static final Logger log = LoggerFactory.getLogger(OpenMetadataMcpTools.class);
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final RestTemplate REST_TEMPLATE = buildRestTemplate();

    private enum ServerMode { UNKNOWN, STATELESS, SESSION_BASED }

    private final String mcpServerUrl;
    private final String mcpAuthHeader;

    private volatile ServerMode detectedMode = ServerMode.UNKNOWN;
    private volatile String     sessionId    = null;

    public OpenMetadataMcpTools(String mcpServerUrl, String mcpAuthHeader) {
        this.mcpServerUrl  = mcpServerUrl;
        this.mcpAuthHeader = normalizeAuth(mcpAuthHeader);
    }

    private static RestTemplate buildRestTemplate() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(10_000);
        factory.setReadTimeout(300_000);
        return new RestTemplate(factory);
    }

    // -------------------------------------------------------------------------
    // Auth normalization — prepend "Bearer " when no scheme prefix is present
    // -------------------------------------------------------------------------

    private static String normalizeAuth(String auth) {
        if (auth == null || auth.trim().isEmpty()) return auth;
        String t = auth.trim();
        String lower = t.toLowerCase();
        if (lower.startsWith("bearer ") || lower.startsWith("basic ")) return t;
        return "Bearer " + t;
    }

    // -------------------------------------------------------------------------
    // Session management
    // -------------------------------------------------------------------------

    private synchronized void initializeSession() {
        if (detectedMode != ServerMode.UNKNOWN) return;
        if (mcpServerUrl == null || mcpServerUrl.trim().isEmpty()) {
            detectedMode = ServerMode.STATELESS;
            return;
        }
        try {
            Map<String, Object> payload = Map.of(
                "jsonrpc", "2.0",
                "id",      "init-" + UUID.randomUUID(),
                "method",  "initialize",
                "params",  Map.of(
                    "protocolVersion", "2025-03-26",
                    "capabilities",    Map.of(),
                    "clientInfo",      Map.of("name", "greenplum-ai-agent", "version", "1.0.0")
                )
            );
            ResponseEntity<String> response = REST_TEMPLATE.postForEntity(
                mcpServerUrl,
                new HttpEntity<>(OBJECT_MAPPER.writeValueAsString(payload), buildHeaders(null)),
                String.class
            );
            String sid = response.getHeaders().getFirst("Mcp-Session-Id");
            if (sid != null && !sid.trim().isEmpty()) {
                sessionId    = sid.trim();
                detectedMode = ServerMode.SESSION_BASED;
                log.info("[OM-MCP] Session established: {}", sessionId);
                sendInitializedNotification();
            } else {
                detectedMode = ServerMode.STATELESS;
                log.info("[OM-MCP] Stateless mode");
            }
        } catch (Exception e) {
            detectedMode = ServerMode.STATELESS;
            log.info("[OM-MCP] Old-spec server ({}) — stateless mode", e.getMessage());
        }
    }

    private void sendInitializedNotification() {
        try {
            Map<String, Object> notification = Map.of("jsonrpc", "2.0", "method", "notifications/initialized");
            REST_TEMPLATE.postForEntity(
                mcpServerUrl,
                new HttpEntity<>(OBJECT_MAPPER.writeValueAsString(notification), buildHeaders(sessionId)),
                String.class
            );
        } catch (Exception e) {
            log.debug("[OM-MCP] notifications/initialized failed (non-fatal): {}", e.getMessage());
        }
    }

    private synchronized void resetSession() {
        sessionId    = null;
        detectedMode = ServerMode.UNKNOWN;
    }

    private static boolean isInvalidSessionError(String body) {
        if (body == null) return false;
        try {
            JsonNode error = OBJECT_MAPPER.readTree(body).path("error");
            if (!error.isMissingNode()) {
                String msg = error.path("message").asText("").toLowerCase();
                return msg.contains("invalid session") || msg.contains("session not found")
                        || msg.contains("session expired") || msg.contains("unknown session");
            }
        } catch (Exception ignored) {}
        return body.toLowerCase().contains("invalid session");
    }

    private HttpHeaders buildHeaders(String sid) {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        // OpenMetadata MCP server requires this Accept header
        h.set("Accept", "application/json, text/event-stream");
        if (mcpAuthHeader != null && !mcpAuthHeader.trim().isEmpty()) {
            h.set("Authorization", mcpAuthHeader);
        }
        if (sid != null && !sid.isEmpty()) {
            h.set("Mcp-Session-Id", sid);
        }
        return h;
    }

    // -------------------------------------------------------------------------
    // Connectivity test (static)
    // -------------------------------------------------------------------------

    public static Map<String, String> testConnection(String mcpUrl, String mcpAuth) {
        if (mcpUrl == null || mcpUrl.trim().isEmpty()) {
            return Map.of("status", "skipped", "message", "OpenMetadata MCP URL not configured.");
        }

        String normalizedAuth = normalizeAuth(mcpAuth);
        String maskedAuth = (normalizedAuth != null && normalizedAuth.length() > 14)
                ? normalizedAuth.substring(0, 14) + "..." : (normalizedAuth != null ? normalizedAuth : "<none>");
        log.info("[OM-MCP] testConnection → url={} auth={}", mcpUrl, maskedAuth);

        try {
            SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
            factory.setConnectTimeout(10_000);
            factory.setReadTimeout(15_000);
            RestTemplate rt = new RestTemplate(factory);

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.set("Accept", "application/json, text/event-stream");
            if (normalizedAuth != null && !normalizedAuth.isEmpty()) headers.set("Authorization", normalizedAuth);

            String probeSessionId = null;
            try {
                Map<String, Object> initPayload = Map.of(
                    "jsonrpc", "2.0",
                    "id",      "test-init",
                    "method",  "initialize",
                    "params",  Map.of(
                        "protocolVersion", "2025-03-26",
                        "capabilities",    Map.of(),
                        "clientInfo",      Map.of("name", "greenplum-ai-agent", "version", "1.0.0")
                    )
                );
                ResponseEntity<String> initResp = rt.postForEntity(mcpUrl,
                    new HttpEntity<>(OBJECT_MAPPER.writeValueAsString(initPayload), headers), String.class);
                probeSessionId = initResp.getHeaders().getFirst("Mcp-Session-Id");
                log.info("[OM-MCP] initialize → HTTP {} sessionId={}", initResp.getStatusCode().value(), probeSessionId);
            } catch (HttpClientErrorException hce) {
                log.warn("[OM-MCP] initialize failed → HTTP {} body={}", hce.getStatusCode().value(), hce.getResponseBodyAsString());
                if (hce.getStatusCode().value() == 401 || hce.getStatusCode().value() == 403) throw hce;
                // Other 4xx (e.g. 404 = server doesn't support initialize) — fall through to tools/list
            } catch (Exception e) {
                log.info("[OM-MCP] initialize not supported ({}), trying stateless mode", e.getMessage());
            }

            HttpHeaders listHeaders = new HttpHeaders();
            listHeaders.setContentType(MediaType.APPLICATION_JSON);
            listHeaders.set("Accept", "application/json, text/event-stream");
            if (normalizedAuth != null && !normalizedAuth.isEmpty()) listHeaders.set("Authorization", normalizedAuth);
            if (probeSessionId != null) listHeaders.set("Mcp-Session-Id", probeSessionId);

            Map<String, Object> listPayload = Map.of("jsonrpc", "2.0", "id", 1, "method", "tools/list", "params", Map.of());
            log.info("[OM-MCP] tools/list → sessionId={}", probeSessionId);
            ResponseEntity<String> listResp = rt.postForEntity(mcpUrl,
                new HttpEntity<>(OBJECT_MAPPER.writeValueAsString(listPayload), listHeaders), String.class);

            // Log all discovered tool names for diagnostics
            try {
                JsonNode tools = OBJECT_MAPPER.readTree(listResp.getBody()).path("result").path("tools");
                if (tools.isArray()) {
                    StringBuilder sb = new StringBuilder("[OM-MCP] Available tools:");
                    for (JsonNode t : tools) sb.append(" ").append(t.path("name").asText());
                    log.info("{}", sb);
                }
            } catch (Exception ignored) {}

            String mode = (probeSessionId != null) ? "session-based" : "stateless";
            return Map.of("status", "success",
                    "message", "OpenMetadata MCP connected [" + mode + "] (HTTP " + listResp.getStatusCode().value() + ")");

        } catch (HttpClientErrorException hce) {
            String body = hce.getResponseBodyAsString();
            log.error("[OM-MCP] testConnection HTTP error → status={} body={} url={}",
                      hce.getStatusCode().value(), body, mcpUrl);
            String msg = "HTTP " + hce.getStatusCode().value() + " " + hce.getStatusText();
            if (body != null && !body.isBlank()) msg += " — " + body;
            return Map.of("status", "error", "message", msg);
        } catch (Exception e) {
            log.error("[OM-MCP] testConnection failed → url={} error={}", mcpUrl, e.getMessage(), e);
            return Map.of("status", "error", "message", e.getMessage());
        }
    }

    // -------------------------------------------------------------------------
    // Tools — read-only, mapped to OpenMetadata MCP server tool names
    // -------------------------------------------------------------------------

    @Tool("Keyword-based search for data assets in the OpenMetadata catalog (tables, databases, dashboards, pipelines, topics, ML models, glossary terms, domains). " +
          "Returns results including 'fullyQualifiedName' and 'entityType' — pass both to getEntityDetails for full details. " +
          "Optionally filter by entityType (singular form: table, database, databaseService, dashboard, topic, pipeline, mlmodel, glossary, glossaryTerm, domain). " +
          "Use this first before calling getEntityDetails. Try alternate search terms if the first attempt returns zero results.")
    public String searchAssets(String query) {
        return callMcpServer("search_metadata", Map.of("query", query, "size", 20));
    }

    @Tool("Get complete metadata details for any OpenMetadata entity — columns, tags, owners, description, service details. " +
          "Requires: entityType (e.g. table, database, databaseService, dashboard, topic, pipeline) AND " +
          "fqn (the exact 'fullyQualifiedName' value returned from searchAssets — never guess this value). " +
          "Always call searchAssets first to get the exact FQN.")
    public String getEntityDetails(String entityType, String fullyQualifiedName) {
        return callMcpServer("get_entity_details", Map.of("entityType", entityType, "fqn", fullyQualifiedName));
    }

    @Tool("Get data lineage for any entity — shows upstream sources (where data comes from) and downstream consumers (what depends on it). " +
          "Requires: entityType (e.g. table, dashboard, pipeline) AND fqn (exact fullyQualifiedName from searchAssets). " +
          "Explores 3 hops upstream and downstream by default.")
    public String getLineage(String entityType, String fullyQualifiedName) {
        return callMcpServer("get_entity_lineage", Map.of(
            "entityType",          entityType,
            "fqn",                 fullyQualifiedName,
            "upstreamDepth",       3,
            "downstreamDepth",     3,
            "includeColumnLineage", false
        ));
    }

    @Tool("List all entities of a given type from the OpenMetadata catalog. " +
          "entityType can be: table, database, databaseService, dashboard, topic, pipeline, mlmodel, glossary, glossaryTerm, domain, dataProduct. " +
          "Use this to give the user an overview of what's available when they haven't named a specific asset.")
    public String listEntities(String entityType) {
        return callMcpServer("search_metadata", Map.of(
            "entityType", entityType,
            "query",      "*",
            "size",       50
        ));
    }

    @Tool("Get data quality test results for a specific table. Pass the exact fullyQualifiedName of the table. " +
          "Returns test cases and their statuses (success/failed), last run times, and failure messages. " +
          "Use searchAssets to get the exact FQN first if needed.")
    public String getDataQualityResults(String tableFullyQualifiedName) {
        return callMcpServer("search_metadata", Map.of(
            "entityType", "testCase",
            "query",      tableFullyQualifiedName,
            "size",       50
        ));
    }

    @Tool("Perform root cause analysis for a data quality issue on an entity. " +
          "Analyzes upstream sources for failures and downstream impact. " +
          "Requires: entityType (e.g. table, pipeline) AND fqn (exact fullyQualifiedName from searchAssets).")
    public String rootCauseAnalysis(String entityType, String fullyQualifiedName) {
        return callMcpServer("root_cause_analysis", Map.of(
            "entityType",          entityType,
            "fqn",                 fullyQualifiedName,
            "upstreamDepth",       3,
            "downstreamDepth",     3,
            "includeColumnLineage", false
        ));
    }

    // -------------------------------------------------------------------------
    // Internal call — detects mode on first use, retries on session expiry
    // -------------------------------------------------------------------------

    private String callMcpServer(String toolName, Map<String, Object> arguments) {
        if (detectedMode == ServerMode.UNKNOWN) initializeSession();
        String result = doCall(toolName, arguments);
        if (isInvalidSessionError(result) && detectedMode == ServerMode.SESSION_BASED) {
            log.warn("[OM-MCP] Session invalid — re-initializing: {}", toolName);
            resetSession();
            initializeSession();
            result = doCall(toolName, arguments);
        }
        return result;
    }

    private String doCall(String toolName, Map<String, Object> arguments) {
        try {
            log.info("\n==================== OM-MCP TOOL OUTBOUND ====================\n" +
                     "Tool Name : {}\nArguments : {}\nTarget URL: {}\nMode      : {}\n" +
                     "==============================================================",
                     toolName, arguments, mcpServerUrl, detectedMode);

            Map<String, Object> payload = Map.of(
                "jsonrpc", "2.0",
                "id",      UUID.randomUUID().toString(),
                "method",  "tools/call",
                "params",  Map.of("name", toolName, "arguments", arguments)
            );

            ResponseEntity<String> response = REST_TEMPLATE.postForEntity(
                mcpServerUrl,
                new HttpEntity<>(OBJECT_MAPPER.writeValueAsString(payload), buildHeaders(sessionId)),
                String.class
            );

            String body = response.getBody();
            log.info("\n==================== OM-MCP TOOL INBOUND =====================\n" +
                     "Tool Name : {}\nResponse  : {}\n" +
                     "==============================================================",
                     toolName, body);
            return body;

        } catch (Exception e) {
            log.error("[OM-MCP] Tool call failed: {} — {}", toolName, e.getMessage(), e);
            return "Error calling OpenMetadata tool " + toolName + ": " + e.getMessage();
        }
    }
}
