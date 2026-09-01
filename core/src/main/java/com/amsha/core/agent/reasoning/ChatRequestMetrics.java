package com.amsha.core.agent.reasoning;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.request.ChatRequest;

import com.amsha.llm.api.MultimodalRequests;

final class ChatRequestMetrics {

    private ChatRequestMetrics() {}

    static int estimateTokens(ChatRequest request) {
        if (request.messages() == null) {
            return 0;
        }
        int total = 0;
        for (ChatMessage msg : request.messages()) {
            String text = extractText(msg);
            if (text != null) {
                total += text.length() / 4;
            }
        }
        return total;
    }

    static int countChars(ChatRequest request) {
        if (request.messages() == null) {
            return 0;
        }
        int total = 0;
        for (ChatMessage msg : request.messages()) {
            String text = extractText(msg);
            total += text != null ? text.length() : 0;
        }
        return total;
    }

    private static String extractText(ChatMessage msg) {
        if (msg instanceof SystemMessage sm) {
            return sm.text();
        }
        if (msg instanceof UserMessage um) {
            return MultimodalRequests.textOf(um, "metrics");
        }
        if (msg instanceof AiMessage am) {
            return am.text();
        }
        if (msg instanceof ToolExecutionResultMessage tm) {
            return tm.text();
        }
        return null;
    }
}
