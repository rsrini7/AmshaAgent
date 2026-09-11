package com.amsha.channel.telegram;

import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.control.ActivateRequestContext;
import jakarta.inject.Inject;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import com.amsha.channel.api.ChannelType;
import com.amsha.channel.api.ChannelUser;
import com.amsha.channel.api.hitl.ApprovalDecision;
import com.amsha.channel.api.hitl.ApprovalGateway;
import com.amsha.channel.api.hitl.ApprovalNotifier;
import com.amsha.channel.api.hitl.ApprovalOutcome;
import com.amsha.channel.api.hitl.ApprovalResult;
import com.amsha.channel.api.preference.UserPreferenceService;
import com.amsha.common.util.LogUtil;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.apache.camel.Exchange;
import org.apache.camel.component.telegram.model.IncomingCallbackQuery;
import org.apache.camel.component.telegram.model.InlineKeyboardMarkup;
import org.apache.camel.component.telegram.model.SendChatActionMessage;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import io.quarkus.arc.Unremovable;

@ApplicationScoped
@Unremovable
public class TelegramCallbackHandler {

    private static final Logger log = Logger.getLogger(TelegramCallbackHandler.class);
    private final HttpClient httpClient =
            HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    @Inject
    TelegramChannelSender sender;

    @Inject
    ApprovalGateway approvalGateway;

    @Inject
    ApprovalNotifier notificationService;

    @Inject
    UserPreferenceService userPreferenceService;

