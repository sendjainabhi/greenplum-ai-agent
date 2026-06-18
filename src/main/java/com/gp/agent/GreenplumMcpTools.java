package com.gp.agent;

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

    private final String mcpServerUrl;
    private final String mcpAuthHeader;

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

    private HttpHeaders buildHeaders() {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        if (mcpAuthHeader != null && !mcpAuthHeader.trim().isEmpty()) {
            h.set("Authorization", mcpAuthHeader);
        }
        return h;
    }

    // -------------------------------------------------------------------------
    // Connectivity test (static — uses its own short-timeout RestTemplate)
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

            Map<String, Object> payload = Map.of(
                "jsonrpc", "2.0",
                "id",      1,
                "method",  "tools/list",
                "params",  Map.of()
            );

            ResponseEntity<String> resp = rt.postForEntity(mcpUrl,
                new HttpEntity<>(OBJECT_MAPPER.writeValueAsString(payload), headers),
                String.class);

            return Map.of("status", "success",
                    "message", "MCP server connected (HTTP " + resp.getStatusCode().value() + ")");
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
    // Internal call
    // -------------------------------------------------------------------------

    private String callMcpServer(String toolName, Map<String, Object> arguments) {
        try {
            log.info("\n==================== MCP TOOL OUTBOUND ====================\n" +
                     "Tool Name : {}\nArguments : {}\nTarget URL: {}\n" +
                     "===========================================================",
                     toolName, arguments, mcpServerUrl);

            Map<String, Object> payload = Map.of(
                "jsonrpc", "2.0",
                "id",      UUID.randomUUID().toString(),
                "method",  "tools/call",
                "params",  Map.of("name", toolName, "arguments", arguments)
            );

            ResponseEntity<String> response = REST_TEMPLATE.postForEntity(
                mcpServerUrl,
                new HttpEntity<>(OBJECT_MAPPER.writeValueAsString(payload), buildHeaders()),
                String.class);

            String responseBody = response.getBody();

            log.info("\n==================== MCP TOOL INBOUND =====================\n" +
                     "Tool Name : {}\nResponse  : {}\n" +
                     "===========================================================",
                     toolName, responseBody);

            return responseBody;

        } catch (Exception e) {
            log.error("\n!!!!!!!!!!!!!!!!!!!! MCP TOOL ERROR !!!!!!!!!!!!!!!!!!!!\n" +
                      "Tool Name : {}\nError Msg : {}\n" +
                      "!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!",
                      toolName, e.getMessage(), e);
            return "Error calling tool " + toolName + ": " + e.getMessage();
        }
    }
}
