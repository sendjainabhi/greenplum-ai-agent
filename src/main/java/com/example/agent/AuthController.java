package com.example.agent;

import org.springframework.web.bind.annotation.*;
import java.util.Map;

/**
 * REST Controller to handle basic administrative authentication.
 * Secures the global settings panel in the frontend UI.
 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {
    
    @PostMapping("/login")
    public Map<String, Boolean> login(@RequestBody Map<String, String> request) {
        String user = request.get("username");
        String pass = request.get("password");
        
        // Simple hardcoded admin validation for demonstration purposes.
        // In a production environment, this should be replaced with Spring Security or a proper DB lookup.
        boolean success = "admin".equals(user) && "admin".equals(pass);
        
        return Map.of("success", success);
    }
}