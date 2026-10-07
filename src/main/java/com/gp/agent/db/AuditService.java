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
     * Returns a paginated slice of the audit log, optionally filtered by date range and email.
     * from / to are ISO date strings (YYYY-MM-DD); email is a case-insensitive substring match.
     */
    public Map<String, Object> getAuditPage(String from, String to, String email, int page) {
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
        if (email != null && !email.isBlank()) {
            where.append(" AND email ILIKE ?");
            params.add("%" + email.trim() + "%");
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

    /**
     * Returns users who sent a QUERY event within the last {@code minutes} minutes,
     * ordered by most-recent activity first.
     */
    public List<Map<String, Object>> getActiveUsers(int minutes) {
        try {
            return jdbc.queryForList(
                    "SELECT email, " +
                    "  MAX(ts) AS last_seen, " +
                    "  COUNT(CASE WHEN action = 'QUERY' THEN 1 END) AS query_count, " +
                    "  MAX(action) AS last_action " +
                    "FROM user_audit_log " +
                    "WHERE action IN ('LOGIN','QUERY') " +
                    "  AND ts >= NOW() - (? * INTERVAL '1 minute') " +
                    "GROUP BY email " +
                    "ORDER BY last_seen DESC",
                    minutes);
        } catch (Exception e) {
            log.warn("[AUDIT] Active users query failed: {}", e.getMessage());
            return new ArrayList<>();
        }
    }

    /** Summary stats: today / this week / this month token totals per model. */
    public List<Map<String, Object>> getUsageSummary() {
        try {
            return jdbc.queryForList(
                "SELECT model_name, " +
                "  SUM(CASE WHEN day = CURRENT_DATE THEN total_tokens ELSE 0 END) AS today_tokens, " +
                "  SUM(CASE WHEN day >= date_trunc('week',  CURRENT_DATE)::date THEN total_tokens ELSE 0 END) AS week_tokens, " +
                "  SUM(CASE WHEN day >= date_trunc('month', CURRENT_DATE)::date THEN total_tokens ELSE 0 END) AS month_tokens, " +
                "  SUM(total_tokens) AS all_tokens, " +
                "  SUM(call_count)   AS call_count " +
                "FROM model_usage " +
                "GROUP BY model_name " +
                "ORDER BY all_tokens DESC");
        } catch (Exception e) {
            log.warn("[USAGE] Summary query failed: {}", e.getMessage());
            return new ArrayList<>();
        }
    }

    /** Daily token breakdown for last {@code days} days, one row per (day, model_name). */
    public List<Map<String, Object>> getDailyUsage(int days) {
        try {
            return jdbc.queryForList(
                "SELECT day, model_name, call_count, input_tokens, output_tokens, total_tokens " +
                "FROM model_usage " +
                "WHERE day >= CURRENT_DATE - (? * INTERVAL '1 day') " +
                "ORDER BY day DESC, total_tokens DESC",
                days);
        } catch (Exception e) {
            log.warn("[USAGE] Daily usage query failed: {}", e.getMessage());
            return new ArrayList<>();
        }
    }

    // Runs daily at 02:45 — deletes model usage data older than 60 days
    @Scheduled(cron = "0 45 2 * * *")
    public void purgeOldUsageEntries() {
        try {
            int deleted = jdbc.update(
                    "DELETE FROM model_usage WHERE day < CURRENT_DATE - INTERVAL '60 days'");
            if (deleted > 0) log.info("[USAGE] Purged {} usage rows older than 60 days", deleted);
        } catch (Exception e) {
            log.warn("[USAGE] Usage purge failed: {}", e.getMessage());
        }
    }

    // Runs daily at 02:15 — deletes audit entries older than 30 days
    @Scheduled(cron = "0 15 2 * * *")
    public void purgeOldAuditEntries() {
        try {
            int deleted = jdbc.update(
                    "DELETE FROM user_audit_log WHERE ts < NOW() - INTERVAL '30 days'");
            if (deleted > 0) log.info("[AUDIT] Purged {} audit entries older than 30 days", deleted);
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
