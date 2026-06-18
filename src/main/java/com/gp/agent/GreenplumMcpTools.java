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
import org.springframework.web.client.RestTemplate;

import java.util.Map;
import java.util.UUID;

public class GreenplumMcpTools {

    private static final Logger log = LoggerFactory.getLogger(GreenplumMcpTools.class);
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final RestTemplate REST_TEMPLATE = buildRestTemplate();

    // Server mode — detected once on first use, then cached
    private enum ServerMode { UNKNOWN, STATELESS, SESSION_BASED }

    private final String mcpServerUrl;
    private final String mcpAuthHeader;

    private volatile ServerMode detectedMode = ServerMode.UNKNOWN;
    private volatile String     sessionId    = null;

    public GreenplumMcpTools(String mcpServerUrl, String mcpAuthHeader) {
        this.mcpServerUrl  = mcpServerUrl;
        this.mcpAuthHeader = mcpAuthHeader;
    }

    private static RestTemplate buildRestTemplate() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(10_000);
        factory.setReadTimeout(300_000);
        return new RestTemplate(factory);
    }

    // -------------------------------------------------------------------------
    // Session management (Approach A — adaptive detection)
    // -------------------------------------------------------------------------

    // Synchronized so only one thread runs the handshake even under concurrent calls.
    private synchronized void initializeSession() {
        if (detectedMode != ServerMode.UNKNOWN) return; // another thread already resolved it

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
                log.info("[MCP] New-spec server (2025-03-26) — session established: {}", sessionId);
                sendInitializedNotification();
            } else {
                // Server accepted initialize but issued no session ID — old server or lenient new server
                detectedMode = ServerMode.STATELESS;
                log.info("[MCP] Server accepted initialize but returned no session ID — stateless mode");
            }

        } catch (Exception e) {
            // Server does not understand initialize (old-spec stateless server) — fall through silently
            detectedMode = ServerMode.STATELESS;
            log.info("[MCP] Old-spec server detected (initialize unsupported: {}) — stateless mode", e.getMessage());
        }
    }

    // Spec requires this notification after initialize; response is not expected.
    private void sendInitializedNotification() {
        try {
            Map<String, Object> notification = Map.of(
                "jsonrpc", "2.0",
                "method",  "notifications/initialized"
            );
            REST_TEMPLATE.postForEntity(
                mcpServerUrl,
                new HttpEntity<>(OBJECT_MAPPER.writeValueAsString(notification), buildHeaders(sessionId)),
                String.class
            );
            log.debug("[MCP] notifications/initialized sent");
        } catch (Exception e) {
            log.debug("[MCP] notifications/initialized failed (non-fatal): {}", e.getMessage());
        }
    }

    private synchronized void resetSession() {
        log.info("[MCP] Resetting session state for re-initialization");
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

    // -------------------------------------------------------------------------
    // Header builder — includes Mcp-Session-Id when available
    // -------------------------------------------------------------------------

    private HttpHeaders buildHeaders(String sid) {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        if (mcpAuthHeader != null && !mcpAuthHeader.trim().isEmpty()) {
            h.set("Authorization", mcpAuthHeader);
        }
        if (sid != null && !sid.isEmpty()) {
            h.set("Mcp-Session-Id", sid);
        }
        return h;
    }

    // -------------------------------------------------------------------------
    // Connectivity test (static — tries initialize first, falls back to tools/list)
    // -------------------------------------------------------------------------

    public static Map<String, String> testConnection(String mcpUrl, String mcpAuth) {
        if (mcpUrl == null || mcpUrl.trim().isEmpty()) {
            return Map.of("status", "skipped", "message", "MCP URL not configured.");
        }
        try {
            SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
            factory.setConnectTimeout(10_000);
            factory.setReadTimeout(15_000);
            RestTemplate rt = new RestTemplate(factory);

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            if (mcpAuth != null && !mcpAuth.trim().isEmpty()) {
                headers.set("Authorization", mcpAuth);
            }

            // Step 1 — try initialize (new-spec server)
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
                    new HttpEntity<>(OBJECT_MAPPER.writeValueAsString(initPayload), headers),
                    String.class);
                probeSessionId = initResp.getHeaders().getFirst("Mcp-Session-Id");
            } catch (Exception ignored) {
                // Old server — initialize not supported, will try tools/list directly
            }

            // Step 2 — call tools/list (with session ID if we got one)
            HttpHeaders listHeaders = new HttpHeaders();
            listHeaders.setContentType(MediaType.APPLICATION_JSON);
            if (mcpAuth != null && !mcpAuth.trim().isEmpty()) listHeaders.set("Authorization", mcpAuth);
            if (probeSessionId != null) listHeaders.set("Mcp-Session-Id", probeSessionId);

            Map<String, Object> listPayload = Map.of(
                "jsonrpc", "2.0",
                "id",      1,
                "method",  "tools/list",
                "params",  Map.of()
            );
            ResponseEntity<String> listResp = rt.postForEntity(mcpUrl,
                new HttpEntity<>(OBJECT_MAPPER.writeValueAsString(listPayload), listHeaders),
                String.class);

            String mode = (probeSessionId != null) ? "session-based" : "stateless";
            return Map.of("status", "success",
                    "message", "MCP server connected [" + mode + "] (HTTP " + listResp.getStatusCode().value() + ")");

        } catch (Exception e) {
            return Map.of("status", "error", "message", e.getMessage());
        }
    }

    // -------------------------------------------------------------------------
    // Tools
    // -------------------------------------------------------------------------

    @Tool("Translates a user's natural language request into a valid PostgreSQL/Greenplum SQL query and executes it against the database. You MUST pass valid SQL syntax as the query argument.")
    public String executeQuery(String query) {
        return callMcpServer("execute_query", Map.of("query", query));
    }

    @Tool("Retrieves comprehensive details regarding the Greenplum database cluster health, segment status, and mirroring configuration. Use this if the user asks about database health or cluster replication.")
    public String getClusterStatus() {
        return callMcpServer("cluster_status", Map.of());
    }

    @Tool("Scans the Greenplum cluster database tables to identify data bloat using gp_toolkit. Use this if the user wants to check database optimization or identify tables needing a VACUUM.")
    public String checkTableBloat() {
        return callMcpServer("check_table_bloat", Map.of("limit", 10, "min_bloat_percent", 20));
    }

    // -------------------------------------------------------------------------
    // Internal call — detects mode on first use, retries on session expiry
    // -------------------------------------------------------------------------

    private String callMcpServer(String toolName, Map<String, Object> arguments) {
        // Detect server mode on first call
        if (detectedMode == ServerMode.UNKNOWN) {
            initializeSession();
        }

        String result = doCall(toolName, arguments);

        // Session expired mid-use — re-initialize once and retry
        if (isInvalidSessionError(result) && detectedMode == ServerMode.SESSION_BASED) {
            log.warn("[MCP] Session invalid/expired — re-initializing and retrying: {}", toolName);
            resetSession();
            initializeSession();
            result = doCall(toolName, arguments);
        }

        return result;
    }

    private String doCall(String toolName, Map<String, Object> arguments) {
        try {
            log.info("\n==================== MCP TOOL OUTBOUND ====================\n" +
                     "Tool Name : {}\nArguments : {}\nTarget URL: {}\nMode      : {}\n" +
                     "===========================================================",
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
                String.class);

            String responseBody = response.getBody();

            log.info("\n==================== MCP TOOL INBOUND =====================\n" +
                     "Tool Name : {}\nResponse  : {}\n" +
                     "===========================================================",
                     toolName, responseBody);

            return translateMcpError(responseBody);

        } catch (Exception e) {
            log.error("\n!!!!!!!!!!!!!!!!!!!! MCP TOOL ERROR !!!!!!!!!!!!!!!!!!!!\n" +
                      "Tool Name : {}\nError Msg : {}\n" +
                      "!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!",
                      toolName, e.getMessage(), e);
            return "Error calling tool " + toolName + ": " + e.getMessage();
        }
    }

    // Converts raw MCP error responses into actionable instructions the LLM can act on.
    private static String translateMcpError(String responseBody) {
        if (responseBody == null) return "";
        try {
            JsonNode root = OBJECT_MAPPER.readTree(responseBody);
            boolean isError = root.path("result").path("isError").asBoolean(false);
            if (!isError) return responseBody; // not an error — pass through unchanged

            String errorText = root.path("result").path("content")
                                   .path(0).path("text").asText("").toLowerCase();

            if (errorText.contains("failed to parse query") || errorText.contains("syntax error in sql")) {
                // Check for scalar subquery with aggregate — give the LLM specific corrective instructions
                if (errorText.contains("max") || errorText.contains("min") || errorText.contains("sum")
                        || errorText.contains("count") || errorText.contains("avg")) {
                    log.warn("[MCP] SQL parse error — likely scalar subquery with aggregate. Returning corrective hint.");
                    return "SQL_PARSE_ERROR: The MCP server rejected this query because its SQL parser does not " +
                           "support scalar subqueries containing aggregate functions (MAX, MIN, SUM, COUNT, AVG) " +
                           "inside WHERE clauses. " +
                           "YOU MUST rewrite this as two separate executeQuery calls: " +
                           "(1) Run the aggregate query alone to get the literal value, " +
                           "(2) Use that exact literal value in the main WHERE clause. " +
                           "Do NOT use (SELECT MAX(...) FROM ...) patterns. Retry immediately with the two-step approach.";
                }
                // Generic parse error
                log.warn("[MCP] SQL parse error: {}", errorText);
                return "SQL_PARSE_ERROR: The MCP server rejected this query due to a SQL syntax issue: "
                       + errorText + ". Please rewrite the query and retry.";
            }
        } catch (Exception ignored) {}
        return responseBody; // unrecognised error format — pass through as-is
    }
}
