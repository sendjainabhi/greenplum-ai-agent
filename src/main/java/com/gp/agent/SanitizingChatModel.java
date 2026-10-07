package com.gp.agent;

import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.output.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Wraps any ChatLanguageModel and sanitizes messages before every API call.
 *
 * MessageSanitizer fixes messages loaded from the database, but it cannot
 * reach messages that live only in the in-session MessageWindowChatMemory —
 * e.g. an AiMessage produced by Gemini that has tool calls but no text (null content).
 * Gemini's OpenAI-compat API then rejects that same message on the very next
 * tool-loop iteration with "at least one parts field must be non-empty".
 *
 * This wrapper intercepts all generate() calls and applies the same
 * null-text / null-result patches before the request leaves the JVM.
 */
public class SanitizingChatModel implements ChatLanguageModel {

    private static final Logger log = LoggerFactory.getLogger(SanitizingChatModel.class);
    private final ChatLanguageModel delegate;

    public SanitizingChatModel(ChatLanguageModel delegate) {
        this.delegate = delegate;
    }

    @Override
    public Response<AiMessage> generate(List<ChatMessage> messages) {
        return delegate.generate(sanitize(messages));
    }

    @Override
    public Response<AiMessage> generate(List<ChatMessage> messages, List<ToolSpecification> specs) {
        return delegate.generate(sanitize(messages), specs);
    }

    @Override
    public Response<AiMessage> generate(List<ChatMessage> messages, ToolSpecification spec) {
        return delegate.generate(sanitize(messages), spec);
    }

    // -------------------------------------------------------------------------

    private static List<ChatMessage> sanitize(List<ChatMessage> messages) {
        return messages.stream()
                .map(SanitizingChatModel::sanitizeOne)
                .filter(m -> m != null)
                .collect(Collectors.toList());
    }

    private static ChatMessage sanitizeOne(ChatMessage msg) {
        if (msg instanceof AiMessage ai) {
            String text   = ai.text();
            boolean blank = text == null || text.isBlank();
            if (blank) {
                if (ai.hasToolExecutionRequests()) {
                    // Tool-call AI message with no text — Gemini rejects content:null
                    // even when tool_calls is present. Inject a dot to satisfy the check.
                    log.debug("[SANITIZER-LIVE] Patched blank AiMessage (with tool calls) → '.'");
                    return new AiMessage(".", ai.toolExecutionRequests());
                } else {
                    // Completely empty AI message — replace with dot placeholder
                    log.debug("[SANITIZER-LIVE] Patched blank AiMessage → '.'");
                    return AiMessage.from(".");
                }
            }
        } else if (msg instanceof ToolExecutionResultMessage t) {
            String text = t.text();
            if (text == null || text.isBlank()) {
                log.debug("[SANITIZER-LIVE] Patched blank ToolExecutionResult id={}", t.id());
                return new ToolExecutionResultMessage(t.id(), t.toolName(), "(no result)");
            }
        }
        return msg;
    }
}
