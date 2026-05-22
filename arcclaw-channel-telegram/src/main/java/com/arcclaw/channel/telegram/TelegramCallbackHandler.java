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

import com.arcclaw.channel.api.ChannelType;
import com.arcclaw.channel.api.ChannelUser;
import com.arcclaw.channel.api.hitl.ApprovalDecision;
import com.arcclaw.channel.api.hitl.ApprovalGateway;
import com.arcclaw.channel.api.hitl.ApprovalOutcome;
import com.arcclaw.channel.api.hitl.ApprovalResult;
import com.arcclaw.common.util.LogUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.arc.Unremovable;
import org.apache.camel.Exchange;
import org.apache.camel.component.telegram.model.IncomingCallbackQuery;
import org.apache.camel.component.telegram.model.InlineKeyboardMarkup;
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

            sessionId = resolveSessionId(sessionId, userId);

            ApprovalResult result = approvalGateway.decide(sessionId, toolName, decision, "Telegram Callback");
            log.infof("ApprovalResult: session=%s tool=%s outcome=%s remaining=%d pending=%s user=%s",
                    sessionId, toolName, result.outcome(), result.remainingPendingTools(),
                    result.pendingToolNames(), userId);

            String popupText = null;
            switch (result.outcome()) {
                case APPROVED, PARTIAL_STILL_PENDING -> popupText = "✅ Approved: " + toolName;
                case REJECTED -> popupText = "❌ Rejected: " + toolName;
                case EXPIRED -> popupText = "⏱ Approval expired";
                case NOT_FOUND -> {
                    log.infof("Stale callback for session %s - ignoring NOT_FOUND", sessionId);
                    // Edit the message to remove stale inline keyboard and show expired notice
                    try {
                        sender.editMessage(chatId, messageId, "ignoring session NOT_FOUND", null);
                    } catch (Exception editEx) {
                        log.debugf("Failed to edit stale NOT_FOUND message: %s", LogUtil.rootMessage(editEx));
                    }
                    answerCallback(query.getId(), "ignoring session NOT_FOUND");
                    return;
                }
                case ALREADY_DECIDED -> popupText = "Already decided";
            }

            // If completely done, we can remove the keyboard entirely
            if (result.outcome() == ApprovalOutcome.PARTIAL_STILL_PENDING) {
                // §7.27: edit in-place to show remaining tools
                String originalText = query.getMessage().getText();
                InlineKeyboardMarkup nextKeyboard =
                    BatchHitlKeyboardBuilder.buildKeyboard(sessionId, result.pendingToolNames());
                sender.editMessage(chatId, messageId, originalText, nextKeyboard);
            } else if (result.outcome() != ApprovalOutcome.ALREADY_DECIDED && result.remainingPendingTools() == 0) {
                String doneText = result.outcome() == ApprovalOutcome.APPROVED
                    ? "✅ Approved: " + toolName
                    : "❌ Rejected: " + toolName;
                sender.editMessage(chatId, messageId, doneText, null);
            }

            if (popupText != null) {
                answerCallback(query.getId(), popupText);
            }
        } catch (Exception e) {
            log.error("Failed to process callback", e);
            sender.sendRaw(user, "❌ Failed to process callback");
        }
    }

    private String resolveSessionId(String rawSessionId, String userId) {
        String cached = BatchHitlKeyboardBuilder.resolveAndRemoveCachedSessionId(rawSessionId);
        if (cached != null) {
            log.debugf("Resolved cached token '%s' -> session '%s' for user=%s", rawSessionId, cached, userId);
            return cached;
        }
        String decompressed = BatchHitlKeyboardBuilder.decompressSessionId(rawSessionId);
        if (!decompressed.equals(rawSessionId)) {
            log.debugf("Decompressed session ID '%s' -> '%s' for user=%s", rawSessionId, decompressed, userId);
            return decompressed;
        }
        if (rawSessionId.length() < 36) {
            String resolved = approvalGateway.resolveSessionIdPrefix(rawSessionId);
            log.infof("Resolved session prefix '%s' -> '%s' for user=%s", rawSessionId, resolved, userId);
            return resolved;
        }
        return rawSessionId;
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
                    callbackQueryId, LogUtil.rootMessage(ex));
                return null;
            });
        } catch (Exception e) {
            log.warnf("Failed to serialize Telegram callback answer: %s", LogUtil.rootMessage(e));
        }
    }

    private void answerCallback(String callbackQueryId) {
        answerCallback(callbackQueryId, null);
    }
}
