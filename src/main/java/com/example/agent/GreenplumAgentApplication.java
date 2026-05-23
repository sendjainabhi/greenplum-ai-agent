package com.example.agent;

import dev.langchain4j.memory.chat.ChatMemoryProvider;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.ollama.OllamaChatModel;
import dev.langchain4j.service.AiServices;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

import java.time.Duration;

@SpringBootApplication
public class GreenplumAgentApplication {

    public static void main(String[] args) {
        SpringApplication.run(GreenplumAgentApplication.class, args);
    }

    @Bean
    public ChatLanguageModel chatLanguageModel(
            @Value("${ollama.server.url}") String ollamaUrl,
            @Value("${ollama.model.name}") String modelName,
            @Value("${ollama.model.timeout-seconds:300}") long timeoutSeconds) {
            
        return OllamaChatModel.builder()
                .baseUrl(ollamaUrl)
                .modelName(modelName)
                .temperature(0.0)
                .timeout(Duration.ofSeconds(timeoutSeconds))
                .build();
    }

    @Bean
    public GreenplumAgent greenplumAgent(ChatLanguageModel model, GreenplumMcpTools mcpTools) {
        // Define a provider factory to cleanly provision distinct memory caches per @MemoryId
        ChatMemoryProvider memoryProvider = memoryId -> MessageWindowChatMemory.withMaxMessages(10);

        return AiServices.builder(GreenplumAgent.class)
                .chatLanguageModel(model)
                .chatMemoryProvider(memoryProvider) // FIXED: Swapped single instance for a structural Provider factory
                .tools(mcpTools)
                .build();
    }
}