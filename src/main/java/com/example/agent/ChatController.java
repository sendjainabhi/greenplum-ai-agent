package com.example.agent;

import dev.langchain4j.memory.chat.ChatMemoryProvider;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.ollama.OllamaChatModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.anthropic.AnthropicChatModel;
import dev.langchain4j.service.AiServices;
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

    private static final String SESSION_ID = "web-session-1"; 
    private static final String CONFIG_FILE = "agent-config.properties";
    private final ChatMemoryProvider memoryProvider = memoryId -> MessageWindowChatMemory.withMaxMessages(10);

    private Properties loadConfig() {
        Properties props = new Properties();
        try {
            File f = new File(CONFIG_FILE);
            if(f.exists()) props.load(new FileInputStream(f));
        } catch (Exception e) { e.printStackTrace(); }
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
            return Map.of("success", true);
        } catch (Exception e) {
            return Map.of("success", false);
        }
    }

    @PostMapping("/chat")
    public Map<String, String> chat(@RequestBody Map<String, String> request) {
        String prompt = request.get("prompt");
        
        Properties config = loadConfig();
        String provider = config.getProperty("provider", "ollama").toLowerCase();
        String modelName = config.getProperty("modelName", "qwen3:30b");
        String apiKey = config.getProperty("apiKey", "");
        String baseUrl = config.getProperty("baseUrl", "");
        String mcpUrl = config.getProperty("mcpUrl", "");
        String mcpAuth = config.getProperty("mcpAuth", "");

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

    @PostMapping("/test")
    public Map<String, String> testConnection(@RequestBody Map<String, String> request) {
        String provider = request.getOrDefault("provider", "ollama").toLowerCase();
        String modelName = request.getOrDefault("modelName", "qwen3:30b");
        String apiKey = request.get("apiKey");
        String baseUrl = request.get("baseUrl");

        try {
            ChatLanguageModel model = buildModel(provider, modelName, apiKey, baseUrl, 15);
            String response = model.generate("Respond with the exact word: OK");
            return Map.of("status", "success", "message", "Connection Successful! AI responded: " + response);
        } catch (Exception e) {
            return Map.of("status", "error", "message", "Connection Failed: " + e.getMessage());
        }
    }

    private ChatLanguageModel buildModel(String provider, String modelName, String apiKey, String baseUrl, int timeoutSeconds) {
        switch (provider) {
            case "openai":
                var openAiBuilder = OpenAiChatModel.builder()
                        .apiKey(apiKey).modelName(modelName).temperature(0.0).timeout(Duration.ofSeconds(timeoutSeconds));
                if (baseUrl != null && !baseUrl.trim().isEmpty()) openAiBuilder.baseUrl(baseUrl);
                return openAiBuilder.build();
            case "anthropic":
                var anthropicBuilder = AnthropicChatModel.builder()
                        .apiKey(apiKey).modelName(modelName).temperature(0.0).timeout(Duration.ofSeconds(timeoutSeconds));
                if (baseUrl != null && !baseUrl.trim().isEmpty()) anthropicBuilder.baseUrl(baseUrl);
                return anthropicBuilder.build();
            case "ollama":
            default:
                String ollamaUrl = (baseUrl != null && !baseUrl.trim().isEmpty()) ? baseUrl : "http://localhost:11434";
                return OllamaChatModel.builder()
                        .baseUrl(ollamaUrl).modelName(modelName).temperature(0.0).timeout(Duration.ofSeconds(timeoutSeconds))
                        .build();
        }
    }
}