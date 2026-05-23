package com.example.agent;

import org.springframework.web.bind.annotation.*;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class ChatController {

    private final GreenplumAgent agent;
    private static final String SESSION_ID = "web-session-1"; // Tracks the continuous user session

    public ChatController(GreenplumAgent agent) {
        this.agent = agent;
    }

    @PostMapping("/chat")
    public Map<String, String> chat(@RequestBody Map<String, String> request) {
        String prompt = request.get("prompt");
        
        // Pass the session ID along with the prompt
        String response = agent.chat(SESSION_ID, prompt);
        
        return Map.of("response", response);
    }
}