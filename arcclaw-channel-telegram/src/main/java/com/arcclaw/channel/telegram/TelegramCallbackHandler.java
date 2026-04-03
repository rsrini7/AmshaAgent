package com.arcclaw.channel.telegram;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import com.arcclaw.channel.api.ChannelType;
import com.arcclaw.channel.api.ChannelUser;
import com.arcclaw.channel.api.hitl.ApprovalDecision;
import com.arcclaw.channel.api.hitl.ApprovalGateway;
import com.arcclaw.channel.api.hitl.ApprovalOutcome;
import com.arcclaw.channel.api.hitl.ApprovalResult;
import io.quarkus.arc.Unremovable;
import org.apache.camel.Exchange;
import org.apache.camel.component.telegram.model.IncomingCallbackQuery;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

@ApplicationScoped
@Unremovable
public class TelegramCallbackHandler {

    private static final Logger log = Logger.getLogger(TelegramCallbackHandler.class);
    private static final HttpClient HTTP_CLIENT = HttpClient.newHttpClient();

    @Inject
    TelegramChannelSender sender;

    @Inject
    ApprovalGateway approvalGateway;

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

        BatchHitlKeyboardBuilder.BatchCallbackEvent event = BatchHitlKeyboardBuilder.parseCallback(data).orElse(null);
        if (event == null) {
            answerCallback(query.getId());
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
            answerCallback(query.getId());
            return;
        }

        try {
            ApprovalResult result = approvalGateway.decide(sessionId, toolName, decision, "Telegram Callback");

            switch (result.outcome()) {
                case APPROVED, PARTIAL_STILL_PENDING -> sender.sendRaw(user, "✅ Approved: " + toolName);
                case REJECTED -> sender.sendRaw(user, "❌ Rejected: " + toolName);
                case EXPIRED -> sender.sendRaw(user, "⏱ Approval expired");
                case NOT_FOUND -> sender.sendRaw(user, "⚠️ Session not found");
                case ALREADY_DECIDED -> {
                    // Answer callback silently, no visible message
                }
            }

            // If completely done, we can remove the keyboard entirely
            if (result.outcome() == ApprovalOutcome.APPROVED ||
                result.outcome() == ApprovalOutcome.REJECTED ||
                result.outcome() == ApprovalOutcome.EXPIRED ||
                // Wait, if it's already decided, we shouldn't necessarily remove it unless it's done. 
                // A better check is if remainingPendingTools == 0 and outcome != ALREADY_DECIDED (since ALREADY_DECIDED is a no-op)
                (result.outcome() != ApprovalOutcome.ALREADY_DECIDED && result.remainingPendingTools() == 0)) {
                sender.editMessage(chatId, messageId, "Approval process completed.", null);
            }

            answerCallback(query.getId());
        } catch (Exception e) {
            log.error("Failed to process callback", e);
            sender.sendRaw(user, "❌ Failed to process callback");
            answerCallback(query.getId());
        }
    }

    private void answerCallback(String callbackQueryId) {
        log.debugf("Answering callback %s", callbackQueryId);
        String body = "{\"callback_query_id\":\"" + callbackQueryId + "\"}";
        HTTP_CLIENT.sendAsync(
            HttpRequest.newBuilder()
                .uri(URI.create(
                    "https://api.telegram.org/bot" + botToken + "/answerCallbackQuery"))
                .timeout(java.time.Duration.ofSeconds(5))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build(),
            HttpResponse.BodyHandlers.discarding())
        .exceptionally(ex -> {
            log.warnf("Failed to answer Telegram callback %s: %s",
                callbackQueryId, ex.getMessage());
            return null;
        });
    }
}
