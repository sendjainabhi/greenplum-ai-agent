package com.example.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.Tool;
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

    private final RestTemplate restTemplate = new RestTemplate();
    private final ObjectMapper objectMapper = new ObjectMapper(); // Built-in Spring Boot JSON parser
    
    @Value("${mcp.server.url}")
    private String mcpServerUrl;

    @Value("${mcp.server.auth-header}")
    private String mcpAuthHeader;

    @Tool("Translates a user's natural language request into a valid PostgreSQL/Greenplum SQL query and executes it against the database. You MUST pass valid SQL syntax as the query.")
    public String queryGreenplum(String query) {
        try {
            System.out.println(">>> LLM is executing Tool with query:\n" + query);

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.set("Authorization", mcpAuthHeader);
            
            // Build the payload as a Java Map
            Map<String, Object> payload = Map.of(
                "jsonrpc", "2.0",
                "id", "1",
                "method", "tools/call",
                "params", Map.of(
                    "name", "execute_query",
                    "arguments", Map.of("query", query)
                )
            );

            // Let ObjectMapper handle all the messy escaping (newlines, quotes, etc.)!
            String requestBody = objectMapper.writeValueAsString(payload);

            HttpEntity<String> request = new HttpEntity<>(requestBody, headers);
            
            ResponseEntity<String> response = restTemplate.postForEntity(mcpServerUrl, request, String.class);
            String responseBody = response.getBody();
            
            System.out.println("<<< MCP SERVER RETURNED: " + responseBody);
            
            return responseBody;
            
        } catch (Exception e) {
            System.out.println("!!! HTTP ERROR: " + e.getMessage());
            return "Error communicating with Greenplum MCP Server: " + e.getMessage();
        }
    }
}