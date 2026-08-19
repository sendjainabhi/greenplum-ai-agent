package com.gp.agent.db;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ChatMessageDeserializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * Patches stored chat-message JSON before LangChain4j deserializes it.
 *
 * Two problems are fixed here:
 *
 * 1. ToolExecutionResultMessage with null/blank text — LangChain4j's constructor
 *    throws "text cannot be null or blank" during deserialization, wiping the whole
 *    session from memory.  Fixed by replacing null/blank text with "(no result)".
 *
 * 2. AiMessage with null text + tool-execution requests — Gemini's OpenAI-compat
 *    endpoint rejects messages where content is null ("at least one parts field" 400).
 *    Fixed by replacing null text with a single space placeholder.  Tool requests are
 *    matched by ID, not by the assistant text, so the tool-use flow is unaffected.
 */
public class MessageSanitizer {

    private static final Logger log = LoggerFactory.getLogger(MessageSanitizer.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private MessageSanitizer() {}

    /**
     * Load and sanitize messages from raw JSON.
     * Returns an empty list if the JSON cannot be parsed even after patching.
     */
    public static List<ChatMessage> fromJson(String json) {
        if (json == null || json.isBlank()) return new ArrayList<>();
        try {
            String patched = patch(json);
            return ChatMessageDeserializer.messagesFromJson(patched);
        } catch (Exception e) {
            log.warn("[SANITIZER] Could not deserialize messages even after patch: {}", e.getMessage());
            return new ArrayList<>();
        }
    }

    /** Visible for testing. */
    static String patch(String json) throws Exception {
        JsonNode root = MAPPER.readTree(json);
        if (!root.isArray()) return json;

        ArrayNode out = MAPPER.createArrayNode();
        for (JsonNode node : root) {
            String type = node.path("type").asText("");
            out.add(patchNode(type, node));
        }
        return MAPPER.writeValueAsString(out);
    }

    private static JsonNode patchNode(String type, JsonNode node) {
        if ("TOOL_EXECUTION_RESULT".equals(type)) {
            String text = textOf(node);
            if (text == null || text.isBlank()) {
                ObjectNode patched = node.deepCopy();
                patched.put("text", "(no result)");
                return patched;
            }
        } else if ("AI".equals(type)) {
            String text = textOf(node);
            JsonNode toolReqs = node.path("toolExecutionRequests");
            boolean hasToolReqs = toolReqs.isArray() && !toolReqs.isEmpty();
            if ((text == null || text.isBlank()) && hasToolReqs) {
                ObjectNode patched = node.deepCopy();
                patched.put("text", " ");
                return patched;
            }
        }
        return node;
    }

    private static String textOf(JsonNode node) {
        JsonNode t = node.path("text");
        if (t.isMissingNode() || t.isNull()) return null;
        return t.asText(null);
    }
}