    ScheduledExecutorService progressScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "amsha-telegram-progress");
        t.setDaemon(true);
        return t;
    });

    @Inject
    ObjectMapper objectMapper;

    @ConfigProperty(name = "amsha.channel.telegram.bot-token")
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

        if (data.startsWith("V|")) {
            handleVoiceCallback(query, data.substring(2).trim(), userId, chatId, messageId);
            return;
        }

        BatchHitlKeyboardBuilder.BatchCallbackEvent event =
                BatchHitlKeyboardBuilder.parseCallback(data).orElse(null);
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

            sender.editMessage(chatId, messageId, "⏳ Processing approval…", null);

            sessionId = resolveSessionId(sessionId, userId);

            ApprovalResult result = approvalGateway.decide(sessionId, toolName, decision, "Telegram Callback");
            log.infof(
                    "ApprovalResult: session=%s tool=%s outcome=%s remaining=%d pending=%s user=%s",
                    sessionId,
                    toolName,
                    result.outcome(),
                    result.remainingPendingTools(),
                    result.pendingToolNames(),
                    userId);

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
                if (result.outcome() == ApprovalOutcome.APPROVED) {
                    // APPROVED (all tools): the agent now runs the approved work asynchronously.
                    // Confirm once here (single source of truth for this edit) and, in addition,
                    // stream a live "typing" chat action while the resumed run is active so the
                    // user sees progress instead of a static "Approved" (observation #3).
                    sender.editMessage(chatId, messageId, "✅ Approved: " + toolName, null);
                    if (progressScheduler != null) {
                        // Track the session the approval actually resumed (the checkpoint owner),
                        // which for delegated work is the ":delegate:" child, not the root session.
                        String resumedSessionId = result.sessionId() != null ? result.sessionId() : sessionId;
                        startProgressTracker(chatId, messageId, resumedSessionId);
                    }
                } else {
                    // REJECTED or an expired checkpoint -> clear the keyboard, show rejection.
                    sender.editMessage(chatId, messageId, "❌ Rejected: " + toolName, null);
                }
            }

            if (popupText != null) {
                answerCallback(query.getId(), popupText);
            }

            // Flush queued CRON HITL notifications now that this chat's approval
            // slot is free. Without this, cron jobs queued behind live-session
            // approvals are never notified (see RCA: TelegramCallbackHandler
            // bypasses AbstractDecisionReflex which normally handles the flush).
            notificationService.flushPendingNotifications(chatId);

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

            httpClient
                    .sendAsync(
                            HttpRequest.newBuilder()
                                    .uri(URI.create("https://api.telegram.org/bot" + botToken + "/answerCallbackQuery"))
                                    .timeout(Duration.ofSeconds(5))
                                    .header("Content-Type", "application/json")
                                    .POST(HttpRequest.BodyPublishers.ofString(body))
                                    .build(),
                            HttpResponse.BodyHandlers.discarding())
                    .exceptionally(ex -> {
                        log.warnf(
                                "Failed to answer Telegram callback %s: %s", callbackQueryId, LogUtil.rootMessage(ex));
                        return null;
                    });
        } catch (Exception e) {
            log.warnf("Failed to serialize Telegram callback answer: %s", LogUtil.rootMessage(e));
        }
    }

    private void handleVoiceCallback(
            IncomingCallbackQuery query, String voiceName, String userId, String chatId, int messageId) {
        String queryId = query.getId();
        if (voiceName == null || voiceName.isBlank() || "default".equalsIgnoreCase(voiceName)) {
            userPreferenceService.clearVoice(userId);
            String systemVoice = userPreferenceService.getEffectiveVoice(userId).orElse("Random / Default");
            answerCallback(queryId, "✅ Voice reset to default: " + systemVoice);
            sender.editMessage(
                    chatId,
                    messageId,
                    "🗣 Voice preference cleared. System default will be used: **" + systemVoice + "**",
                    null);
            return;
        }

        try {
            userPreferenceService.setVoice(userId, voiceName);
            answerCallback(queryId, "✅ Voice set to " + voiceName);
            sender.editMessage(
                    chatId,
                    messageId,
                    "🗣 Voice preference set to **" + voiceName + "**. All future speech replies will use this voice.",
                    null);
        } catch (IllegalArgumentException e) {
            log.warnf("Rejected invalid voice callback '%s': %s", voiceName, LogUtil.rootMessage(e));
            answerCallback(queryId, "⚠️ Unsupported voice: " + voiceName);
            sender.editMessage(
                    chatId,
                    messageId,
                    "⚠️ Unsupported voice option: **" + voiceName + "**. Please choose a valid voice.",
                    null);
        }
    }

    /**
     * Background tracker: while the resumed agent run is still active, repeatedly
     * send a Telegram "typing" chat action (Telegram shows the progress icon for
     * ~5s after each call) so the user sees live progress.
     * <p>
     * The approval message is <em>not</em> edited here: the caller already wrote
     * "✅ Approved: &lt;tool&gt;" synchronously, and re-sending identical text would
     * make Telegram reject the edit ("message is not modified"). The tracker stops
     * on its own once the session stops resuming or the safety timeout elapses.
     *
     * @param sessionId the session the approval resumed (checkpoint owner, possibly
     *                  a {@code :delegate:} child), whose resume mark drives the loop
     */
    private void startProgressTracker(String chatId, int messageId, String sessionId) {
        if (progressScheduler == null) {
            return;
        }
        Instant deadline = Instant.now().plus(Duration.ofMinutes(10));
        AtomicReference<ScheduledFuture<?>> taskRef = new AtomicReference<>();
        ScheduledFuture<?> future = progressScheduler.scheduleWithFixedDelay(
                () -> {
                    try {
                        if (Instant.now().isAfter(deadline) || !approvalGateway.isSessionResuming(sessionId)) {
                            ScheduledFuture<?> self = taskRef.get();
                            if (self != null) {
                                self.cancel(false);
                            }
                            return;
                        }
                        sender.sendChatAction(chatId, SendChatActionMessage.Action.TYPING);
                    } catch (Exception e) {
                        log.debugf(
                                "Telegram progress tracker ended for chat=%s msg=%d: %s",
                                chatId, messageId, LogUtil.rootMessage(e));
                        ScheduledFuture<?> self = taskRef.get();
                        if (self != null) {
                            self.cancel(false);
                        }
                    }
                },
                2,
                4,
                TimeUnit.SECONDS);
        taskRef.set(future);
    }

    @PreDestroy
    void shutdown() {
        if (progressScheduler != null) {
            progressScheduler.shutdown();
        }
    }

    private void answerCallback(String callbackQueryId) {
        answerCallback(callbackQueryId, null);
    }
}
