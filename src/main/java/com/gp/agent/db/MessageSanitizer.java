package com.gp.agent.db;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ChatMessageDeserializer;
import dev.langchain4j.data.message.UserMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Patches stored chat-message JSON before LangChain4j deserializes it.
 *
 * Problems fixed:
 *
 * 1. ToolExecutionResultMessage with null/blank text → replaced with "(no result)"
 *    (LangChain4j constructor throws "text cannot be null or blank")
 *
 * 2. AiMessage with null/blank text → replaced with " "
 *    (Gemini rejects content:null — "at least one parts field must be non-empty")
 *
 * 3. AiMessage with unmatched tool requests → unmatched requests removed
 *    (Gemini: "number of function response parts must equal number of function call parts")
 *    Occurs when a tool call times out/fails and no result is stored, or when
 *    MessageWindowChatMemory trimming splits a call/result pair.
 *
 * 4. Orphaned ToolExecutionResultMessages (no matching AI tool call) → dropped
 *    Occurs when MessageWindowChatMemory trims the AI message but keeps the result.
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

        // Pass 1 — collect all tool request IDs present in AI messages
        Set<String> toolRequestIds = new LinkedHashSet<>();
        for (JsonNode node : root) {
            if ("AI".equals(node.path("type").asText(""))) {
                JsonNode reqs = node.path("toolExecutionRequests");
                if (reqs.isArray()) {
                    for (JsonNode req : reqs) {
                        String id = req.path("id").asText("");
                        if (!id.isBlank()) toolRequestIds.add(id);
                    }
                }
            }
        }

        // Pass 2 — collect all tool result IDs present in TOOL_EXECUTION_RESULT messages
        Set<String> toolResultIds = new LinkedHashSet<>();
        for (JsonNode node : root) {
            if ("TOOL_EXECUTION_RESULT".equals(node.path("type").asText(""))) {
                String id = node.path("id").asText("");
                if (!id.isBlank()) toolResultIds.add(id);
            }
        }

        // Pass 3 — patch each message; null return means drop the message entirely
        ArrayNode out = MAPPER.createArrayNode();
        for (JsonNode node : root) {
            String type = node.path("type").asText("");
            JsonNode result = patchNode(type, node, toolRequestIds, toolResultIds);
            if (result != null) out.add(result);
        }
        return MAPPER.writeValueAsString(out);
    }

    /**
     * Patch a single message node.
     * Returns null to signal that the message should be dropped.
     */
    private static JsonNode patchNode(String type, JsonNode node,
                                      Set<String> toolRequestIds,
                                      Set<String> toolResultIds) {
        if ("TOOL_EXECUTION_RESULT".equals(type)) {
            String id = node.path("id").asText("");
            // Drop orphaned tool results that have no matching AI tool request.
            if (!id.isBlank() && !toolRequestIds.contains(id)) {
                log.warn("[SANITIZER] Dropping orphaned TOOL_EXECUTION_RESULT id={}", id);
                return null;
            }
            // Fix null/blank text
            String text = textOf(node);
            if (text == null || text.isBlank()) {
                ObjectNode patched = node.deepCopy();
                patched.put("text", "(no result)");
                return patched;
            }

        } else if ("AI".equals(type)) {
            JsonNode reqs = node.path("toolExecutionRequests");
            boolean hasReqs = reqs.isArray() && reqs.size() > 0;
            boolean needsTextPatch = isNullOrBlank(textOf(node));

            if (hasReqs) {
                // Remove tool requests that have no matching tool result.
                ArrayNode filtered = MAPPER.createArrayNode();
                for (JsonNode req : reqs) {
                    String id = req.path("id").asText("");
                    if (toolResultIds.contains(id)) {
                        filtered.add(req);
                    } else {
                        log.warn("[SANITIZER] Removing unmatched tool request id={} name={}",
                                id, req.path("name").asText("?"));
                    }
                }
                boolean requestsChanged = filtered.size() != reqs.size();
                if (requestsChanged || needsTextPatch) {
                    ObjectNode patched = node.deepCopy();
                    if (filtered.size() == 0) {
                        patched.remove("toolExecutionRequests");
                    } else {
                        patched.set("toolExecutionRequests", filtered);
                    }
                    if (needsTextPatch) patched.put("text", ".");
                    return patched;
                }
            } else if (needsTextPatch) {
                ObjectNode patched = node.deepCopy();
                patched.put("text", ".");
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

    private static boolean isNullOrBlank(String s) {
        return s == null || s.isBlank();
    }

    /**
     * Strip injected schema blocks from UserMessages when a conversation turn completes.
     *
     * Schema is injected into the prompt on every request (so the LLM always has it), but we
     * don't want it accumulating in stored conversation history. Stripping at turn-end means:
     *   - The LLM sees schema in the current turn's prompt (correct answer)
     *   - Stored history stays lean (no repeated 2000-char schema blocks)
     *   - App restarts are safe — schema is re-injected from cache on next request
     *
     * We only strip when the final AiMessage (no tool calls) is stored — i.e., the turn is
     * complete and we know the LLM has already used the schema to produce its response.
     */
    public static List<ChatMessage> stripSchemaAtTurnEnd(List<ChatMessage> messages) {
        if (messages.isEmpty()) return messages;
        ChatMessage last = messages.get(messages.size() - 1);
        // Only strip at turn-end: final AI response (not a tool-call message)
        if (!(last instanceof AiMessage ai) || ai.hasToolExecutionRequests()) return messages;

        List<ChatMessage> result = new ArrayList<>(messages.size());
        boolean anyStripped = false;
        for (ChatMessage msg : messages) {
            if (msg instanceof UserMessage um) {
                try {
                    String original = um.singleText();
                    String stripped = stripSchemaBlock(original);
                    if (!stripped.equals(original)) {
                        result.add(UserMessage.from(stripped));
                        anyStripped = true;
                        continue;
                    }
                } catch (Exception ignored) {
                    // multi-part content — leave as-is
                }
            }
            result.add(msg);
        }
        if (anyStripped) log.debug("[SANITIZER] Stripped schema injection from stored user message(s)");
        return anyStripped ? result : messages;
    }

    /**
     * Remove the injected [DATABASE SCHEMA...] block from a user message text.
     * The block starts with the marker and ends just before the next injection block (\n\n[)
     * or at the end of the string. Safe to call when no schema block is present.
     */
    static String stripSchemaBlock(String text) {
        if (text == null) return text;
        final String MARKER = "\n\n[DATABASE SCHEMA — already loaded, do NOT call describe_tables:\n";
        int start = text.indexOf(MARKER);
        if (start < 0) return text;
        int nextBlock = text.indexOf("\n\n[", start + MARKER.length());
        if (nextBlock >= 0) {
            return text.substring(0, start) + text.substring(nextBlock);
        }
        return text.substring(0, start).trim();
    }
}
