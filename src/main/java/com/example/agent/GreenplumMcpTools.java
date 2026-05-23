package com.example.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.Tool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;
import org.springframework.http.ResponseEntity;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;

import java.util.Map;

@Component
public class GreenplumMcpTools {

    // Define a dedicated Logger instance for this class
    private static final Logger log = LoggerFactory.getLogger(GreenplumMcpTools.class);
    
    private final RestTemplate restTemplate = new RestTemplate();
    private final ObjectMapper objectMapper = new ObjectMapper();
    
    @Value("${mcp.server.url}")
    private String mcpServerUrl;

    @Value("${mcp.server.auth-header}")
    private String mcpAuthHeader;

    @Tool("Translates a user's natural language request into a valid PostgreSQL/Greenplum SQL query and executes it against the database. You MUST pass valid SQL syntax as the query argument.")
    public String executeQuery(String query) {
        return callMcpServer("execute_query", Map.of("query", query), "1");
    }

    @Tool("Retrieves comprehensive details regarding the Greenplum database cluster health, segment status, and mirroring configuration. Use this if the user asks about database health or cluster replication.")
    public String getClusterStatus() {
        return callMcpServer("cluster_status", Map.of(), "2");
    }

    @Tool("Scans the Greenplum cluster database tables to identify data bloat using gp_toolkit. Use this if the user wants to check database optimization or identify tables needing a VACUUM.")
    public String checkTableBloat() {
        return callMcpServer("check_table_bloat", Map.of("limit", 10, "min_bloat_percent", 20), "3");
    }

    private String callMcpServer(String toolName, Map<String, Object> arguments, String rpcId) {
        try {
            // Visual separator for the start of an API transaction
            log.info("\n==================== MCP TOOL OUTBOUND ====================\n" +
                     "Tool Name : {}\n" +
                     "Arguments : {}\n" +
                     "===========================================================", 
                     toolName, arguments);
            
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.set("Authorization", mcpAuthHeader);
            
            Map<String, Object> payload = Map.of(
                "jsonrpc", "2.0",
                "id", rpcId,
                "method", "tools/call",
                "params", Map.of(
                    "name", toolName,
                    "arguments", arguments
                )
            );

            String requestBody = objectMapper.writeValueAsString(payload);
            HttpEntity<String> request = new HttpEntity<>(requestBody, headers);
            
            ResponseEntity<String> response = restTemplate.postForEntity(mcpServerUrl, request, String.class);
            String responseBody = response.getBody();
            
            // Visual separator for the incoming database payload
            log.info("\n==================== MCP TOOL INBOUND =====================\n" +
                     "Tool Name : {}\n" +
                     "Response  : {}\n" +
                     "===========================================================", 
                     toolName, responseBody);
                     
            return responseBody;
            
        } catch (Exception e) {
            log.error("\n!!!!!!!!!!!!!!!!!!!! MCP TOOL ERROR !!!!!!!!!!!!!!!!!!!!\n" +
                      "Tool Name : {}\n" +
                      "Error Msg : {}\n" +
                      "!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!", 
                      toolName, e.getMessage(), e);
            return "Error calling tool " + toolName + ": " + e.getMessage();
        }
    }
}