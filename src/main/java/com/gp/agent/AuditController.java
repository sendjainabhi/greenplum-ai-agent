package com.gp.agent;

import com.gp.agent.db.AuditService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/admin")
@ConditionalOnProperty(name = "gp.agent.storage", havingValue = "postgres")
public class AuditController {

    private final AuditService auditService;

    public AuditController(AuditService auditService) {
        this.auditService = auditService;
    }

    @GetMapping("/audit")
    ResponseEntity<Map<String, Object>> getAuditLog(
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            @RequestParam(required = false) String email,
            @RequestParam(defaultValue = "0") int page,
            Authentication authentication) {

        if (!isPermanentAdmin(resolveEmail(authentication))) {
            return ResponseEntity.status(403).body(Map.of("error", "Access denied"));
        }
        if (auditService == null) {
            return ResponseEntity.ok(Map.of("rows", java.util.List.of(), "total", 0,
                    "page", 0, "pageSize", 50, "totalPages", 1));
        }
        return ResponseEntity.ok(auditService.getAuditPage(from, to, email, Math.max(0, page)));
    }

    @GetMapping("/active-users")
    ResponseEntity<?> getActiveUsers(
            @RequestParam(defaultValue = "30") int minutes,
            Authentication authentication) {

        if (!isPermanentAdmin(resolveEmail(authentication))) {
            return ResponseEntity.status(403).body(Map.of("error", "Access denied"));
        }
        int window = Math.min(Math.max(minutes, 1), 1440); // clamp 1 min – 24 h
        return ResponseEntity.ok(Map.of(
                "users",   auditService == null ? java.util.List.of() : auditService.getActiveUsers(window),
                "minutes", window));
    }

    @GetMapping("/usage")
    ResponseEntity<?> getUsage(
            @RequestParam(defaultValue = "7") int days,
            Authentication authentication) {

        if (!isPermanentAdmin(resolveEmail(authentication))) {
            return ResponseEntity.status(403).body(Map.of("error", "Access denied"));
        }
        int window = Math.min(Math.max(days, 1), 90);
        List<Map<String, Object>> summary = auditService == null ? List.of() : auditService.getUsageSummary();
        List<Map<String, Object>> daily   = auditService == null ? List.of() : auditService.getDailyUsage(window);
        return ResponseEntity.ok(Map.of("summary", summary, "daily", daily, "days", window));
    }

    private String resolveEmail(Authentication authentication) {
        if (authentication == null) return "";
        if (authentication.getPrincipal() instanceof OidcUser oidcUser) {
            String email = oidcUser.getAttribute("user_name");
            if (email == null || email.isBlank()) email = oidcUser.getEmail();
            return email != null ? email.toLowerCase().trim() : "";
        }
        return "";
    }

    static boolean isPermanentAdmin(String email) {
        if (email == null || email.isBlank()) return false;
        String env = System.getenv("PERMANENT_ADMIN_EMAILS");
        if (env == null || env.isBlank()) return false;
        String lc = email.toLowerCase().trim();
        for (String e : env.split(",")) {
            if (lc.equals(e.trim().toLowerCase())) return true;
        }
        return false;
    }
}
