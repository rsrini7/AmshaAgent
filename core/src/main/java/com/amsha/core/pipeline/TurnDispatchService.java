package com.amsha.core.pipeline;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import com.amsha.channel.api.AttachmentType;
import com.amsha.channel.api.MediaAttachment;
import com.amsha.core.agent.result.AgentResult;

import org.eclipse.microprofile.context.ManagedExecutor;

/**
 * The single application entry point for user-initiated turns.
 *
 * <p>It deliberately delegates to {@link MessagePipeline}; adapters must not
 * reproduce command dispatch, classification, or direct AgentLoop invocation.
 */
@ApplicationScoped
public class TurnDispatchService {

    public static final String DATA_AGENT_RESULT = "turn.agent-result";
    public static final String DATA_COMMAND_RESPONSE = "turn.command-response";
    public static final String DATA_MAX_ITERATIONS = "turn.max-iterations";
    public static final String DATA_INBOUND_AUDIO = "turn.inbound-audio";

    @Inject
    MessagePipeline messagePipeline;

    @Inject
    ManagedExecutor executor;

    public TurnDispatchResult dispatch(TurnRequest request) {
        MessageContext context =
                new MessageContext(request.chatId(), request.message().sender(), request.message());
        request.requestedSessionId().ifPresent(context::setSessionId);
        request.explicitRole().ifPresent(context::setAgentRole);
        request.maxIterations().ifPresent(value -> context.setData(DATA_MAX_ITERATIONS, value));
        messagePipeline.process(context);

        Object rawResult = context.getData(DATA_AGENT_RESULT);
        Optional<AgentResult> agentResult =
                rawResult instanceof AgentResult result ? Optional.of(result) : Optional.empty();
        Object rawResponse = context.getData(DATA_COMMAND_RESPONSE);
        Optional<String> commandResponse =
                rawResponse instanceof String response ? Optional.of(response) : Optional.empty();
        return new TurnDispatchResult(context.getSessionId(), context.getAgentRole(), agentResult, commandResponse);
    }

    public CompletableFuture<TurnDispatchResult> dispatchAsync(TurnRequest request) {
        return executor.supplyAsync(() -> dispatch(request));
    }

    public static void markInboundAudio(MessageContext context) {
        if (context != null) {
            context.setData(DATA_INBOUND_AUDIO, true);
        }
    }

    public static boolean hasInboundAudio(MessageContext context) {
        if (context == null) {
            return false;
        }
        if (Boolean.TRUE.equals(context.getData(DATA_INBOUND_AUDIO))
                || "true".equalsIgnoreCase(String.valueOf(context.getData(DATA_INBOUND_AUDIO)))) {
            return true;
        }
        if (context.getMessage() == null || context.getMessage().attachments() == null) {
            return false;
        }
        for (MediaAttachment attachment : context.getMessage().attachments()) {
            if (attachment != null && attachment.type() == AttachmentType.AUDIO) {
                return true;
            }
        }
        return false;
    }
}
