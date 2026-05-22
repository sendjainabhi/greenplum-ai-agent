package com.example.agent;

import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.ollama.OllamaChatModel;
import dev.langchain4j.service.AiServices;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

import java.time.Duration; // <-- Don't forget this import!

@SpringBootApplication
public class GreenplumAgentApplication {

    public static void main(String[] args) {
        SpringApplication.run(GreenplumAgentApplication.class, args);
    }

    @Bean
    public ChatLanguageModel chatLanguageModel(
            @Value("${ollama.server.url}") String ollamaUrl,
            @Value("${ollama.model.name}") String modelName,
            @Value("${ollama.model.timeout-seconds:300}") long timeoutSeconds) { // Reads from YAML, defaults to 300 if missing
            
        return OllamaChatModel.builder()
                .baseUrl(ollamaUrl)
                .modelName(modelName) 
                .temperature(0.0)
                .timeout(Duration.ofSeconds(timeoutSeconds)) // <-- Tells Java to wait longer
                .build();
    }

    @Bean
    public GreenplumAgent greenplumAgent(ChatLanguageModel chatLanguageModel, GreenplumMcpTools mcpTools) {
        return AiServices.builder(GreenplumAgent.class)
                .chatLanguageModel(chatLanguageModel)
                .chatMemory(MessageWindowChatMemory.withMaxMessages(10))
                .tools(mcpTools)
                .build();
    }
}