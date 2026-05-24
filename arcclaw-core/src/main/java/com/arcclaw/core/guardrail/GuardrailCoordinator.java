package com.arcclaw.core.guardrail;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

import com.arcclaw.core.audit.AuditService;
import com.arcclaw.core.audit.SecurityEventType;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.Tracer;
import org.jboss.logging.Logger;

@ApplicationScoped
public class GuardrailCoordinator {

    private static final Logger log = Logger.getLogger(GuardrailCoordinator.class);

    @Inject
    Instance<PreLlmGuard> inputGuards;

    @Inject
    Instance<PostLlmGuard> outputGuards;

    @Inject
    AuditService auditService;

    @Inject
    Tracer tracer;

    public InputResult runInputGuards(String sessionId, String userId, String userMessage, String assembledPrompt) {
        List<PreLlmGuard> sorted = sortInputGuards();
        String currentPrompt = assembledPrompt;

        for (PreLlmGuard guard : sorted) {
            Span span = tracer.spanBuilder("arcclaw.guardrail.input")
                    .setAttribute("arcclaw.guardrail.type", guard.getClass().getSimpleName())
                    .startSpan();

            GuardDecision decision;
            try {
                decision = guard.validate(sessionId, userId, userMessage, currentPrompt);
            } catch (Exception e) {
                log.warnf("[GUARD] %s threw: %s", guard.getClass().getSimpleName(), e.getMessage());
                span.recordException(e);
                span.end();
                return InputResult.blocked("Guardrail error: " + guard.getClass().getSimpleName());
            }

            span.setAttribute("arcclaw.guardrail.decision", decision.getClass().getSimpleName());
            auditService.record(sessionId, userId, "guardrail",
                    SecurityEventType.REFLEX_COMMAND,
                    guard.getClass().getSimpleName() + "=" + decision.getClass().getSimpleName());

            if (decision instanceof GuardDecision.Block b) {
                log.infof("[GUARD] %s blocked: %s", guard.getClass().getSimpleName(), b.reason());
                span.end();
                return InputResult.blocked(b.reason());
            }
            if (decision instanceof GuardDecision.Retry r) {
                log.infof("[GUARD] %s retry: %s", guard.getClass().getSimpleName(), r.reason());
                span.end();
                return InputResult.retry(r.reason(), r.correctedInput());
            }
            if (decision instanceof GuardDecision.Sanitize s) {
                currentPrompt = s.sanitizedInput();
                log.debugf("[GUARD] %s sanitized input", guard.getClass().getSimpleName());
            }
            span.end();
        }

        return InputResult.allowed(currentPrompt);
    }

    public OutputResult runOutputGuards(String sessionId, String userId, String userMessage, String llmResponse) {
        List<PostLlmGuard> sorted = sortOutputGuards();
        String currentResponse = llmResponse;

        for (PostLlmGuard guard : sorted) {
            Span span = tracer.spanBuilder("arcclaw.guardrail.output")
                    .setAttribute("arcclaw.guardrail.type", guard.getClass().getSimpleName())
                    .startSpan();

            GuardDecision decision;
            try {
                decision = guard.validate(sessionId, userId, userMessage, currentResponse);
            } catch (Exception e) {
                log.warnf("[GUARD] %s threw: %s", guard.getClass().getSimpleName(), e.getMessage());
                span.recordException(e);
                span.end();
                return OutputResult.blocked("Guardrail error: " + guard.getClass().getSimpleName());
            }

            span.setAttribute("arcclaw.guardrail.decision", decision.getClass().getSimpleName());
            auditService.record(sessionId, userId, "guardrail",
                    SecurityEventType.REFLEX_COMMAND,
                    guard.getClass().getSimpleName() + "=" + decision.getClass().getSimpleName());

            if (decision instanceof GuardDecision.Block b) {
                log.infof("[GUARD] %s blocked response: %s", guard.getClass().getSimpleName(), b.reason());
                span.end();
                return OutputResult.blocked(b.reason());
            }
            if (decision instanceof GuardDecision.Retry r) {
                log.infof("[GUARD] %s retry response: %s", guard.getClass().getSimpleName(), r.reason());
                span.end();
                return OutputResult.retry(r.reason(), r.correctedInput());
            }
            if (decision instanceof GuardDecision.Sanitize s) {
                currentResponse = s.sanitizedInput();
            }
            span.end();
        }

        return OutputResult.allowed(currentResponse);
    }

    public record InputResult(String sanitizedPrompt, boolean allowed, boolean retryable, String blockReason, String retryPrompt) {
        public static InputResult allowed(String prompt) { return new InputResult(prompt, true, false, null, null); }
        public static InputResult blocked(String reason) { return new InputResult(null, false, false, reason, null); }
        public static InputResult retry(String reason, String corrected) { return new InputResult(corrected, false, true, reason, corrected); }
    }

    public record OutputResult(String sanitizedResponse, boolean allowed, boolean retryable, String blockReason, String retryResponse) {
        public static OutputResult allowed(String response) { return new OutputResult(response, true, false, null, null); }
        public static OutputResult blocked(String reason) { return new OutputResult(null, false, false, reason, null); }
        public static OutputResult retry(String reason, String corrected) { return new OutputResult(corrected, false, true, reason, corrected); }
    }

    private List<PreLlmGuard> sortInputGuards() {
        List<PreLlmGuard> guards = new ArrayList<>();
        inputGuards.forEach(guards::add);
        guards.sort(Comparator.comparingInt(g -> extractPriority(g.getClass())));
        return guards;
    }

    private List<PostLlmGuard> sortOutputGuards() {
        List<PostLlmGuard> guards = new ArrayList<>();
        outputGuards.forEach(guards::add);
        guards.sort(Comparator.comparingInt(g -> extractPriority(g.getClass())));
        return guards;
    }

    private static int extractPriority(Class<?> clazz) {
        Priority p = clazz.getAnnotation(Priority.class);
        return p != null ? p.value() : 500;
    }
}
