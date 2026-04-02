package com.arcclaw.core.agent.sse;

import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;

import jakarta.enterprise.context.ApplicationScoped;

import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.operators.multi.processors.BroadcastProcessor;

@ApplicationScoped
public class AgentSseService {

    private final ConcurrentHashMap<String, BroadcastProcessor<SseEvent>> 
        processors = new ConcurrentHashMap<>();

    public Multi<SseEvent> subscribe(String sessionId) {
        return processors
            .computeIfAbsent(sessionId, id -> BroadcastProcessor.create())
            .toHotStream()
            .onItem().castTo(SseEvent.class);
    }

    public void publish(String sessionId, SseEvent event) {
        BroadcastProcessor<SseEvent> proc = processors.get(sessionId);
        if (proc != null) {
            proc.onNext(event);
        }
    }

    public void complete(String sessionId) {
        BroadcastProcessor<SseEvent> proc = processors.remove(sessionId);
        if (proc != null) proc.onComplete();
    }

    public interface SseEvent {}

    public record HitlEvent(
        String sessionId,
        String type,
        String toolName,
        String toolParams,
        String reason,
        Instant timestamp
    ) implements SseEvent {}

    public record AgentStatusEvent(
        String sessionId,
        String agentRole,
        String status,
        Instant timestamp
    ) implements SseEvent {}
}
