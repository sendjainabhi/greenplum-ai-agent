package com.gp.agent.db;

import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ChatMessageSerializer;
import dev.langchain4j.store.memory.chat.ChatMemoryStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.List;

/**
 * LangChain4j ChatMemoryStore backed by the user_memory table.
 * memoryId format: "{userId}::{sessionId}" (same as FileBackedChatMemoryStore).
 */
public class PostgresChatMemoryStore implements ChatMemoryStore {

    private static final Logger log = LoggerFactory.getLogger(PostgresChatMemoryStore.class);

    private final JdbcTemplate jdbc;

    public PostgresChatMemoryStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<ChatMessage> getMessages(Object memoryId) {
        try {
            String[] parts   = memoryId.toString().split("::", 2);
            String userId    = parts[0];
            String sessionId = parts.length > 1 ? parts[1] : parts[0];
            List<String> rows = jdbc.queryForList(
                    "SELECT messages FROM user_memory WHERE user_id = ? AND session_id = ?",
                    String.class, userId, sessionId);
            if (rows.isEmpty()) return new ArrayList<>();
            return MessageSanitizer.fromJson(rows.get(0));
        } catch (Exception e) {
            log.error("[MEMORY] Failed to read messages for {}: {}", memoryId, e.getMessage());
            return new ArrayList<>();
        }
    }

    @Override
    public void updateMessages(Object memoryId, List<ChatMessage> messages) {
        try {
            String[] parts   = memoryId.toString().split("::", 2);
            String userId    = parts[0];
            String sessionId = parts.length > 1 ? parts[1] : parts[0];
            String json = ChatMessageSerializer.messagesToJson(messages);
            jdbc.update("""
                    INSERT INTO user_memory(user_id, session_id, messages, updated_at) VALUES (?, ?, ?, NOW())
                    ON CONFLICT(user_id, session_id) DO UPDATE
                        SET messages = EXCLUDED.messages, updated_at = NOW()
                    """, userId, sessionId, json);
            log.info("[MEMORY] Saved {} messages for {}::{}", messages.size(), userId, sessionId);
        } catch (Exception e) {
            log.error("[MEMORY] Failed to save messages for {}: {}", memoryId, e.getMessage());
        }
    }

    @Override
    public void deleteMessages(Object memoryId) {
        try {
            String[] parts   = memoryId.toString().split("::", 2);
            String userId    = parts[0];
            String sessionId = parts.length > 1 ? parts[1] : parts[0];
            int deleted = jdbc.update(
                    "DELETE FROM user_memory WHERE user_id = ? AND session_id = ?",
                    userId, sessionId);
            if (deleted > 0) log.info("[MEMORY] Deleted memory for {}::{}", userId, sessionId);
        } catch (Exception e) {
            log.error("[MEMORY] Failed to delete memory for {}: {}", memoryId, e.getMessage());
        }
    }
}
