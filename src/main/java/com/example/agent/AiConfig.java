package com.example.agent;

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
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

@Configuration
public class AiConfig {

    // Wire up SLF4J to use your logback-spring.xml
    private static final Logger log = LoggerFactory.getLogger(AiConfig.class);

    @Bean
    public ChatMemoryStore chatMemoryStore() {
        return new ChatMemoryStore() {
            
            private File getUserFile(Object memoryId) {
                String directory = System.getProperty("user.dir");
                return new File(directory + File.separator + memoryId + "-memory.json");
            }

            @Override
            public List<ChatMessage> getMessages(Object memoryId) {
                try {
                    File file = getUserFile(memoryId);
                    if (file.exists()) {
                        String json = new String(Files.readAllBytes(file.toPath()));
                        return ChatMessageDeserializer.messagesFromJson(json);
                    }
                } catch (Exception e) {
                    log.error("❌ [MEMORY] Failed to read memory: {}", e.getMessage());
                }
                return new ArrayList<>(); 
            }

            @Override
            public void updateMessages(Object memoryId, List<ChatMessage> messages) {
                try {
                    File file = getUserFile(memoryId);
                    String json = ChatMessageSerializer.messagesToJson(messages);
                    Files.write(file.toPath(), json.getBytes());
                    log.info("💾 [MEMORY] Saved {} messages to {}", messages.size(), file.getName());
                } catch (Exception e) {
                    log.error("❌ [MEMORY] Failed to save memory: {}", e.getMessage());
                }
            }

            @Override
            public void deleteMessages(Object memoryId) {
                try {
                    File file = getUserFile(memoryId);
                    if (file.exists()) {
                        file.delete();
                        log.info("🗑️ [MEMORY] Deleted file: {}", file.getName());
                    }
                } catch (Exception e) {
                    log.error("❌ [MEMORY] Failed to delete memory: {}", e.getMessage());
                }
            }
        };
    }

    @Bean
    public ChatMemoryProvider chatMemoryProvider(ChatMemoryStore store) {
        return memoryId -> MessageWindowChatMemory.builder()
                .id(memoryId)
                .maxMessages(30) 
                .chatMemoryStore(store)
                .build();
    }
}