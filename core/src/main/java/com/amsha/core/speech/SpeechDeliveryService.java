package com.amsha.core.speech;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.Locale;
import java.util.Optional;

import com.amsha.channel.api.ChannelSender;
import com.amsha.channel.api.ChannelUser;
import com.amsha.channel.api.preference.UserPreferenceService;
import com.amsha.core.agent.result.AgentResult;
import com.amsha.core.pipeline.MessageContext;
import com.amsha.core.pipeline.TurnDispatchService;

/**
 * Coordinates speech and text reply delivery across channels for finished agent turns.
 */
@ApplicationScoped
public class SpeechDeliveryService {

    private final SpeechReplyService speechReplyService;
    private final SpeechOutputPolicy speechOutputPolicy;
    private final UserPreferenceService userPreferenceService;

    @Inject
    public SpeechDeliveryService(
            SpeechReplyService speechReplyService,
            SpeechOutputPolicy speechOutputPolicy,
            UserPreferenceService userPreferenceService) {
        this.speechReplyService = speechReplyService;
        this.speechOutputPolicy = speechOutputPolicy;
        this.userPreferenceService = userPreferenceService;
    }

    public void deliver(
            MessageContext context, ChannelSender sender, ChannelUser user, AgentResult result, String output) {
        if (!(result instanceof AgentResult.Finished) || speechReplyService == null || speechOutputPolicy == null) {
            sender.sendRaw(user, output);
            return;
        }
        boolean isApprovalPause = isApprovalPauseMessage(output);
        String userId = user != null ? user.platformId() : null;
        OutputMode preferredMode = resolveUserPreferredMode(userId);
        String policyText = resolvePolicyText(context, output);
        OutputMode mode =
                speechOutputPolicy.resolve(OutputPolicyContext.of(policyText, hadInboundAudio(context), preferredMode));
        if (mode == OutputMode.AUDIO_ONLY) {
            if (isApprovalPause) {
                sender.sendRaw(user, output);
                // Approval prompts are control messages, not agent answers. Keep them
                // text-only so an audio request cannot cause the approval prompt itself
                // to be synthesized.
                return;
            }
            if (!speechReplyService.deliver(sender, user, mode, output)) {
                if (!isApprovalPause) {
                    sender.sendRaw(user, output);
                }
            }
            return;
        }
        sender.sendRaw(user, output);
        if (mode == OutputMode.TEXT_AND_AUDIO) {
            speechReplyService.deliver(sender, user, mode, output);
        }
    }

    private static boolean isApprovalPauseMessage(String output) {
        return output != null
                && (output.contains("⏸")
                        || output.contains("PAUSED awaiting human approval")
                        || output.contains("Requires Approval"));
    }

    private String resolvePolicyText(MessageContext context, String fallback) {
        if (context != null) {
            String goal = context.getGoal();
            if (goal != null && !goal.isBlank()) {
                return goal;
            }
            if (context.getMessage() != null) {
                String text = context.getMessage().text();
                if (text != null && !text.isBlank()) {
                    return text;
                }
            }
        }
        return fallback;
    }

    private OutputMode resolveUserPreferredMode(String userId) {
        if (userId == null || userPreferenceService == null) {
            return null;
        }
        return userPreferenceService
                .getOutputMode(userId)
                .flatMap(m -> {
                    try {
                        return Optional.of(OutputMode.valueOf(m.trim().toUpperCase(Locale.ROOT)));
                    } catch (IllegalArgumentException _) {
                        return Optional.empty();
                    }
                })
                .orElse(null);
    }

    static boolean hadInboundAudio(MessageContext context) {
        return TurnDispatchService.hasInboundAudio(context);
    }
}
