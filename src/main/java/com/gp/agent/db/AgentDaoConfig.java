package com.gp.agent.db;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Creates exactly one AgentDao bean based on the gp.agent.storage property
 * set by PostgresEnvironmentPostProcessor at startup.
 *
 * Using @ConditionalOnProperty instead of @ConditionalOnBean(JdbcTemplate.class)
 * because @ConditionalOnBean is evaluated before JDBC auto-configuration beans
 * are registered, making it unreliable in user @Configuration classes.
 * @ConditionalOnProperty reads from the Spring Environment which is fully
 * populated before any bean conditions are evaluated.
 */
@Configuration
public class AgentDaoConfig {

    @Bean
    @ConditionalOnProperty(name = "gp.agent.storage", havingValue = "postgres")
    AgentDao postgresAgentDao(JdbcTemplate jdbc) {
        return new PostgresAgentDao(jdbc);
    }

    @Bean
    @ConditionalOnProperty(name = "gp.agent.storage", havingValue = "file", matchIfMissing = true)
    AgentDao fileAgentDao() {
        return new FileAgentDao();
    }
}
