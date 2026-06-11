package com.example.agent;

import dev.langchain4j.memory.chat.ChatMemoryProvider;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.ollama.OllamaChatModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.anthropic.AnthropicChatModel;
import dev.langchain4j.service.AiServices;
import dev.langchain4j.store.memory.chat.ChatMemoryStore;
import org.springframework.web.bind.annotation.*;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.time.Duration;
import java.util.Map;
import java.util.Properties;

@RestController
@RequestMapping("/api")
public class ChatController {

    private static final String CONFIG_FILE = "agent-config.properties";
    private volatile Properties cachedConfig = null; 
    private final ChatMemoryProvider memoryProvider;
    private final ChatMemoryStore memoryStore;

    public ChatController(ChatMemoryProvider memoryProvider, ChatMemoryStore memoryStore) {
        this.memoryProvider = memoryProvider;
        this.memoryStore = memoryStore;
    }

    private Properties loadConfig() {
        if (cachedConfig != null) return cachedConfig;
        Properties props = new Properties();
        try {
            File f = new File(CONFIG_FILE);
            if (f.exists()) props.load(new FileInputStream(f));
        } catch (Exception e) { e.printStackTrace(); }
        cachedConfig = props; 
        return props;
    }

    @GetMapping("/settings")
    public Properties getSettings() {
        return loadConfig();
    }

    @PostMapping("/settings")
    public Map<String, Boolean> saveSettings(@RequestBody Map<String, String> req) {
        Properties props = new Properties();
        props.putAll(req);
        try {
            props.store(new FileOutputStream(CONFIG_FILE), "Global Agent Configuration");
            cachedConfig = props; 
            return Map.of("success", true);
        } catch (Exception e) {
            return Map.of("success", false);
        }
    }

    @PostMapping("/chat")
    public Map<String, String> chat(@RequestBody Map<String, String> request) {
        String prompt = request.get("prompt");
        String userId = request.get("userId"); 
        if (userId == null || userId.trim().isEmpty()) userId = "default-user";
        
        Properties config = loadConfig();
        
        String provider = config.getProperty("provider", "ollama").toLowerCase();
        String modelName = config.getProperty("modelName", "");
        String apiKey = config.getProperty("apiKey", "");
        String baseUrl = config.getProperty("baseUrl", "");
        String mcpUrl = config.getProperty("mcpUrl", "");
        String mcpAuth = config.getProperty("mcpAuth", "");
        
        // Grab the custom system instructions provided by the user
        String customSystemPrompt = config.getProperty("systemPrompt", "");

        if (modelName == null || modelName.trim().isEmpty()) {
            return Map.of("response", "⚠️ **Configuration Required:** Please click the Settings button and configure an AI Provider and Model Name before chatting.");
        }

        ChatLanguageModel model = buildModel(provider, modelName, apiKey, baseUrl, 300);
        GreenplumMcpTools mcpTools = new GreenplumMcpTools(mcpUrl, mcpAuth);

        GreenplumAgent agent = AiServices.builder(GreenplumAgent.class)
                .chatLanguageModel(model)
                .chatMemoryProvider(memoryProvider)
                .tools(mcpTools)
                .build();
        
        // Construct the enforced rule set
        String enforcedPrompt = prompt + "\n\n[SYSTEM REMINDER: 1. You MUST actively use the `executeQuery` tool to fetch the actual data. Do not just write SQL without running it. 2. In your final response, display the exact SQL query used inside a ```sql markdown block, followed by the formatted data results.";
        
        // Inject the user's custom instructions into the system barrier if they exist
        if (!customSystemPrompt.trim().isEmpty()) {
            enforcedPrompt += "\n\nUSER CUSTOM INSTRUCTIONS:\n" + customSystemPrompt;
        }
        
        enforcedPrompt += "]";
        
        String response = agent.chat(userId, enforcedPrompt);
        return Map.of("response", response);
    }
    
    @PostMapping("/chat/clear")
    public Map<String, Boolean> clearChat(@RequestBody Map<String, String> request) {
        String userId = request.get("userId");
        if (userId != null && !userId.trim().isEmpty()) memoryStore.deleteMessages(userId);
        return Map.of("success", true);
    }

    @PostMapping("/test")
    public Map<String, String> testConnection(@RequestBody Map<String, String> request) {
        String provider = request.getOrDefault("provider", "ollama").toLowerCase();
        String modelName = request.getOrDefault("modelName", "");
        String apiKey = request.get("apiKey");
        String baseUrl = request.get("baseUrl");

        if (modelName == null || modelName.trim().isEmpty()) {
            return Map.of("status", "error", "message", "Model Name cannot be empty.");
        }

        try {
            ChatLanguageModel model = buildModel(provider, modelName, apiKey, baseUrl, 90);
            String response = model.generate("Respond with the exact word: OK");
            return Map.of("status", "success", "message", "Connection Successful! AI responded: " + response);
        } catch (Exception e) {
            return Map.of("status", "error", "message", "Connection Failed: " + e.getMessage());
        }
    }

    private ChatLanguageModel buildModel(String provider, String modelName, String apiKey, String baseUrl, int timeoutSeconds) {
        Duration timeout = Duration.ofSeconds(timeoutSeconds);

        switch (provider) {
            case "openai":
                var openAiBuilder = OpenAiChatModel.builder()
                        .apiKey(apiKey).modelName(modelName).temperature(0.0)
                        .timeout(timeout).maxRetries(1);
                if (baseUrl != null && !baseUrl.trim().isEmpty()) openAiBuilder.baseUrl(baseUrl);
                return openAiBuilder.build();
                
            case "anthropic":
                var anthropicBuilder = AnthropicChatModel.builder()
                        .apiKey(apiKey).modelName(modelName).temperature(0.0)
                        .timeout(timeout).maxRetries(1);
                if (baseUrl != null && !baseUrl.trim().isEmpty()) anthropicBuilder.baseUrl(baseUrl);
                return anthropicBuilder.build();
                
            case "ollama":
            default:
                String ollamaUrl = (baseUrl != null && !baseUrl.trim().isEmpty()) ? baseUrl : "http://localhost:11434";
                return OllamaChatModel.builder()
                        .baseUrl(ollamaUrl).modelName(modelName).temperature(0.0)
                        .timeout(timeout).maxRetries(1)
                        .build();
        }
    }
}