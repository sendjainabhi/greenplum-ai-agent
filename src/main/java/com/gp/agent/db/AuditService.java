package com.gp.agent.db;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
@ConditionalOnProperty(name = "gp.agent.storage", havingValue = "postgres")
public class AuditService {

    private static final Logger log = LoggerFactory.getLogger(AuditService.class);
    private static final int PAGE_SIZE = 50;

    private final JdbcTemplate jdbc;

    public AuditService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void log(String email, String action) {
        try {
            jdbc.update("INSERT INTO user_audit_log(email, action, ts) VALUES (?, ?, NOW())",
                    email, action);
        } catch (Exception e) {
            // Never let audit failures surface to the caller
            AuditService.log.warn("[AUDIT] Failed to log event email={} action={}: {}", email, action, e.getMessage());
        }
    }

    /**
     * Returns a paginated slice of the audit log, optionally filtered by date range.
     * from / to are ISO date strings (YYYY-MM-DD); either may be null.
     */
    public Map<String, Object> getAuditPage(String from, String to, int page) {
        List<Object> params = new ArrayList<>();
        StringBuilder where = new StringBuilder();

        if (from != null && !from.isBlank()) {
            where.append(" AND ts >= ?::date");
            params.add(from.trim());
        }
        if (to != null && !to.isBlank()) {
            where.append(" AND ts < (?::date + INTERVAL '1 day')");
            params.add(to.trim());
        }

        String baseWhere = where.isEmpty() ? "" : " WHERE 1=1" + where;

        int total = 0;
        try {
            Integer count = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM user_audit_log" + baseWhere,
                    Integer.class, params.toArray());
            total = count != null ? count : 0;
        } catch (Exception e) {
            log.warn("[AUDIT] Count query failed: {}", e.getMessage());
        }

        int offset = page * PAGE_SIZE;
        List<Object> queryParams = new ArrayList<>(params);
        queryParams.add(PAGE_SIZE);
        queryParams.add(offset);

        List<Map<String, Object>> rows = new ArrayList<>();
        try {
            rows = jdbc.queryForList(
                    "SELECT email, action, ts FROM user_audit_log"
                    + baseWhere
                    + " ORDER BY ts DESC LIMIT ? OFFSET ?",
                    queryParams.toArray());
        } catch (Exception e) {
            log.warn("[AUDIT] Row query failed: {}", e.getMessage());
        }

        int totalPages = total == 0 ? 1 : (int) Math.ceil((double) total / PAGE_SIZE);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("rows",       rows);
        result.put("total",      total);
        result.put("page",       page);
        result.put("pageSize",   PAGE_SIZE);
        result.put("totalPages", totalPages);
        return result;
    }

    // Runs daily at 02:15 — deletes audit entries older than 15 days
    @Scheduled(cron = "0 15 2 * * *")
    public void purgeOldAuditEntries() {
        try {
            int deleted = jdbc.update(
                    "DELETE FROM user_audit_log WHERE ts < NOW() - INTERVAL '15 days'");
            if (deleted > 0) log.info("[AUDIT] Purged {} audit entries older than 15 days", deleted);
        } catch (Exception e) {
            log.warn("[AUDIT] Audit purge failed: {}", e.getMessage());
        }
    }

    // Runs daily at 02:30 — deletes chat memory sessions not updated in the last 30 days
    @Scheduled(cron = "0 30 2 * * *")
    public void purgeOldChatMemory() {
        try {
            int deleted = jdbc.update(
                    "DELETE FROM user_memory WHERE updated_at < NOW() - INTERVAL '30 days'");
            if (deleted > 0) log.info("[MEMORY] Purged {} chat memory sessions older than 30 days", deleted);
        } catch (Exception e) {
            log.warn("[MEMORY] Memory purge failed: {}", e.getMessage());
        }
    }
}
