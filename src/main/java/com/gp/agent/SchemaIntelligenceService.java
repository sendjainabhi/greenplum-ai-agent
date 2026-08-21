package com.gp.agent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Provides schema intelligence improvements:
 *  1. Session-first-message detection → triggers schema pre-load instruction
 *  2. Business glossary keyword matching → injects authoritative term→column rules
 *  3. Query template keyword matching → injects canonical SQL hints
 *  4. SQL error detection → drives self-correction retry in ChatController
 *  5. In-process session registry → avoids redundant pre-load on repeated turns
 */
@Service
public class SchemaIntelligenceService {

    private static final Logger log = LoggerFactory.getLogger(SchemaIntelligenceService.class);

    // Sessions that have already received a schema pre-load instruction this instance lifetime.
    private final Set<String> preinitializedSessions = ConcurrentHashMap.newKeySet();

    // SQL error patterns that indicate the AI produced a broken query
    private static final List<String> SQL_ERROR_PATTERNS = List.of(
            "does not exist",
            "column \"",
            "relation \"",
            "ERROR:",
            "syntax error",
            "ambiguous column",
            "operator does not exist",
            "invalid input syntax"
    );

    // -------------------------------------------------------------------------
    // 1 & 5 — Session pre-load tracking
    // -------------------------------------------------------------------------

    /**
     * Returns true if this is the first chat turn for memoryId in the current
     * app instance.  Also marks the session as seen so the next call returns false.
     */
    public boolean isNewSessionAndMark(String memoryId,
                                       dev.langchain4j.store.memory.chat.ChatMemoryStore store) {
        if (preinitializedSessions.contains(memoryId)) return false;
        // Treat sessions with 0 stored messages as new regardless of the in-process set.
        try {
            boolean hasMessages = !store.getMessages(memoryId).isEmpty();
            if (hasMessages) {
                preinitializedSessions.add(memoryId);
                return false;
            }
        } catch (Exception e) {
            log.debug("[SCHEMA] Could not read memory for {}: {}", memoryId, e.getMessage());
        }
        preinitializedSessions.add(memoryId);
        return true;
    }

    // -------------------------------------------------------------------------
    // 2 — Business glossary injection
    // -------------------------------------------------------------------------

    /**
     * Scans the user prompt for glossary term matches (case-insensitive) and returns
     * a formatted block ready to append to the LLM prompt.
     * Returns empty string when no matches or glossary is empty.
     */
    public String buildGlossaryContext(String userPrompt,
                                       List<Map<String, String>> glossaryEntries) {
        if (glossaryEntries == null || glossaryEntries.isEmpty()) return "";
        String lowerPrompt = userPrompt.toLowerCase();
        StringBuilder sb = new StringBuilder();
        for (Map<String, String> entry : glossaryEntries) {
            String term = entry.getOrDefault("term", "");
            if (term.isBlank()) continue;
            if (lowerPrompt.contains(term.toLowerCase())) {
                sb.append("• ").append(term);
                String col = entry.getOrDefault("column_ref", "");
                String tbl = entry.getOrDefault("table_ref", "");
                if (!col.isBlank()) sb.append(" → ").append(tbl.isBlank() ? col : tbl + "." + col);
                sb.append(": ").append(entry.getOrDefault("rule", "")).append("\n");
            }
        }
        return sb.toString().trim();
    }

    // -------------------------------------------------------------------------
    // 3 — Query template hint injection
    // -------------------------------------------------------------------------

    /**
     * Scans the user prompt for template keyword matches and returns the hint SQL
     * of the best-matching template.  Returns empty string when no match.
     */
    public String buildTemplateHint(String userPrompt,
                                    List<Map<String, String>> templates) {
        if (templates == null || templates.isEmpty()) return "";
        String lowerPrompt = userPrompt.toLowerCase();
        String bestHint    = "";
        int    bestScore   = 0;

        for (Map<String, String> t : templates) {
            String keywords = t.getOrDefault("keywords", "");
            if (keywords.isBlank()) continue;
            int score = (int) Arrays.stream(keywords.split(","))
                    .map(String::trim)
                    .filter(k -> !k.isBlank() && lowerPrompt.contains(k.toLowerCase()))
                    .count();
            if (score > bestScore) {
                bestScore = score;
                bestHint  = t.getOrDefault("hint_sql", "");
            }
        }
        return bestScore > 0 ? bestHint.trim() : "";
    }

    // -------------------------------------------------------------------------
    // 5 — SQL keyword detection (drives schema reminder injection)
    // -------------------------------------------------------------------------

    private static final List<String> SQL_KEYWORDS = List.of(
            "show", "list", "top", "count", "how many", "total", "sum",
            "average", "revenue", "customer", "renewal", "pipeline",
            "quarter", "region", "pod", "product", "sku", "entitlement"
    );

    public boolean containsSqlKeywords(String prompt) {
        if (prompt == null || prompt.isBlank()) return false;
        String lower = prompt.toLowerCase();
        return SQL_KEYWORDS.stream().anyMatch(lower::contains);
    }

    // -------------------------------------------------------------------------
    // 4 — SQL error detection
    // -------------------------------------------------------------------------

    /**
     * Returns true when the AI response contains patterns that indicate a failed
     * SQL execution — used to trigger a single self-correction retry.
     */
    public boolean containsSqlError(String response) {
        if (response == null || response.isBlank()) return false;
        String lower = response.toLowerCase();
        return SQL_ERROR_PATTERNS.stream().anyMatch(p -> lower.contains(p.toLowerCase()));
    }
}
