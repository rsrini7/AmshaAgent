package com.amsha.core.agent.sse;

import jakarta.enterprise.context.ApplicationScoped;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.operators.multi.processors.BroadcastProcessor;

@ApplicationScoped
public class AgentSseService {

    // Active SSE subscribers per sessionId
    private final ConcurrentHashMap<String, BroadcastProcessor<SseEvent>> processors = new ConcurrentHashMap<>();

    // Pending events for sessions that survived restart (no active subscriber yet)
    private final ConcurrentHashMap<String, SseEvent> pendingEvents = new ConcurrentHashMap<>();

    /**
     * Subscribe to SSE events for a session.
     * If a pending event exists (e.g. zombie AWAITING_APPROVAL from restart),
     * it is replayed immediately on subscribe.
     * Includes a 25-second heartbeat to keep the connection alive through
     * proxies and browsers that close idle SSE connections.
     */
    public Multi<SseEvent> subscribe(String sessionId) {
        BroadcastProcessor<SseEvent> proc = processors.computeIfAbsent(sessionId, id -> BroadcastProcessor.create());

        // Replay any pending event immediately on connect (zombie approval from restart)
        SseEvent pending = pendingEvents.remove(sessionId);
        if (pending != null) {
            proc.onNext(pending);
        }

        // Heartbeat stream - every 25s to prevent proxy/browser idle timeout
        Multi<SseEvent> heartbeat = Multi.createFrom()
                .ticks()
                .every(Duration.ofSeconds(25))
                .map(tick -> (SseEvent) new HeartbeatEvent(Instant.now()));

        // Merge live events + heartbeat
        return Multi.createBy().merging().streams(proc.toHotStream().onItem().castTo(SseEvent.class), heartbeat);
    }

    /**
     * Publish a live event to any active subscriber for this session.
     * If no subscriber exists yet, the event is dropped (live events only).
     * AWAITING_APPROVAL events are also stored for late-connecting subscribers.
     */
    public void publish(String sessionId, SseEvent event) {
        BroadcastProcessor<SseEvent> proc = processors.get(sessionId);
        if (proc != null) {
            proc.onNext(event);
        }
        // Broadcast HITL events to all active session processors so connected UIs
        // get immediate notification of approvals across all sessions.
        if (event instanceof HitlEvent) {
            for (Map.Entry<String, BroadcastProcessor<SseEvent>> entry : processors.entrySet()) {
                if (!entry.getKey().equals(sessionId)) {
                    entry.getValue().onNext(event);
                }
            }
        }
        // Store AWAITING_APPROVAL so UI gets it even if SSE connects after event fires
        if (event instanceof HitlEvent h
                && HitlEventType.AWAITING_APPROVAL.wireValue().equals(h.type())) {
            pendingEvents.put(sessionId, event);
        }
    }

    /**
     * Called by SessionReaper on startup for surviving AWAITING_APPROVAL checkpoints.
     * Stores the event so the UI gets it when it subscribes after restart.
     */
    public void republishOnSubscribe(String sessionId, String toolName, String toolParams) {
        pendingEvents.put(
                sessionId,
                new HitlEvent(
                        sessionId,
                        HitlEventType.AWAITING_APPROVAL.wireValue(),
                        toolName,
                        toolParams,
                        null, // riskLevel
                        null,
                        Instant.now()));
    }

    /**
     * Complete and remove the processor for a session (on COMPLETED/FAILED).
     * Cleans up both the active processor and any pending replay events.
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
            String type, // AWAITING_APPROVAL, APPROVED, DENIED, RESUMED
            String toolName,
            String toolParams,
            String riskLevel,
            String reason,
            Instant timestamp)
            implements SseEvent {}

    public record AgentStatusEvent(
            String sessionId,
            String agentRole,
            String status, // RUNNING, COMPLETED, FAILED
            Instant timestamp)
            implements SseEvent {}

    public record HeartbeatEvent(Instant timestamp) implements SseEvent {}
}
