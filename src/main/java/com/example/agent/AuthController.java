package com.example.agent;

import org.springframework.web.bind.annotation.*;
import java.util.Map;

@RestController
@RequestMapping("/api/auth")
public class AuthController {
    
    @PostMapping("/login")
    public Map<String, Boolean> login(@RequestBody Map<String, String> request) {
        String user = request.get("username");
        String pass = request.get("password");
        
        // Single admin user validation
        boolean success = "admin".equals(user) && "admin".equals(pass);
        
        return Map.of("success", success);
    }
}