package com.gp.agent.db;

import dev.langchain4j.model.chat.listener.ChatModelListener;
import dev.langchain4j.model.chat.listener.ChatModelResponseContext;
import dev.langchain4j.model.output.TokenUsage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Captures token usage from every LLM call and writes it to model_usage.
 * Active only in postgres storage mode.
 */
@Component
@ConditionalOnProperty(name = "gp.agent.storage", havingValue = "postgres")
public class ModelUsageListener implements ChatModelListener {

    private static final Logger log = LoggerFactory.getLogger(ModelUsageListener.class);
    private final JdbcTemplate jdbc;

    public ModelUsageListener(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void onResponse(ChatModelResponseContext context) {
        try {
            TokenUsage usage = context.response().tokenUsage();
            if (usage == null) return;

            String model = context.response().model();
            int input  = usage.inputTokenCount()  != null ? usage.inputTokenCount()  : 0;
            int output = usage.outputTokenCount() != null ? usage.outputTokenCount() : 0;
            int total  = usage.totalTokenCount()  != null ? usage.totalTokenCount()  : input + output;

            // Upsert: one row per (day, model) — increment daily counters
            jdbc.update(
                "INSERT INTO model_usage (day, model_name, call_count, input_tokens, output_tokens, total_tokens) " +
                "VALUES (CURRENT_DATE, ?, 1, ?, ?, ?) " +
                "ON CONFLICT (day, model_name) DO UPDATE SET " +
                "  call_count    = model_usage.call_count    + 1, " +
                "  input_tokens  = model_usage.input_tokens  + EXCLUDED.input_tokens, " +
                "  output_tokens = model_usage.output_tokens + EXCLUDED.output_tokens, " +
                "  total_tokens  = model_usage.total_tokens  + EXCLUDED.total_tokens",
                model, input, output, total);
        } catch (Exception e) {
            log.warn("[USAGE] Failed to record model usage: {}", e.getMessage());
        }
    }
}
