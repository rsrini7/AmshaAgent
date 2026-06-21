package com.amsha.app.ui;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import com.amsha.app.ui.dto.AgentEvent;
import com.amsha.app.ui.dto.ChatMessageDto;
import com.amsha.core.agent.event.AgentStateChangedEvent;
import com.amsha.core.state.PersistentChatMemoryStore;
import com.amsha.core.state.StatefulAgentStore;

import org.eclipse.microprofile.openapi.annotations.Operation;
import org.jboss.resteasy.reactive.RestStreamElementType;

import io.smallrye.common.annotation.RunOnVirtualThread;
import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.operators.multi.processors.UnicastProcessor;

/**
 * SSE endpoint to broadcast agent status updates to the Lit frontend.
 */
@Path("chat/stream")
@ApplicationScoped
@RunOnVirtualThread
public class ChatStreamResource {

    @Inject
    PersistentChatMemoryStore chatMemoryStore;

    @Inject
    StatefulAgentStore statefulAgentStore;

    private final UnicastProcessor<AgentEvent> processor = UnicastProcessor.create();

    // Subscribe to the UnicastProcessor ONCE - the resulting hotStream
    // can be subscribed to many times (one per SSE connection)
    private final Multi<AgentEvent> hotStream = processor.broadcast().toAllSubscribers();

    public void onStateChanged(@Observes AgentStateChangedEvent event) {
        processor.onNext(new AgentEvent(
                "status_change",
                event.agentName(),
                event.newStatus() + (event.currentTask() != null ? " : " + event.currentTask() : "")));
    }

    @GET
    @Produces(MediaType.SERVER_SENT_EVENTS)
    @RestStreamElementType(MediaType.APPLICATION_JSON)
    @Operation(summary = "Stream agent status changes and keep-alive heartbeats via SSE")
    public Multi<AgentEvent> stream() {
        Multi<AgentEvent> heartbeat = Multi.createFrom()
                .ticks()
                .every(Duration.ofSeconds(20))
                .map(t -> new AgentEvent("heartbeat", "system", "keep-alive"));

        // hotStream is shared - each subscriber gets their own downstream from the broadcast
        return Multi.createBy().merging().streams(hotStream, heartbeat);
    }

    @GET
    @Path("/history/{sessionId}")
    @Produces(MediaType.APPLICATION_JSON)
    @Operation(summary = "Retrieve full chat history for a session, enriched with approval IDs")
    public List<ChatMessageDto> history(@PathParam("sessionId") String sessionId) {

        final Long actionIdForPause = statefulAgentStore
                .findLatestAwaitingApproval(sessionId)
                .map(cp -> cp.getId())
                .orElse(null);

        List<ChatMessage> messages = chatMemoryStore.getMessages(sessionId);
        List<Long> timestamps = chatMemoryStore.getTimestamps(sessionId);

        List<ChatMessageDto> dtos = new ArrayList<>();
        for (int i = 0; i < messages.size(); i++) {
            ChatMessage m = messages.get(i);
            if (!(m instanceof UserMessage || m instanceof AiMessage)) continue;

            // Offset alignment for system messages (since we filter them for DTOs but they might be in memory)
            // However Amsha Agent system messages are never stored in memory according to PersistentChatMemoryStore
            // docs.
            Long ts = (i < timestamps.size()) ? timestamps.get(i) : null;
            dtos.add(ChatMessageDto.from(m, ts));
        }

        // Only attach actionId to the LAST matching message (§ Option A refined)
        if (actionIdForPause != null) {
            int lastIdx = -1;
            for (int i = dtos.size() - 1; i >= 0; i--) {
                ChatMessageDto d = dtos.get(i);
                if ("assistant".equals(d.role()) && d.text().contains("⏸ Approval needed")) {
                    lastIdx = i;
                    break;
                }
            }
            if (lastIdx >= 0) {
                ChatMessageDto d = dtos.get(lastIdx);
                dtos.set(lastIdx, new ChatMessageDto(d.role(), d.text(), actionIdForPause, d.timestamp()));
            }
        }
        return dtos;
    }
}
