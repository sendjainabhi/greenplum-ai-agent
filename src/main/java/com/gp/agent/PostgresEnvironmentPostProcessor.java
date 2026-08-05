package com.gp.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Reads the Tanzu Postgres (postgres / db-small) VCAP binding at startup and
 * injects spring.datasource.* so Spring Boot auto-configures HikariCP + Flyway.
 * When no postgres binding is found the datasource auto-configurations are excluded
 * so the app falls back to file-based storage without errors.
 */
public class PostgresEnvironmentPostProcessor implements EnvironmentPostProcessor {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment env, SpringApplication app) {
        String vcap = System.getenv("VCAP_SERVICES");
        if (vcap == null || vcap.isBlank()) {
            disableDataSource(env);
            return;
        }

        try {
            JsonNode root = MAPPER.readTree(vcap);
            Creds creds = extractPostgresCreds(root);
            if (creds == null) {
                disableDataSource(env);
                return;
            }

            Map<String, Object> props = new LinkedHashMap<>();
            props.put("spring.datasource.url",      creds.url);
            props.put("spring.datasource.username",  creds.user);
            props.put("spring.datasource.password",  creds.password);
            props.put("spring.datasource.driver-class-name", "org.postgresql.Driver");
            // HikariCP tuning for CF containers
            props.put("spring.datasource.hikari.maximum-pool-size",   "5");
            props.put("spring.datasource.hikari.minimum-idle",         "1");
            props.put("spring.datasource.hikari.connection-timeout",  "10000");
            props.put("spring.datasource.hikari.idle-timeout",        "300000");
            // Flyway
            props.put("spring.flyway.enabled",            "true");
            props.put("spring.flyway.locations",          "classpath:db");
            props.put("spring.flyway.baseline-on-migrate","true");

            props.put("gp.agent.storage", "postgres");
            env.getPropertySources().addFirst(
                    new MapPropertySource("vcap-postgres", props));
            System.out.println("[POSTGRES] DataSource configured from VCAP: " + maskUrl(creds.url));

        } catch (Exception e) {
            System.err.println("[POSTGRES] Failed to parse VCAP_SERVICES for postgres: " + e.getMessage());
            disableDataSource(env);
        }
    }

    private Creds extractPostgresCreds(JsonNode root) {
        // Tanzu Postgres binding is under key "postgres"
        for (JsonNode services : root) {
            for (JsonNode svc : services) {
                String label = svc.path("label").asText("");
                String name  = svc.path("name").asText("").toLowerCase();
                if (!"postgres".equalsIgnoreCase(label) && !name.contains("postgres")) continue;

                JsonNode c = svc.path("credentials");
                String jdbcUrl  = c.path("jdbcUrl").asText("").trim();
                String uri      = c.path("uri").asText("").trim();
                String user     = c.path("user").asText("").trim();
                String password = c.path("password").asText("").trim();
                String host     = c.path("primary_host").asText(
                                    c.path("hosts").path(0).asText("")).trim();
                int    port     = c.path("port").asInt(5432);
                String db       = c.path("db").asText("postgres").trim();

                if (!jdbcUrl.isEmpty()) {
                    // jdbcUrl may embed credentials — use it directly but also pass user/pass separately
                    String cleanUrl = jdbcUrl.replaceAll("[?&]user=[^&]*", "")
                                             .replaceAll("[?&]password=[^&]*", "")
                                             .replaceAll("[?]$", "");
                    return new Creds(cleanUrl, user, password);
                }
                if (!host.isEmpty()) {
                    String url = "jdbc:postgresql://" + host + ":" + port + "/" + db;
                    return new Creds(url, user, password);
                }
                // Fall back to parsing uri (postgresql://user:pass@host:port/db)
                if (!uri.isEmpty()) {
                    String url = uri.replace("postgresql://", "jdbc:postgresql://");
                    // Extract user/pass from URI if not already found
                    if (user.isEmpty() && url.contains("@")) {
                        String auth = url.substring("jdbc:postgresql://".length(), url.indexOf("@"));
                        String[] parts = auth.split(":", 2);
                        user     = parts[0];
                        password = parts.length > 1 ? parts[1] : "";
                        url      = "jdbc:postgresql://" + url.substring(url.indexOf("@") + 1);
                    }
                    return new Creds(url, user, password);
                }
            }
        }
        return null;
    }

    private void disableDataSource(ConfigurableEnvironment env) {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("spring.autoconfigure.exclude",
                "org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration," +
                "org.springframework.boot.autoconfigure.jdbc.JdbcTemplateAutoConfiguration," +
                "org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration");
        props.put("spring.flyway.enabled", "false");
        props.put("gp.agent.storage", "file");
        env.getPropertySources().addFirst(
                new MapPropertySource("vcap-postgres-disabled", props));
        System.out.println("[POSTGRES] No postgres VCAP binding found — using file-based storage");
    }

    private String maskUrl(String url) {
        return url.replaceAll("password=[^&?]+", "password=***");
    }

    private record Creds(String url, String user, String password) {}
}
