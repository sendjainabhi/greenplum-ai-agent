package com.gp.agent;

import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ChatMessageDeserializer;
import dev.langchain4j.data.message.ChatMessageSerializer;
import dev.langchain4j.memory.chat.ChatMemoryProvider;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.store.memory.chat.ChatMemoryStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

@Configuration
public class AiConfig {

    @Bean
    ChatMemoryStore chatMemoryStore() {
        return new FileBackedChatMemoryStore();
    }

    @Bean
    ChatMemoryProvider chatMemoryProvider(ChatMemoryStore store) {
        return memoryId -> MessageWindowChatMemory.builder()
                .id(memoryId)
                .maxMessages(30)
                .chatMemoryStore(store)
                .build();
    }

    static class FileBackedChatMemoryStore implements ChatMemoryStore {

        private static final Logger log = LoggerFactory.getLogger(FileBackedChatMemoryStore.class);
        private static final int RETENTION_DAYS = 90;

        // memoryId format: "{userId}::{sessionId}"
        private File getMemoryFile(Object memoryId) {
            String[] parts = memoryId.toString().split("::", 2);
            String userId  = parts[0];
            String session = parts.length > 1 ? parts[1] : parts[0];
            File dir = new File(GreenplumAgentApplication.resolveDataDir()
                    + File.separator + "users"
                    + File.separator + userId
                    + File.separator + "memory");
            dir.mkdirs();
            return new File(dir, session + ".json");
        }

        @Override
        public List<ChatMessage> getMessages(Object memoryId) {
            try {
                File file = getMemoryFile(memoryId);
                if (file.exists()) {
                    String json = Files.readString(file.toPath(), StandardCharsets.UTF_8);
                    return ChatMessageDeserializer.messagesFromJson(json);
                }
            } catch (Exception e) {
                log.error("[MEMORY] Failed to read memory for {}: {}", memoryId, e.getMessage());
            }
            return new ArrayList<>();
        }

        @Override
        public void updateMessages(Object memoryId, List<ChatMessage> messages) {
            try {
                File file = getMemoryFile(memoryId);
                String json = ChatMessageSerializer.messagesToJson(messages);
                Files.writeString(file.toPath(), json, StandardCharsets.UTF_8);
                log.info("[MEMORY] Saved {} messages to {}", messages.size(), file.getName());
                cleanupOldMemoryFiles(memoryId);
            } catch (Exception e) {
                log.error("[MEMORY] Failed to save memory for {}: {}", memoryId, e.getMessage());
            }
        }

        @Override
        public void deleteMessages(Object memoryId) {
            try {
                File file = getMemoryFile(memoryId);
                if (file.exists()) {
                    file.delete();
                    log.info("[MEMORY] Deleted file: {}", file.getName());
                }
            } catch (Exception e) {
                log.error("[MEMORY] Failed to delete memory for {}: {}", memoryId, e.getMessage());
            }
        }

        private void cleanupOldMemoryFiles(Object memoryId) {
            try {
                File memoryDir = getMemoryFile(memoryId).getParentFile();
                if (memoryDir == null || !memoryDir.exists()) return;
                Instant cutoff = Instant.now().minus(RETENTION_DAYS, ChronoUnit.DAYS);
                File[] files = memoryDir.listFiles((d, name) -> name.endsWith(".json"));
                if (files == null) return;
                for (File f : files) {
                    if (Instant.ofEpochMilli(f.lastModified()).isBefore(cutoff)) {
                        f.delete();
                        log.info("[MEMORY] Cleaned up expired file: {}", f.getName());
                    }
                }
            } catch (Exception e) {
                log.warn("[MEMORY] Cleanup scan failed: {}", e.getMessage());
            }
        }
    }
}
