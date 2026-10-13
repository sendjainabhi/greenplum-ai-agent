package com.gp.agent.db;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.data.message.ChatMessage;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for MessageSanitizer — the JSON-level patch pass that runs once on
 * first DB load to fix corrupted stored messages before LangChain4j deserializes them.
 */
class MessageSanitizerTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    // -------------------------------------------------------------------------
    // fromJson — integration-level (patch + deserialize)
    // -------------------------------------------------------------------------

    @Test
    void fromJson_null_returnsEmpty() {
        assertThat(MessageSanitizer.fromJson(null)).isEmpty();
    }

    @Test
    void fromJson_blank_returnsEmpty() {
        assertThat(MessageSanitizer.fromJson("  ")).isEmpty();
    }

    @Test
    void fromJson_validMessages_deserialized() {
        String json = """
                [
                  {"type":"USER","contents":[{"type":"TEXT","text":"hello"}]},
                  {"type":"AI","text":"world"}
                ]
                """;
        List<ChatMessage> msgs = MessageSanitizer.fromJson(json);
        assertThat(msgs).hasSize(2);
    }

    // -------------------------------------------------------------------------
    // patch — unit tests on the JSON transformer
    // -------------------------------------------------------------------------

    @Test
    void patch_validMessages_passedThrough() throws Exception {
        String json = """
                [
                  {"type":"USER","contents":[{"type":"TEXT","text":"hello"}]},
                  {"type":"AI","text":"world"}
                ]
                """;
        String patched = MessageSanitizer.patch(json);
        JsonNode out = MAPPER.readTree(patched);
        assertThat(out).hasSize(2);
        assertThat(out.get(0).path("type").asText()).isEqualTo("USER");
        assertThat(out.get(1).path("type").asText()).isEqualTo("AI");
        assertThat(out.get(1).path("text").asText()).isEqualTo("world");
    }

    @Test
    void patch_aiMessageWithNullText_replacedWithDot() throws Exception {
        String json = """
                [
                  {"type":"AI"}
                ]
                """;
        String patched = MessageSanitizer.patch(json);
        JsonNode node = MAPPER.readTree(patched).get(0);
        assertThat(node.path("text").asText()).isEqualTo(".");
    }

    @Test
    void patch_aiMessageWithBlankText_replacedWithDot() throws Exception {
        String json = """
                [
                  {"type":"AI","text":""}
                ]
                """;
        String patched = MessageSanitizer.patch(json);
        assertThat(MAPPER.readTree(patched).get(0).path("text").asText()).isEqualTo(".");
    }

    @Test
    void patch_toolResultWithNullText_replacedWithNoResult() throws Exception {
        String json = """
                [
                  {"type":"AI","text":"ok","toolExecutionRequests":[{"id":"t1","name":"executeQuery","arguments":"{}"}]},
                  {"type":"TOOL_EXECUTION_RESULT","id":"t1","toolName":"executeQuery"}
                ]
                """;
        String patched = MessageSanitizer.patch(json);
        JsonNode out = MAPPER.readTree(patched);
        assertThat(out).hasSize(2);
        assertThat(out.get(1).path("text").asText()).isEqualTo("(no result)");
    }

    @Test
    void patch_toolResultWithBlankText_replacedWithNoResult() throws Exception {
        String json = """
                [
                  {"type":"AI","text":"ok","toolExecutionRequests":[{"id":"t1","name":"executeQuery","arguments":"{}"}]},
                  {"type":"TOOL_EXECUTION_RESULT","id":"t1","toolName":"executeQuery","text":""}
                ]
                """;
        String patched = MessageSanitizer.patch(json);
        JsonNode result = MAPPER.readTree(patched).get(1);
        assertThat(result.path("text").asText()).isEqualTo("(no result)");
    }

    @Test
    void patch_aiMessageWithUnmatchedToolRequest_requestRemoved() throws Exception {
        // AI has tool request t1 but no TOOL_EXECUTION_RESULT with id t1 exists
        String json = """
                [
                  {"type":"AI","text":".","toolExecutionRequests":[{"id":"t1","name":"executeQuery","arguments":"{}"}]}
                ]
                """;
        String patched = MessageSanitizer.patch(json);
        JsonNode aiNode = MAPPER.readTree(patched).get(0);
        // toolExecutionRequests should be removed since t1 has no result
        assertThat(aiNode.has("toolExecutionRequests")).isFalse();
    }

    @Test
    void patch_orphanedToolResult_dropped() throws Exception {
        // TOOL_EXECUTION_RESULT with id t99 but no AI message has t99 in its tool requests
        String json = """
                [
                  {"type":"USER","contents":[{"type":"TEXT","text":"hi"}]},
                  {"type":"TOOL_EXECUTION_RESULT","id":"t99","toolName":"executeQuery","text":"data"}
                ]
                """;
        String patched = MessageSanitizer.patch(json);
        JsonNode out = MAPPER.readTree(patched);
        // Orphaned tool result must be dropped — only USER remains
        assertThat(out).hasSize(1);
        assertThat(out.get(0).path("type").asText()).isEqualTo("USER");
    }

    @Test
    void patch_matchedToolCallAndResult_bothPreserved() throws Exception {
        String json = """
                [
                  {"type":"AI","text":".","toolExecutionRequests":[{"id":"t1","name":"executeQuery","arguments":"{}"}]},
                  {"type":"TOOL_EXECUTION_RESULT","id":"t1","toolName":"executeQuery","text":"result data"}
                ]
                """;
        String patched = MessageSanitizer.patch(json);
        JsonNode out = MAPPER.readTree(patched);
        assertThat(out).hasSize(2);
        assertThat(out.get(0).path("type").asText()).isEqualTo("AI");
        assertThat(out.get(1).path("type").asText()).isEqualTo("TOOL_EXECUTION_RESULT");
        assertThat(out.get(1).path("text").asText()).isEqualTo("result data");
    }

    @Test
    void patch_mixedValidAndInvalidMessages_onlyInvalidPatched() throws Exception {
        // One good AI message + one bad AI (null text) → only bad one gets "."
        String json = """
                [
                  {"type":"AI","text":"I am fine."},
                  {"type":"AI"}
                ]
                """;
        String patched = MessageSanitizer.patch(json);
        JsonNode out = MAPPER.readTree(patched);
        assertThat(out.get(0).path("text").asText()).isEqualTo("I am fine.");
        assertThat(out.get(1).path("text").asText()).isEqualTo(".");
    }

    @Test
    void patch_nonArrayInput_returnedAsIs() throws Exception {
        String json = "{\"type\":\"USER\"}";
        String patched = MessageSanitizer.patch(json);
        assertThat(patched).isEqualTo(json);
    }

    @Test
    void patch_partialUnmatchedRequests_onlyUnmatchedRemoved() throws Exception {
        // AI has two tool requests; only t2 has a result → t1 removed, t2 kept
        String json = """
                [
                  {"type":"AI","text":".","toolExecutionRequests":[
                    {"id":"t1","name":"executeQuery","arguments":"{}"},
                    {"id":"t2","name":"executeQuery","arguments":"{}"}
                  ]},
                  {"type":"TOOL_EXECUTION_RESULT","id":"t2","toolName":"executeQuery","text":"result"}
                ]
                """;
        String patched = MessageSanitizer.patch(json);
        JsonNode out = MAPPER.readTree(patched);
        JsonNode aiNode = out.get(0);
        JsonNode reqs = aiNode.path("toolExecutionRequests");
        assertThat(reqs.isArray()).isTrue();
        assertThat(reqs).hasSize(1);
        assertThat(reqs.get(0).path("id").asText()).isEqualTo("t2");
    }
}
