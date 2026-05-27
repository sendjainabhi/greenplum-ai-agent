package com.example.agent;

import dev.langchain4j.memory.chat.ChatMemoryProvider;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.ollama.OllamaChatModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.anthropic.AnthropicChatModel;
import dev.langchain4j.service.AiServices;
import org.springframework.web.bind.annotation.*;

import java.time.Duration;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class ChatController {

    private static final String SESSION_ID = "web-session-1"; 
    
    private final ChatMemoryProvider memoryProvider = memoryId -> MessageWindowChatMemory.withMaxMessages(10);

    @PostMapping("/chat")
    public Map<String, String> chat(@RequestBody Map<String, String> request) {
        
        String prompt = request.get("prompt");
        String provider = request.getOrDefault("provider", "ollama").toLowerCase();
        String modelName = request.getOrDefault("modelName", "qwen3:30b");
        String apiKey = request.get("apiKey");
        String baseUrl = request.get("baseUrl");
        
        String mcpUrl = request.get("mcpUrl");
        String mcpAuth = request.get("mcpAuth");

        ChatLanguageModel model = buildModel(provider, modelName, apiKey, baseUrl, 300);
        GreenplumMcpTools mcpTools = new GreenplumMcpTools(mcpUrl, mcpAuth);

        GreenplumAgent agent = AiServices.builder(GreenplumAgent.class)
                .chatLanguageModel(model)
                .chatMemoryProvider(memoryProvider)
                .tools(mcpTools)
                .build();
        
        String response = agent.chat(SESSION_ID, prompt);
        
        return Map.of("response", response);
    }

    // --- NEW: Test Connection Endpoint ---
    @PostMapping("/test")
    public Map<String, String> testConnection(@RequestBody Map<String, String> request) {
        String provider = request.getOrDefault("provider", "ollama").toLowerCase();
        String modelName = request.getOrDefault("modelName", "qwen3:30b");
        String apiKey = request.get("apiKey");
        String baseUrl = request.get("baseUrl");

        try {
            // Build the model with a short 15-second timeout for testing
            ChatLanguageModel model = buildModel(provider, modelName, apiKey, baseUrl, 15);
            
            // Send a tiny prompt to verify connectivity and API keys
            String response = model.generate("Respond with the exact word: OK");
            
            return Map.of("status", "success", "message", "Connection Successful! AI responded: " + response);
        } catch (Exception e) {
            return Map.of("status", "error", "message", "Connection Failed: " + e.getMessage());
        }
    }

    // Helper method to keep code DRY (Don't Repeat Yourself)
    private ChatLanguageModel buildModel(String provider, String modelName, String apiKey, String baseUrl, int timeoutSeconds) {
        switch (provider) {
            case "openai":
                var openAiBuilder = OpenAiChatModel.builder()
                        .apiKey(apiKey)
                        .modelName(modelName)
                        .temperature(0.0)
                        .timeout(Duration.ofSeconds(timeoutSeconds));
                if (baseUrl != null && !baseUrl.trim().isEmpty()) {
                    openAiBuilder.baseUrl(baseUrl);
                }
                return openAiBuilder.build();
                
            case "anthropic":
                var anthropicBuilder = AnthropicChatModel.builder()
                        .apiKey(apiKey)
                        .modelName(modelName)
                        .temperature(0.0)
                        .timeout(Duration.ofSeconds(timeoutSeconds));
                if (baseUrl != null && !baseUrl.trim().isEmpty()) {
                    anthropicBuilder.baseUrl(baseUrl);
                }
                return anthropicBuilder.build();
                
            case "ollama":
            default:
                String ollamaUrl = (baseUrl != null && !baseUrl.trim().isEmpty()) ? baseUrl : "http://localhost:11434";
                return OllamaChatModel.builder()
                        .baseUrl(ollamaUrl)
                        .modelName(modelName)
                        .temperature(0.0)
                        .timeout(Duration.ofSeconds(timeoutSeconds))
                        .build();
        }
    }
}