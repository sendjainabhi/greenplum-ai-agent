package com.gp.agent.db;

import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ChatMessageSerializer;
import dev.langchain4j.store.memory.chat.ChatMemoryStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * LangChain4j ChatMemoryStore backed by the user_memory table.
 * memoryId format: "{userId}::{sessionId}" (same as FileBackedChatMemoryStore).
 *
 * In-memory cache: MessageWindowChatMemory.add() calls store.getMessages() before
 * every write to rebuild its list. Without a cache the sanitizer would run on every
 * tool-call loop iteration, stripping tool requests whose results haven't been written
 * yet — producing an infinite re-execution loop. The cache ensures the sanitizer runs
 * exactly once (on first DB load); subsequent reads return the already-clean list.
 */
public class PostgresChatMemoryStore implements ChatMemoryStore {

    private static final Logger log = LoggerFactory.getLogger(PostgresChatMemoryStore.class);

    private final JdbcTemplate jdbc;
    private final ConcurrentHashMap<Object, List<ChatMessage>> cache = new ConcurrentHashMap<>();

    public PostgresChatMemoryStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<ChatMessage> getMessages(Object memoryId) {
        List<ChatMessage> cached = cache.get(memoryId);
        if (cached != null) {
            return new ArrayList<>(cached);
        }
        // First load: read from DB, sanitize once, populate cache
        try {
            String[] parts   = memoryId.toString().split("::", 2);
            String userId    = parts[0];
            String sessionId = parts.length > 1 ? parts[1] : parts[0];
            List<String> rows = jdbc.queryForList(
                    "SELECT messages FROM user_memory WHERE user_id = ? AND session_id = ?",
                    String.class, userId, sessionId);
            List<ChatMessage> messages = rows.isEmpty()
                    ? new ArrayList<>()
                    : MessageSanitizer.fromJson(rows.get(0));
            cache.put(memoryId, new ArrayList<>(messages));
            return messages;
        } catch (Exception e) {
            log.error("[MEMORY] Failed to read messages for {}: {}", memoryId, e.getMessage());
            return new ArrayList<>();
        }
    }

    @Override
    public void updateMessages(Object memoryId, List<ChatMessage> messages) {
        cache.put(memoryId, new ArrayList<>(messages));
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
        cache.remove(memoryId);
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
