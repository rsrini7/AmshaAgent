package com.arcclaw.channel.telegram;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

import com.arcclaw.channel.api.ChannelMessage;
import com.arcclaw.channel.api.ChannelType;
import com.arcclaw.channel.api.ChannelUser;
import com.arcclaw.core.agent.role.AgentRoleEnum;
import com.arcclaw.core.pipeline.MessageContext;
import com.arcclaw.core.reflex.AgentReflex;
import com.arcclaw.core.reflex.ReflexResult;
import com.arcclaw.core.state.AgentCheckpoint;
import com.arcclaw.core.state.CheckpointStatus;
import com.arcclaw.core.state.StatefulAgentStore;
import com.arcclaw.core.util.BatchDecisionUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.camel.Exchange;
import org.apache.camel.component.telegram.model.IncomingCallbackQuery;
import org.apache.camel.component.telegram.model.InlineKeyboardMarkup;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

@ApplicationScoped
public class TelegramCallbackHandler {

    private static final Logger log = Logger.getLogger(TelegramCallbackHandler.class);

    @Inject
    TelegramChannelSender sender;

    @Inject
    Instance<AgentReflex> reflexes;

    @Inject
    StatefulAgentStore agentStore;

    @Inject
    ObjectMapper objectMapper;

    @ConfigProperty(name = "arcclaw.channel.telegram.bot-token")
    String botToken;

    public void handleCallback(Exchange exchange) {
        IncomingCallbackQuery query = exchange.getIn().getBody(IncomingCallbackQuery.class);
        if (query == null) return;

        String data = query.getData();
        if (data == null) return;

        String chatId = String.valueOf(query.getMessage().getChat().getId());
        String userId = String.valueOf(query.getFrom().getId());
        int messageId = Math.toIntExact(query.getMessage().getMessageId());

        log.infof("TELEGRAM-CALLBACK: %s from user=%s chat=%s msg=%d", data, userId, chatId, messageId);

        Optional<BatchHitlKeyboardBuilder.BatchCallbackEvent> evOpt = BatchHitlKeyboardBuilder.parseCallback(data);
        if (evOpt.isEmpty()) return;

        BatchHitlKeyboardBuilder.BatchCallbackEvent event = evOpt.get();
        String commandText = formCommandText(event);

        if (commandText == null) return;

        ChannelUser user = new ChannelUser(userId, chatId, null, ChannelType.TELEGRAM);
        ChannelMessage.TelegramMessage message = new ChannelMessage.TelegramMessage(user, commandText, chatId, Instant.now());
        MessageContext ctx = new MessageContext(chatId, user, message);
        String sessionId = getSessionId(event);

        for (AgentReflex reflex : reflexes) {
            if (reflex.matches(commandText)) {
                try {
                    ReflexResult result = reflex.execute(sessionId, userId, chatId, commandText, ctx);

                    if (result.commandResponse().isPresent() && !result.commandResponse().get().isBlank()) {
                        sender.sendRaw(ctx.getUser(), result.commandResponse().get());
                    }

                    updateOrRemoveKeyboard(sessionId, chatId, messageId);
                    answerCallback(query.getId());
                    return;
                } catch (Exception e) {
                    log.error("Reflex execution failed from callback", e);
                    sender.sendRaw(ctx.getUser(), "❌ Failed to process callback");
                    answerCallback(query.getId());
                    return;
                }
            }
        }
        answerCallback(query.getId());
    }

    private void answerCallback(String callbackQueryId) {
        try {
            log.debugf("Answering callback %s", callbackQueryId);
            String body = "{\"callback_query_id\":\"" + callbackQueryId + "\"}";
            HttpClient.newHttpClient().sendAsync(
                HttpRequest.newBuilder()
                    .uri(URI.create(
                        "https://api.telegram.org/bot" + botToken + "/answerCallbackQuery"))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build(),
                HttpResponse.BodyHandlers.discarding());
        } catch (Exception e) {
            log.warnf("Failed to answer callback %s: %s", callbackQueryId, e.getMessage());
        }
    }

    private void updateOrRemoveKeyboard(String sessionId, String chatId, int messageId) {
        String activeRole = null;
        for (AgentRoleEnum role : AgentRoleEnum.values()) {
            Optional<AgentCheckpoint> cpOpt = agentStore.load(sessionId, role.toScopedName());
            if (cpOpt.isPresent() && cpOpt.get().getStatus() == CheckpointStatus.AWAITING_APPROVAL) {
                activeRole = role.toScopedName();
                break;
            }
        }

        if (activeRole != null) {
            AgentCheckpoint cp = agentStore.load(sessionId, activeRole).orElseThrow();
            List<String> pending = BatchDecisionUtil.pendingToolNames(cp, objectMapper);
            if (!pending.isEmpty()) {
                // Update keyboard
                InlineKeyboardMarkup markup = BatchHitlKeyboardBuilder.buildKeyboard(sessionId, pending);
                sender.editMessage(chatId, messageId, "Please review remaining operations:", markup);
                return;
            }
        }

        // Remove keyboard completely
        sender.editMessage(chatId, messageId, "Approval process completed.", null);
    }

    private String getSessionId(BatchHitlKeyboardBuilder.BatchCallbackEvent event) {
        return switch (event) {
            case BatchHitlKeyboardBuilder.BatchCallbackEvent.ApproveTool a -> a.sessionId();
            case BatchHitlKeyboardBuilder.BatchCallbackEvent.DenyTool d -> d.sessionId();
            case BatchHitlKeyboardBuilder.BatchCallbackEvent.ApproveAll aa -> aa.sessionId();
            case BatchHitlKeyboardBuilder.BatchCallbackEvent.DenyAll da -> da.sessionId();
        };
    }

    private String formCommandText(BatchHitlKeyboardBuilder.BatchCallbackEvent event) {
        if (event instanceof BatchHitlKeyboardBuilder.BatchCallbackEvent.ApproveTool a) {
            return "/approve " + a.toolName();
        } else if (event instanceof BatchHitlKeyboardBuilder.BatchCallbackEvent.DenyTool d) {
            return "/deny " + d.toolName();
        } else if (event instanceof BatchHitlKeyboardBuilder.BatchCallbackEvent.ApproveAll) {
            return "/approve";
        } else if (event instanceof BatchHitlKeyboardBuilder.BatchCallbackEvent.DenyAll) {
            return "/deny";
        }
        return null;
    }
}
