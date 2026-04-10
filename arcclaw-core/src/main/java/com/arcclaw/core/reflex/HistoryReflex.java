package com.arcclaw.core.reflex;

import static com.arcclaw.core.message.UserMessages.*;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import com.arcclaw.core.pipeline.MessageContext;
import com.arcclaw.core.state.CompactionSummaryRepository;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.store.memory.chat.ChatMemoryStore;

@ApplicationScoped
public class HistoryReflex implements AgentReflex {

     private static final Pattern MATCHES_PATTERN = Pattern.compile(
        "(?i)^/history$" +
        "|what (did |have )?(we |i )?(discuss|talk|cover|say)" +
        "|our conversation( so far| history)?" +
        "|(show|give|list|summarize).{0,20}(conversation|discussion|history)" +
        "|search memory.{0,30}(full|all|everything)" +
        "|what.{0,20}so far"
    );

    @Inject
    ChatMemoryStore chatMemoryStore;

    @Inject
    CompactionSummaryRepository compactionSummaryRepository;

    @Override
    public boolean matches(String input) {
        return input != null && MATCHES_PATTERN.matcher(input.trim()).matches();
    }

    @Override
    public ReflexResult execute(String sessionId, String userId, String chatId, String input, MessageContext context) {
        StringBuilder sb = new StringBuilder();

        // Use findLatestBySessionId for consistency
        compactionSummaryRepository.findLatestBySessionId(sessionId).ifPresent(s -> {
            sb.append(String.format(COMPACTED_SUMMARY_FORMAT, 
                      s.getMessagesCompacted(), s.getSummary()))
              .append("\n\n");
        });

        List<ChatMessage> messages = chatMemoryStore.getMessages(sessionId);
        if (messages == null || messages.isEmpty()) {
            if (sb.isEmpty()) {
                return ReflexResult.respond(NO_HISTORY_FOUND, context);
            }
            return ReflexResult.respond(sb.toString().trim(), context);
        }

        sb.append(String.format(ACTIVE_WINDOW_FORMAT, messages.size()))
          .append("\n");

        AtomicInteger idx = new AtomicInteger(1);
        for (ChatMessage msg : messages) {
            if (msg instanceof UserMessage um) {
                String text =  um.singleText();
                sb.append(idx.getAndIncrement())
                .append(HISTORY_YOU_PREFIX)
                .append(truncate(text, 200))
                .append("\n");
            } else if (msg instanceof AiMessage am) {
                String text = am.text();
                sb.append(idx.getAndIncrement())
                .append(HISTORY_AI_PREFIX)
                .append(truncate(text, 200))
                .append("\n");
            }
        }

        return ReflexResult.respond(sb.toString().trim(), context);
    }

    private String truncate(String text, int maxLen) {
        if (text == null) return "";
        return text.length() <= maxLen ? text : text.substring(0, maxLen) + "...";
    }
}
