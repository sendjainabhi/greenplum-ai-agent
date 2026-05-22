package com.example.agent;

import org.springframework.web.bind.annotation.*;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class ChatController {

    private final GreenplumAgent agent;

    public ChatController(GreenplumAgent agent) {
        this.agent = agent;
    }

    @PostMapping("/chat")
    public Map<String, String> chat(@RequestBody Map<String, String> request) {
        String prompt = request.get("prompt");
        String response = agent.chat(prompt);
        return Map.of("response", response);
    }
}