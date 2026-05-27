package com.example.agent;

import dev.langchain4j.memory.chat.ChatMemoryProvider;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.model.ollama.OllamaChatModel;
import dev.langchain4j.service.AiServices;
import org.springframework.web.bind.annotation.*;

import java.time.Duration;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class ChatController {

    private static final String SESSION_ID = "web-session-1"; // Tracks the continuous user session
    
    // We keep the memory provider at the controller level so context history persists across dynamic requests
    private final ChatMemoryProvider memoryProvider = memoryId -> MessageWindowChatMemory.withMaxMessages(10);

    @PostMapping("/chat")
    public Map<String, String> chat(@RequestBody Map<String, String> request) {
        
        // 1. Extract values dynamically sent from the GUI
        String prompt = request.get("prompt");
        String ollamaUrl = request.getOrDefault("ollamaUrl", "http://localhost:11434");
        String modelName = request.getOrDefault("modelName", "qwen3:30b");
        String mcpUrl = request.get("mcpUrl");
        String mcpAuth = request.get("mcpAuth");

        // 2. Build the Model dynamically
        OllamaChatModel model = OllamaChatModel.builder()
                .baseUrl(ollamaUrl)
                .modelName(modelName)
                .temperature(0.0)
                .timeout(Duration.ofSeconds(300))
                .build();

        // 3. Build the MCP Tools dynamically
        GreenplumMcpTools mcpTools = new GreenplumMcpTools(mcpUrl, mcpAuth);

        // 4. Construct the Agent on-the-fly
        GreenplumAgent agent = AiServices.builder(GreenplumAgent.class)
                .chatLanguageModel(model)
                .chatMemoryProvider(memoryProvider)
                .tools(mcpTools)
                .build();
        
        // Execute the prompt using the dynamically generated agent
        String response = agent.chat(SESSION_ID, prompt);
        
        return Map.of("response", response);
    }
}