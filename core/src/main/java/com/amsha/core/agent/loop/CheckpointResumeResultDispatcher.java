package com.amsha.core.agent.loop;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import com.amsha.channel.api.ApprovalPrompt;
import com.amsha.channel.api.ChannelMessage;
import com.amsha.channel.api.ChannelPlugin;
import com.amsha.channel.api.ChannelSender;
import com.amsha.channel.api.ChannelType;
import com.amsha.channel.api.ChannelUser;
import com.amsha.common.util.LogUtil;
import com.amsha.core.agent.result.AgentResult;
import com.amsha.core.channel.ChannelPluginRegistry;
import com.amsha.core.domain.model.AgentCheckpoint;
import com.amsha.core.pipeline.MessageContext;
import com.amsha.core.speech.SpeechDeliveryService;
import com.amsha.core.state.SessionTree;
import com.amsha.core.util.AgentResultFormatter;
import com.amsha.persistence.api.CronRunStorePort;
import com.amsha.persistence.api.CronStorePort;
import com.amsha.tool.api.AgentSentinels;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.jboss.logging.Logger;

/**
 * Handles result formatting and channel dispatch for resumed checkpoint executions.
 */
class CheckpointResumeResultDispatcher {

    private static final Logger log = Logger.getLogger(CheckpointResumeResultDispatcher.class);

    private final CronStorePort cronStore;
    private final CronRunStorePort cronRunStore;
    private final Supplier<ResumeCronNotificationDispatcher> cronNotificationDispatcherProvider;
    private final CliResultBridge cliResultBridge;
    private final ChannelPluginRegistry channelPluginRegistry;
    private final Supplier<SpeechDeliveryService> speechDeliveryServiceProvider;

    CheckpointResumeResultDispatcher(
            CronStorePort cronStore,
            CronRunStorePort cronRunStore,
            Supplier<ResumeCronNotificationDispatcher> cronNotificationDispatcherProvider,
            CliResultBridge cliResultBridge,
            ChannelPluginRegistry channelPluginRegistry,
            Supplier<SpeechDeliveryService> speechDeliveryServiceProvider) {
        this.cronStore = cronStore;
        this.cronRunStore = cronRunStore;
        this.cronNotificationDispatcherProvider = cronNotificationDispatcherProvider;
        this.cliResultBridge = cliResultBridge;
        this.channelPluginRegistry = channelPluginRegistry;
        this.speechDeliveryServiceProvider = speechDeliveryServiceProvider;
    }

    void dispatchResumeResult(
            MessageContext context,
            AgentResult result,
            ChannelType cType,
            ChannelUser user,
            String sessionId,
            String toolInfo) {
        if (!CronSessionStatusSync.syncTerminal(sessionId, result, cronStore, cronRunStore)) {
            log.warnf("Skipping resume notification for session %s because cron ownership was lost", sessionId);
            return;
        }

        ResumeCronNotificationDispatcher cronNotificationDispatcher =
                cronNotificationDispatcherProvider != null ? cronNotificationDispatcherProvider.get() : null;
        if (ResumeCronNotificationDispatcher.isCronSession(sessionId)) {
            if (cronNotificationDispatcher != null) {
                cronNotificationDispatcher.dispatch(sessionId, result);
            }
            return;
        }

        boolean isCli = (cType != null && ChannelType.CLI.id().equalsIgnoreCase(cType.id()))
                || (sessionId != null && sessionId.startsWith(AgentSentinels.CLI_SESSION_PREFIX));
        if (isCli) {
            String bridgeSid = SessionTree.rootOf(sessionId);
            log.debugf(
                    "CheckpointResumeHandler: delivering async CLI result via bridge for session %s (bridge: %s)",
                    sessionId, bridgeSid);
            if (result instanceof AgentResult.AwaitingApproval) {
                cliResultBridge.complete(bridgeSid, result);
                return;
            }
            cliResultBridge.complete(bridgeSid, result);
            return;
        }

        String baseOutput = AgentResultFormatter.format(result);
        String finalOutput = (toolInfo != null && !toolInfo.isBlank())
                ? "---\n"
                        + (baseOutput.contains("denied") || baseOutput.contains("cancelled")
                                ? "❌ Denied"
                                : "✅ Approved")
                        + " — " + toolInfo + "\n---\n\n" + baseOutput
                : baseOutput;
        channelPluginRegistry
                .find(cType.id())
                .map(ChannelPlugin::sender)
                .ifPresentOrElse(
                        s -> sendFormattedResult(s, user, result, finalOutput, sessionId, context),
                        () -> fallbackResultDispatch(sessionId, result, cType));
    }

    private void sendFormattedResult(
            ChannelSender s,
            ChannelUser user,
            AgentResult result,
            String output,
            String sessionId,
            MessageContext context) {
        if (result instanceof AgentResult.AwaitingApproval aa) {
            s.send(user, List.of(new ApprovalPrompt(aa.toolName(), aa.displayParamsJson(), sessionId)));
            return;
        }
        SpeechDeliveryService speechDeliveryService =
                speechDeliveryServiceProvider != null ? speechDeliveryServiceProvider.get() : null;
        if (speechDeliveryService != null) {
            speechDeliveryService.deliver(context, s, user, result, output);
        } else {
            s.sendRaw(user, output);
        }
    }

    private void fallbackResultDispatch(String sessionId, AgentResult result, ChannelType cType) {
        log.debugf("CheckpointResumeHandler: no sender for type %s", cType != null ? cType.id() : "null");
        boolean isCli = (cType != null && ChannelType.CLI.id().equalsIgnoreCase(cType.id()))
                || (sessionId != null && sessionId.startsWith(AgentSentinels.CLI_SESSION_PREFIX));
        if (isCli) {
            String bridgeSid = SessionTree.rootOf(sessionId);
            if (result instanceof AgentResult.AwaitingApproval) {
                cliResultBridge.complete(bridgeSid, result);
                return;
            }
            cliResultBridge.complete(bridgeSid, result);
        }
    }

    MessageContext prepareResumeMessageContext(
            String chatId,
            ChannelUser user,
            ChannelType cType,
            String goal,
            AgentCheckpoint checkpoint,
            ObjectMapper objectMapper) {
        ChannelMessage msg =
                switch (cType.id()) {
                    case "telegram" -> new ChannelMessage.TelegramMessage(user, goal, chatId, Instant.now());
                    case "discord" -> new ChannelMessage.DiscordMessage(user, goal, chatId, chatId, Instant.now());
                    case "rest_api" -> new ChannelMessage.RestApiMessage(user, goal, chatId, Instant.now());
                    case "cli" -> new ChannelMessage.CliMessage(user, goal, chatId, Instant.now());
                    case "websocket" -> new ChannelMessage.WebSocketMessage(user, goal, chatId, Instant.now());
                    default -> new ChannelMessage.GenericChannelMessage(user, goal, chatId, Instant.now(), cType);
                };
        MessageContext context = new MessageContext(chatId, user, msg);
        context.setResumeSignal(true);
        context.setGoal(goal);
        if (checkpoint != null && checkpoint.getContextDataJson() != null && objectMapper != null) {
            try {
                @SuppressWarnings("unchecked")
                Map<String, Object> data = objectMapper.readValue(checkpoint.getContextDataJson(), Map.class);
                if (data != null) data.forEach(context::setData);
            } catch (Exception e) {
                log.debugf("Restore context data (non-fatal): %s", LogUtil.rootMessage(e));
            }
        }
        return context;
    }
}
