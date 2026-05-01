package com.arcclaw.channel.telegram;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.control.ActivateRequestContext;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;

import com.arcclaw.channel.api.ChannelType;
import com.arcclaw.channel.api.ChannelUser;
import com.arcclaw.channel.api.hitl.ApprovalDecision;
import com.arcclaw.channel.api.hitl.ApprovalGateway;
import com.arcclaw.channel.api.hitl.ApprovalOutcome;
import com.arcclaw.channel.api.hitl.ApprovalResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.arc.Unremovable;
import org.apache.camel.Exchange;
import org.apache.camel.component.telegram.model.IncomingCallbackQuery;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

@ApplicationScoped
@Unremovable
public class TelegramCallbackHandler {

    private static final Logger log = Logger.getLogger(TelegramCallbackHandler.class);
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    @Inject
    TelegramChannelSender sender;

    @Inject
    ApprovalGateway approvalGateway;

    @Inject
    ObjectMapper objectMapper;

    @ConfigProperty(name = "arcclaw.channel.telegram.bot-token")
    String botToken;

    @ActivateRequestContext
    @Transactional
    public void handleCallback(Exchange exchange) {
        IncomingCallbackQuery query = exchange.getIn().getBody(IncomingCallbackQuery.class);
        if (query == null) return;

        String data = query.getData();
        if (data == null) return;

        String chatId = String.valueOf(query.getMessage().getChat().getId());
        String userId = String.valueOf(query.getFrom().getId());
        int messageId = Math.toIntExact(query.getMessage().getMessageId());

        log.infof("TELEGRAM-CALLBACK: %s from user=%s chat=%s msg=%d", data, userId, chatId, messageId);

        BatchHitlKeyboardBuilder.BatchCallbackEvent event = BatchHitlKeyboardBuilder.parseCallback(data).orElse(null);
        if (event == null) {
            return;
        }

        ChannelUser user = new ChannelUser(userId, chatId, null, ChannelType.TELEGRAM);

        String sessionId;
        String toolName;
        ApprovalDecision decision;

        if (event instanceof BatchHitlKeyboardBuilder.BatchCallbackEvent.ApproveTool a) {
            sessionId = a.sessionId();
            toolName = a.toolName();
            decision = ApprovalDecision.APPROVE;
        } else if (event instanceof BatchHitlKeyboardBuilder.BatchCallbackEvent.DenyTool d) {
            sessionId = d.sessionId();
            toolName = d.toolName();
            decision = ApprovalDecision.REJECT;
        } else {
            return;
        }

        try {
            // Acknowledge callback immediately to stop the spinning icon and prevent Telegram retries
            // during potentially long-running approvalGate.decide/resume paths §7.35
            answerCallback(query.getId());

            // Resolve full sessionId from 8-char prefix to stay under 64-byte limit §7.25
            if (sessionId.length() < 36) {
                sessionId = approvalGateway.resolveSessionIdPrefix(sessionId);
            }

            ApprovalResult result = approvalGateway.decide(sessionId, toolName, decision, "Telegram Callback");

            String popupText = null;
            switch (result.outcome()) {
                case APPROVED, PARTIAL_STILL_PENDING -> popupText = "✅ Approved: " + toolName;
                case REJECTED -> popupText = "❌ Rejected: " + toolName;
                case EXPIRED -> popupText = "⏱ Approval expired";
                case NOT_FOUND -> {
                    log.debugf("Stale callback for session %s, ignoring", sessionId);
                    return;
                }
                case ALREADY_DECIDED -> popupText = "Already decided";
            }

            // If completely done, we can remove the keyboard entirely
            if (result.outcome() != ApprovalOutcome.ALREADY_DECIDED && result.remainingPendingTools() == 0) {
                String originalText = query.getMessage().getText();
                sender.editMessage(chatId, messageId, originalText, null);
            }

            if (popupText != null) {
                answerCallback(query.getId(), popupText);
            }
        } catch (Exception e) {
            log.error("Failed to process callback", e);
            sender.sendRaw(user, "❌ Failed to process callback");
        }
    }

    private void answerCallback(String callbackQueryId, String text) {
        log.debugf("Answering callback %s with text: %s", callbackQueryId, text);
        try {
            Map<String, String> payload = new HashMap<>();
            payload.put("callback_query_id", callbackQueryId);
            if (text != null) {
                payload.put("text", text);
            }
            String body = objectMapper.writeValueAsString(payload);

            httpClient.sendAsync(
                HttpRequest.newBuilder()
                    .uri(URI.create(
                        "https://api.telegram.org/bot" + botToken + "/answerCallbackQuery"))
                    .timeout(Duration.ofSeconds(5))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build(),
                HttpResponse.BodyHandlers.discarding())
            .exceptionally(ex -> {
                log.warnf("Failed to answer Telegram callback %s: %s",
                    callbackQueryId, ex.getMessage());
                return null;
            });
        } catch (Exception e) {
            log.warnf("Failed to serialize Telegram callback answer: %s", e.getMessage());
        }
    }

    private void answerCallback(String callbackQueryId) {
        answerCallback(callbackQueryId, null);
    }
}
