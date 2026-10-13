package com.gp.agent;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for SchemaIntelligenceService — glossary injection, template hints,
 * SQL keyword detection, and SQL error detection.
 */
class SchemaIntelligenceServiceTest {

    private final SchemaIntelligenceService svc = new SchemaIntelligenceService();

    // -------------------------------------------------------------------------
    // containsSqlKeywords
    // -------------------------------------------------------------------------

    @Test
    void containsSqlKeywords_null_returnsFalse() {
        assertThat(svc.containsSqlKeywords(null)).isFalse();
    }

    @Test
    void containsSqlKeywords_blank_returnsFalse() {
        assertThat(svc.containsSqlKeywords("   ")).isFalse();
    }

    @Test
    void containsSqlKeywords_match_returnsTrue() {
        assertThat(svc.containsSqlKeywords("show me the top customers")).isTrue();
        assertThat(svc.containsSqlKeywords("what is the total revenue")).isTrue();
        assertThat(svc.containsSqlKeywords("how many renewals this quarter")).isTrue();
    }

    @Test
    void containsSqlKeywords_noMatch_returnsFalse() {
        assertThat(svc.containsSqlKeywords("hello, how are you?")).isFalse();
        assertThat(svc.containsSqlKeywords("what is the weather today")).isFalse();
    }

    // -------------------------------------------------------------------------
    // containsSqlError
    // -------------------------------------------------------------------------

    @Test
    void containsSqlError_null_returnsFalse() {
        assertThat(svc.containsSqlError(null)).isFalse();
    }

    @Test
    void containsSqlError_blank_returnsFalse() {
        assertThat(svc.containsSqlError("  ")).isFalse();
    }

    @Test
    void containsSqlError_knownPatterns_returnTrue() {
        assertThat(svc.containsSqlError("column \"foo\" does not exist")).isTrue();
        assertThat(svc.containsSqlError("ERROR: relation \"bar\" does not exist")).isTrue();
        assertThat(svc.containsSqlError("syntax error at or near \"SELECT\"")).isTrue();
        assertThat(svc.containsSqlError("ambiguous column reference")).isTrue();
        assertThat(svc.containsSqlError("invalid input syntax for type integer")).isTrue();
    }

    @Test
    void containsSqlError_normalResponse_returnsFalse() {
        assertThat(svc.containsSqlError("The top 5 customers by revenue are: A, B, C.")).isFalse();
        assertThat(svc.containsSqlError("Here is a summary of the data you requested.")).isFalse();
    }

    // -------------------------------------------------------------------------
    // buildGlossaryContext
    // -------------------------------------------------------------------------

    @Test
    void buildGlossaryContext_nullGlossary_returnsEmpty() {
        assertThat(svc.buildGlossaryContext("revenue", null)).isEmpty();
    }

    @Test
    void buildGlossaryContext_emptyGlossary_returnsEmpty() {
        assertThat(svc.buildGlossaryContext("revenue", List.of())).isEmpty();
    }

    @Test
    void buildGlossaryContext_noMatch_returnsEmpty() {
        List<Map<String, String>> glossary = List.of(
                Map.of("term", "renewal", "column_ref", "renewal_date", "table_ref", "contracts", "rule", "use renewal_date column")
        );
        assertThat(svc.buildGlossaryContext("show me the customers", glossary)).isEmpty();
    }

    @Test
    void buildGlossaryContext_matchingTerm_injectsContext() {
        List<Map<String, String>> glossary = List.of(
                Map.of("term", "renewal", "column_ref", "renewal_date", "table_ref", "contracts", "rule", "use renewal_date column")
        );
        String ctx = svc.buildGlossaryContext("how many renewals are due this quarter?", glossary);
        assertThat(ctx).contains("renewal");
        assertThat(ctx).contains("contracts.renewal_date");
        assertThat(ctx).contains("use renewal_date column");
    }

    @Test
    void buildGlossaryContext_multipleMatches_allInjected() {
        List<Map<String, String>> glossary = List.of(
                Map.of("term", "renewal", "column_ref", "renewal_date", "table_ref", "contracts", "rule", "R1"),
                Map.of("term", "revenue", "column_ref", "arr", "table_ref", "deals", "rule", "R2"),
                Map.of("term", "pod",     "column_ref", "pod_name", "table_ref", "teams", "rule", "R3")
        );
        String ctx = svc.buildGlossaryContext("show revenue by pod with renewal counts", glossary);
        assertThat(ctx).contains("renewal");
        assertThat(ctx).contains("revenue");
        assertThat(ctx).contains("pod");
    }

    @Test
    void buildGlossaryContext_termWithoutTableRef_usesColumnRefOnly() {
        List<Map<String, String>> glossary = List.of(
                Map.of("term", "arr", "column_ref", "arr_value", "rule", "annual recurring revenue")
        );
        String ctx = svc.buildGlossaryContext("what is the arr", glossary);
        assertThat(ctx).contains("arr");
        assertThat(ctx).contains("arr_value");
        assertThat(ctx).doesNotContain("null");
    }

    // -------------------------------------------------------------------------
    // buildTemplateHint
    // -------------------------------------------------------------------------

    @Test
    void buildTemplateHint_nullTemplates_returnsEmpty() {
        assertThat(svc.buildTemplateHint("top customers", null)).isEmpty();
    }

    @Test
    void buildTemplateHint_emptyTemplates_returnsEmpty() {
        assertThat(svc.buildTemplateHint("top customers", List.of())).isEmpty();
    }

    @Test
    void buildTemplateHint_noKeywordMatch_returnsEmpty() {
        List<Map<String, String>> templates = List.of(
                Map.of("keywords", "renewal,contract", "hint_sql", "SELECT * FROM contracts")
        );
        assertThat(svc.buildTemplateHint("show me the weather", templates)).isEmpty();
    }

    @Test
    void buildTemplateHint_singleMatch_returnsHint() {
        List<Map<String, String>> templates = List.of(
                Map.of("keywords", "top,customer,revenue", "hint_sql", "SELECT customer_id, SUM(arr) FROM deals GROUP BY 1 ORDER BY 2 DESC LIMIT 10")
        );
        String hint = svc.buildTemplateHint("show me top customers by revenue", templates);
        assertThat(hint).isEqualTo("SELECT customer_id, SUM(arr) FROM deals GROUP BY 1 ORDER BY 2 DESC LIMIT 10");
    }

    @Test
    void buildTemplateHint_multipleTemplates_bestScoreWins() {
        List<Map<String, String>> templates = List.of(
                Map.of("keywords", "renewal", "hint_sql", "RENEWAL_SQL"),
                Map.of("keywords", "top,customer,revenue", "hint_sql", "TOP_CUSTOMER_SQL")
        );
        // Prompt matches 3 keywords of second template vs 1 of first → second wins
        String hint = svc.buildTemplateHint("show me top customers by revenue", templates);
        assertThat(hint).isEqualTo("TOP_CUSTOMER_SQL");
    }

    @Test
    void buildTemplateHint_caseInsensitiveKeywordMatch() {
        List<Map<String, String>> templates = List.of(
                Map.of("keywords", "Revenue,Customer", "hint_sql", "HINT_SQL")
        );
        String hint = svc.buildTemplateHint("show me revenue per customer", templates);
        assertThat(hint).isEqualTo("HINT_SQL");
    }
}
