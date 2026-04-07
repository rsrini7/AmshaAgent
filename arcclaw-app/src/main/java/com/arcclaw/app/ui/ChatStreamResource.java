package com.arcclaw.app.ui;

import java.time.Duration;
import java.util.List;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import com.arcclaw.app.security.ApiKeyValidator;
import com.arcclaw.app.ui.dto.AgentEvent;
import com.arcclaw.app.ui.dto.ChatMessageDto;
import com.arcclaw.core.agent.event.AgentStateChangedEvent;
import com.arcclaw.core.state.PersistentChatMemoryStore;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.UserMessage;
import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.operators.multi.processors.UnicastProcessor;
import org.jboss.resteasy.reactive.RestStreamElementType;

/**
 * SSE endpoint to broadcast agent status updates to the Lit frontend.
 */
@Path("chat/stream")
@ApplicationScoped
public class ChatStreamResource {

    @Inject
    PersistentChatMemoryStore chatMemoryStore;
    
    @Inject
    ApiKeyValidator apiKeyValidator;

    private final UnicastProcessor<AgentEvent> processor = UnicastProcessor.create();

    // Subscribe to the UnicastProcessor ONCE — the resulting hotStream
    // can be subscribed to many times (one per SSE connection)
    private final Multi<AgentEvent> hotStream = processor.broadcast().toAllSubscribers();

    public void onStateChanged(@Observes AgentStateChangedEvent event) {
        processor.onNext(new AgentEvent(
            "status_change",
            event.agentName(),
            event.newStatus() + (event.currentTask() != null ? " : " + event.currentTask() : "")
        ));
    }

    @GET
    @Produces(MediaType.SERVER_SENT_EVENTS)
    @RestStreamElementType(MediaType.APPLICATION_JSON)
    public Multi<AgentEvent> stream(@QueryParam("apiKey") String apiKey) {
        if (!apiKeyValidator.isValid(apiKey)) {
            return Multi.createFrom().failure(new WebApplicationException(Response.Status.UNAUTHORIZED));
        }
        Multi<AgentEvent> heartbeat = Multi.createFrom().ticks().every(Duration.ofSeconds(20))
                .map(t -> new AgentEvent("heartbeat", "system", "keep-alive"));

        // hotStream is shared — each subscriber gets their own downstream from the broadcast
        return Multi.createBy().merging().streams(hotStream, heartbeat);
    }

    @GET
    @Path("/history/{sessionId}")
    @Produces(MediaType.APPLICATION_JSON)
    public List<ChatMessageDto> history(@PathParam("sessionId") String sessionId, @QueryParam("apiKey") String apiKey) {
        if (!apiKeyValidator.isValid(apiKey)) {
            throw new WebApplicationException(Response.Status.UNAUTHORIZED);
        }
        return chatMemoryStore.getMessages(sessionId)
                .stream()
                .filter(m -> m instanceof UserMessage || m instanceof AiMessage)
                .map(ChatMessageDto::from)
                .toList();
    }
}
