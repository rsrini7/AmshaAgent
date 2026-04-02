package com.arcclaw.core.agent.sse;

import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;

import jakarta.enterprise.context.ApplicationScoped;

import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.operators.multi.processors.BroadcastProcessor;

@ApplicationScoped
public class AgentSseService {

    // Active SSE subscribers per sessionId
    private final ConcurrentHashMap<String, BroadcastProcessor<SseEvent>>
        processors = new ConcurrentHashMap<>();

    // Pending events for sessions that survived restart (no active subscriber yet)
    private final ConcurrentHashMap<String, SseEvent>
        pendingEvents = new ConcurrentHashMap<>();

    /**
     * Subscribe to SSE events for a session.
     * If a pending event exists (e.g. zombie AWAITING_APPROVAL from restart),
     * it is replayed immediately on subscribe.
     */
    public Multi<SseEvent> subscribe(String sessionId) {
        BroadcastProcessor<SseEvent> proc = processors
            .computeIfAbsent(sessionId, id -> BroadcastProcessor.create());

        Multi<SseEvent> stream = proc.toHotStream()
            .onItem().castTo(SseEvent.class);

        // Replay any pending event immediately on connect
        SseEvent pending = pendingEvents.remove(sessionId);
        if (pending != null) {
            proc.onNext(pending);
        }

        return stream;
    }

    /**
     * Publish a live event to any active subscriber for this session.
     * If no subscriber exists yet, the event is dropped (live events only).
     */
    public void publish(String sessionId, SseEvent event) {
        BroadcastProcessor<SseEvent> proc = processors.get(sessionId);
        if (proc != null) {
            proc.onNext(event);
        }
        // For AWAITING_APPROVAL events, also store as pending
        // so late-connecting UI gets it on subscribe
        if (event instanceof HitlEvent h && "AWAITING_APPROVAL".equals(h.type())) {
            pendingEvents.put(sessionId, event);
        }
    }

    /**
     * Called by SessionReaper on startup for surviving AWAITING_APPROVAL checkpoints.
     * Stores the event so the UI gets it when it connects/subscribes.
     */
    public void republishOnSubscribe(String sessionId, String toolName, String toolParams) {
        pendingEvents.put(sessionId, new HitlEvent(
            sessionId,
            "AWAITING_APPROVAL",
            toolName,
            toolParams,
            null,
            Instant.now()
        ));
    }

    /**
     * Complete and remove the processor for a session (on COMPLETED/FAILED).
     */
    public void complete(String sessionId) {
        BroadcastProcessor<SseEvent> proc = processors.remove(sessionId);
        if (proc != null) proc.onComplete();
        pendingEvents.remove(sessionId);
    }

    // ─── Event Types ────────────────────────────────────────────────────────

    public interface SseEvent {}

    public record HitlEvent(
        String sessionId,
        String type,        // AWAITING_APPROVAL, APPROVED, DENIED, RESUMED
        String toolName,
        String toolParams,
        String reason,
        Instant timestamp
    ) implements SseEvent {}

    public record AgentStatusEvent(
        String sessionId,
        String agentRole,
        String status,      // RUNNING, COMPLETED, FAILED
        Instant timestamp
    ) implements SseEvent {}
}
