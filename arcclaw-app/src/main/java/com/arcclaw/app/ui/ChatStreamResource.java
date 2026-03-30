package com.arcclaw.app.ui;

import java.time.Duration;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

import com.arcclaw.app.ui.dto.AgentEvent;
import com.arcclaw.core.agent.event.AgentStateChangedEvent;
import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.operators.multi.processors.UnicastProcessor;
import org.jboss.resteasy.reactive.RestStreamElementType;

/**
 * SSE endpoint to broadcast agent status updates to the Lit frontend.
 */
@Path("chat/stream")
@ApplicationScoped
public class ChatStreamResource {

    private final UnicastProcessor<AgentEvent> processor = UnicastProcessor.create();

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
    public Multi<AgentEvent> stream() {
        // Broadcaster with a heartbeat to keep SSE connection alive
        Multi<AgentEvent> heartBeat = Multi.createFrom().ticks().every(Duration.ofSeconds(20))
                .map(t -> new AgentEvent("heartbeat", "system", "keep-alive"));

        return Multi.createBy().merging().streams(processor.broadcast().toAllSubscribers(), heartBeat);
    }
}
