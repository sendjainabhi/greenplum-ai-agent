package com.example.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.Tool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.List;
import java.util.Map;

@Component
public class GreenplumMcpTools {

    private static final Logger log =
            LoggerFactory.getLogger(GreenplumMcpTools.class);

    private final RestTemplate restTemplate =
            new RestTemplate();

    private final ObjectMapper objectMapper =
            new ObjectMapper();

    @Value("${mcp.server.url}")
    private String mcpServerUrl;

    @Value("${mcp.server.auth-header}")
    private String mcpAuthHeader;

    // =====================================================
    // SCHEMA DISCOVERY TOOLS
    // =====================================================

    @Tool("""
    Lists all non-system schemas in Greenplum.
    ALWAYS use this first before querying tables.
    """)
    public String listSchemas() {

        String sql = """
            SELECT schema_name
            FROM information_schema.schemata
            WHERE schema_name NOT IN (
                'pg_catalog',
                'information_schema'
            )
            ORDER BY schema_name
            """;

        return executeQuery(sql);
    }

    @Tool("""
    Lists all tables for a schema.
    Input should be schema name only.
    """)
    public String listTables(String schema) {

        String sql = """
            SELECT table_name
            FROM information_schema.tables
            WHERE table_schema = '%s'
            ORDER BY table_name
            """.formatted(schema);

        return executeQuery(sql);
    }

    @Tool("""
    Returns columns and datatypes for a table.
    ALWAYS inspect columns before generating SQL.
    """)
    public String describeTable(
            String schema,
            String table
    ) {

        String sql = """
            SELECT
                column_name,
                data_type
            FROM information_schema.columns
            WHERE table_schema = '%s'
            AND table_name = '%s'
            ORDER BY ordinal_position
            """.formatted(schema, table);

        return executeQuery(sql);
    }

    @Tool("""
    Returns sample rows from a table.
    Use this to understand the business meaning of columns.
    """)
    public String sampleTable(
            String schema,
            String table
    ) {

        String sql = """
            SELECT *
            FROM "%s"."%s"
            LIMIT 3
            """.formatted(schema, table);

        return executeQuery(sql);
    }

    // =====================================================
    // MAIN QUERY EXECUTION
    // =====================================================

    @Tool("""
    Executes READ ONLY SQL queries against Greenplum.

    ONLY SELECT queries are allowed.

    NEVER use:
    INSERT
    UPDATE
    DELETE
    DROP
    ALTER
    TRUNCATE
    CREATE
    GRANT
    """)
    public String executeQuery(String query) {

        validateSql(query);

        return callMcpServer(
                "execute_query",
                Map.of("query", query),
                "1"
        );
    }

    // =====================================================
    // GREENPLUM DIAGNOSTICS
    // =====================================================

    @Tool("""
    Returns Greenplum cluster health and segment status.
    """)
    public String getClusterStatus() {

        return callMcpServer(
                "cluster_status",
                Map.of(),
                "2"
        );
    }

    @Tool("""
    Detects Greenplum table bloat using gp_toolkit.
    """)
    public String checkTableBloat() {

        return callMcpServer(
                "check_table_bloat",
                Map.of(
                        "limit", 10,
                        "min_bloat_percent", 20
                ),
                "3"
        );
    }

    @Tool("""
    Returns Greenplum partition metadata.
    """)
    public String getPartitions() {

        String sql = """
            SELECT *
            FROM pg_partitions
            LIMIT 100
            """;

        return executeQuery(sql);
    }

    @Tool("""
    Returns Greenplum distribution policy metadata.
    """)
    public String getDistributionPolicy() {

        String sql = """
            SELECT *
            FROM gp_distribution_policy
            LIMIT 100
            """;

        return executeQuery(sql);
    }

    // =====================================================
    // MCP CALL
    // =====================================================

    private String callMcpServer(
            String toolName,
            Map<String, Object> arguments,
            String rpcId
    ) {

        try {

            log.info("""
                    
                    ==================== MCP TOOL OUTBOUND ====================
                    Tool Name : {}
                    Arguments : {}
                    ===========================================================
                    """,
                    toolName,
                    arguments
            );

            HttpHeaders headers = new HttpHeaders();

            headers.setContentType(
                    MediaType.APPLICATION_JSON
            );

            headers.set(
                    "Authorization",
                    mcpAuthHeader
            );

            Map<String, Object> payload = Map.of(
                    "jsonrpc", "2.0",
                    "id", rpcId,
                    "method", "tools/call",
                    "params", Map.of(
                            "name", toolName,
                            "arguments", arguments
                    )
            );

            String requestBody =
                    objectMapper.writeValueAsString(payload);

            HttpEntity<String> request =
                    new HttpEntity<>(requestBody, headers);

            ResponseEntity<String> response =
                    restTemplate.postForEntity(
                            mcpServerUrl,
                            request,
                            String.class
                    );

            String responseBody = response.getBody();

            log.info("""
                    
                    ==================== MCP TOOL INBOUND =====================
                    Tool Name : {}
                    Response  : {}
                    ===========================================================
                    """,
                    toolName,
                    responseBody
            );

            return responseBody;

        } catch (Exception e) {

            log.error("""
                    
                    !!!!!!!!!!!!!!!!!!!! MCP TOOL ERROR !!!!!!!!!!!!!!!!!!!!
                    Tool Name : {}
                    Error Msg : {}
                    !!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!
                    """,
                    toolName,
                    e.getMessage(),
                    e
            );

            return """
                   SQL execution failed.
                   
                   Error:
                   %s
                   
                   Please analyze the error and retry with corrected SQL.
                   """.formatted(e.getMessage());
        }
    }

    // =====================================================
    // SQL VALIDATION
    // =====================================================

    private void validateSql(String sql) {

        String normalized =
                sql.toUpperCase().trim();

        List<String> forbidden = List.of(
                "INSERT",
                "UPDATE",
                "DELETE",
                "DROP",
                "ALTER",
                "TRUNCATE",
                "CREATE",
                "GRANT",
                "REVOKE"
        );

        for (String keyword : forbidden) {

            if (normalized.contains(keyword)) {

                throw new RuntimeException(
                        "Forbidden SQL detected: "
                                + keyword
                );
            }
        }

        if (!normalized.startsWith("SELECT")
                && !normalized.startsWith("WITH")) {

            throw new RuntimeException(
                    "Only SELECT queries allowed"
            );
        }
    }
}