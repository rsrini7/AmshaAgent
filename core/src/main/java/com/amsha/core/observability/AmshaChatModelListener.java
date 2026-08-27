package com.amsha.core.observability;

import dev.langchain4j.model.chat.listener.ChatModelErrorContext;
import dev.langchain4j.model.chat.listener.ChatModelListener;
import dev.langchain4j.model.chat.listener.ChatModelRequestContext;
import dev.langchain4j.model.chat.listener.ChatModelResponseContext;
import dev.langchain4j.model.output.TokenUsage;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

import com.amsha.core.scope.WorkspaceContext;
import com.amsha.core.scope.WorkspaceExecutors;
import com.amsha.security.api.AutonomyLevel;

import org.jboss.logging.Logger;

import io.micrometer.core.instrument.MeterRegistry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.quarkus.arc.Unremovable;
import io.quarkus.runtime.Startup;
import io.quarkus.virtual.threads.VirtualThreads;
import io.vertx.core.Context;

/**
 * LangChain4j {@link ChatModelListener} migration pilot adapter.
 *
 * <p>Operates under {@link ChatModelListenerRuntimeControl} with zero payload leakage:
 * prompt and response texts are never logged, serialized, or dispatched.
 */
@Startup
@ApplicationScoped
@Unremovable
public class AmshaChatModelListener implements ChatModelListener {

    private static final Logger LOG = Logger.getLogger(AmshaChatModelListener.class);
    private static final String ATTR_LEASE = "amsha.listener.lease";
    private static final String ATTR_START_NANOS = "amsha.listener.start_nanos";
    private static final String ATTR_WORKSPACE_CONTEXT = "amsha.listener.workspace_context";
    private static final String ATTR_MANUAL_INSTRUMENTATION = "amsha.listener.manual_instrumentation";
    private static final String ATTR_SPAN = "amsha.listener.span";
    private static final String SPAN_LISTENER_CALL = "amsha.llm.listener_call";

    private final ChatModelListenerRuntimeControl runtimeControl;
    private final Instance<MeterRegistry> meterRegistryInstance;
    private final Instance<Tracer> tracerInstance;
    private final Executor callbackExecutor;

    private ChatModelListenerRuntimeControl.Registration registration;

    @Inject
    public AmshaChatModelListener(
            ChatModelListenerRuntimeControl runtimeControl,
            Instance<MeterRegistry> meterRegistryInstance,
            Instance<Tracer> tracerInstance,
            @VirtualThreads ExecutorService callbackExecutor) {
        this(runtimeControl, meterRegistryInstance, tracerInstance, (Executor)
                WorkspaceExecutors.wrap(callbackExecutor));
    }

    public AmshaChatModelListener(
            ChatModelListenerRuntimeControl runtimeControl,
            Instance<MeterRegistry> meterRegistryInstance,
            Instance<Tracer> tracerInstance) {
        this(runtimeControl, meterRegistryInstance, tracerInstance, (Executor) Runnable::run);
    }

    private AmshaChatModelListener(
            ChatModelListenerRuntimeControl runtimeControl,
            Instance<MeterRegistry> meterRegistryInstance,
            Instance<Tracer> tracerInstance,
            Executor callbackExecutor) {
        this.runtimeControl = runtimeControl;
        this.meterRegistryInstance = meterRegistryInstance;
        this.tracerInstance = tracerInstance;
        this.callbackExecutor = callbackExecutor;
    }

    @PostConstruct
    void init() {
        this.registration = runtimeControl.registerReadyImplementation();
        if (meterRegistryInstance.isResolvable()) {
            MeterRegistry registry = meterRegistryInstance.get();
            registry.counter("amsha.observability.listener.shadow.tokens", "type", "input");
            registry.counter("amsha.observability.listener.shadow.tokens", "type", "output");
            registry.counter("amsha.observability.listener.shadow.calls", "outcome", "success");
            registry.counter("amsha.observability.listener.shadow.calls", "outcome", "error");
            registry.timer("amsha.observability.listener.shadow.duration");

            registry.counter("amsha.observability.listener.active.tokens", "type", "input");
            registry.counter("amsha.observability.listener.active.tokens", "type", "output");
            registry.counter("amsha.observability.listener.active.calls", "outcome", "success");
            registry.counter("amsha.observability.listener.active.calls", "outcome", "error");
            registry.timer("amsha.observability.listener.active.duration");
        }
        LOG.debug("AmshaChatModelListener registered with runtime control and pre-registered meters");
    }

    @PreDestroy
    void destroy() {
        if (registration != null) {
            registration.close();
        }
    }

    @Override
    public void onRequest(ChatModelRequestContext requestContext) {
        if (requestContext == null || requestContext.attributes() == null) {
            return;
        }

        // The listener is not allowed to add work to an event-loop callback. The
        // synchronous ChatModel call itself must already be on a worker/virtual
        // thread; this adapter fails closed when that contract is violated.
        if (Context.isOnEventLoopThread()) {
            LOG.warn("AmshaChatModelListener ignored on Vert.x event-loop thread");
            return;
        }

        ChatModelListenerRuntimeControl.Lease lease;
        try {
            var leaseOpt = runtimeControl.tryAcquire();
            if (leaseOpt.isEmpty()) {
                return;
            }
            lease = leaseOpt.get();
        } catch (RuntimeException e) {
            LOG.warnf(
                    "AmshaChatModelListener request admission skipped (%s)",
                    e.getClass().getSimpleName());
            return;
        }

        try {
            requestContext.attributes().put(ATTR_LEASE, lease);
            requestContext.attributes().put(ATTR_START_NANOS, System.nanoTime());

            WorkspaceContextSnapshot workspaceContext = WorkspaceContextSnapshot.capture();
            if (workspaceContext != null) {
                requestContext.attributes().put(ATTR_WORKSPACE_CONTEXT, workspaceContext);
            }
            String sessionId = workspaceContext != null ? workspaceContext.sessionId() : null;
            boolean manuallyInstrumented = manualInstrumentationActive();
            requestContext.attributes().put(ATTR_MANUAL_INSTRUMENTATION, manuallyInstrumented);

            if (lease.mode() == ChatModelListenerMode.ACTIVE
                    && !manuallyInstrumented
                    && tracerInstance.isResolvable()) {
                String modelName = extractModelName(requestContext);
                var builder = tracerInstance
                        .get()
                        .spanBuilder(SPAN_LISTENER_CALL)
                        .setAttribute(AgentOTelConstants.ATTR_OI_SPAN_KIND, AgentOTelConstants.OI_KIND_LLM)
                        .setAttribute(AgentOTelConstants.ATTR_LLM_MODEL_NAME, modelName);
                if (sessionId != null) {
                    builder.setAttribute(AgentOTelConstants.ATTR_SESSION_ID, sessionId);
                }
                Span span = builder.startSpan();
                requestContext.attributes().put(ATTR_SPAN, span);
            }
        } catch (RuntimeException e) {
            abortInvocation(requestContext.attributes(), lease, e, "request");
        }
    }

    @Override
    public void onResponse(ChatModelResponseContext responseContext) {
        if (responseContext == null || responseContext.attributes() == null) {
            return;
        }

        ChatModelListenerRuntimeControl.Lease lease = null;
        try {
            Object leaseObj = responseContext.attributes().remove(ATTR_LEASE);
            if (!(leaseObj instanceof ChatModelListenerRuntimeControl.Lease)) {
                return;
            }

            // Keep the lease available to the defensive catch below if dispatch itself fails.
            ChatModelListenerRuntimeControl.Lease acquiredLease = (ChatModelListenerRuntimeControl.Lease) leaseObj;
            lease = acquiredLease;
            dispatchCompletion(
                    responseContext.attributes(),
                    acquiredLease,
                    () -> completeResponse(responseContext, acquiredLease));
        } catch (RuntimeException e) {
            if (lease != null) {
                abortInvocation(responseContext.attributes(), lease, e, "response");
            } else {
                LOG.warnf(
                        "AmshaChatModelListener response telemetry skipped (%s)",
                        e.getClass().getSimpleName());
            }
        }
    }

    private void completeResponse(
            ChatModelResponseContext responseContext, ChatModelListenerRuntimeControl.Lease lease) {
        try (lease) {
            long startNanos = startNanos(responseContext.attributes());
            long durationMs = Math.max(0L, (System.nanoTime() - startNanos) / 1_000_000L);

            int inputTokens = 0;
            int outputTokens = 0;
            if (responseContext.chatResponse() != null) {
                TokenUsage usage = responseContext.chatResponse().tokenUsage();
                if (usage != null) {
                    inputTokens = usage.inputTokenCount() != null ? usage.inputTokenCount() : 0;
                    outputTokens = usage.outputTokenCount() != null ? usage.outputTokenCount() : 0;
                }
            }

            boolean manuallyInstrumented = manualInstrumentation(responseContext.attributes());
            if (lease.mode() == ChatModelListenerMode.SHADOW) {
                recordShadowMetrics(inputTokens, outputTokens, durationMs, true);
            } else if (lease.mode() == ChatModelListenerMode.ACTIVE && !manuallyInstrumented) {
                Object spanObj = responseContext.attributes().get(ATTR_SPAN);
                if (spanObj instanceof Span span) {
                    span.setAttribute(AgentOTelConstants.ATTR_LLM_INPUT_TOKENS, (long) inputTokens);
                    span.setAttribute(AgentOTelConstants.ATTR_LLM_OUTPUT_TOKENS, (long) outputTokens);
                    span.setAttribute(AgentOTelConstants.ATTR_INPUT_TOKENS, (long) inputTokens);
                    span.setAttribute(AgentOTelConstants.ATTR_OUTPUT_TOKENS, (long) outputTokens);
                }
                recordActiveMetrics(inputTokens, outputTokens, durationMs, true);
            }
        } finally {
            endSpanQuietly(responseContext.attributes());
            clearInvocationAttributes(responseContext.attributes());
        }
    }

    @Override
    public void onError(ChatModelErrorContext errorContext) {
        if (errorContext == null || errorContext.attributes() == null) {
            return;
        }

        ChatModelListenerRuntimeControl.Lease lease = null;
        try {
            Object leaseObj = errorContext.attributes().remove(ATTR_LEASE);
            if (!(leaseObj instanceof ChatModelListenerRuntimeControl.Lease)) {
                return;
            }

            // Keep the lease available to the defensive catch below if dispatch itself fails.
            ChatModelListenerRuntimeControl.Lease acquiredLease = (ChatModelListenerRuntimeControl.Lease) leaseObj;
            lease = acquiredLease;
            dispatchCompletion(
                    errorContext.attributes(), acquiredLease, () -> completeError(errorContext, acquiredLease));
        } catch (RuntimeException e) {
            if (lease != null) {
                abortInvocation(errorContext.attributes(), lease, e, "error");
            } else {
                LOG.warnf(
                        "AmshaChatModelListener error telemetry skipped (%s)",
                        e.getClass().getSimpleName());
            }
        }
    }

    private void dispatchCompletion(
            Map<Object, Object> attributes, ChatModelListenerRuntimeControl.Lease lease, Runnable callback) {
        Runnable contextualCallback = () -> {
            try {
                withWorkspaceContext(attributes, callback);
            } catch (RuntimeException e) {
                abortInvocation(attributes, lease, e, "completion");
            }
        };
        if (!Context.isOnEventLoopThread()) {
            contextualCallback.run();
            return;
        }

        try {
            callbackExecutor.execute(contextualCallback);
        } catch (RuntimeException e) {
            abortInvocation(attributes, lease, e, "callback dispatch");
        }
    }

    private void completeError(ChatModelErrorContext errorContext, ChatModelListenerRuntimeControl.Lease lease) {
        try (lease) {
            long startNanos = startNanos(errorContext.attributes());
            long durationMs = Math.max(0L, (System.nanoTime() - startNanos) / 1_000_000L);

            if (lease.mode() == ChatModelListenerMode.SHADOW) {
                recordShadowMetrics(0, 0, durationMs, false);
            } else if (lease.mode() == ChatModelListenerMode.ACTIVE
                    && !manualInstrumentation(errorContext.attributes())) {
                Object spanObj = errorContext.attributes().get(ATTR_SPAN);
                if (spanObj instanceof Span span) {
                    span.setStatus(StatusCode.ERROR, "LLM provider invocation failed");
                    if (errorContext.error() != null) {
                        span.setAttribute(
                                AgentOTelConstants.ATTR_ERROR_TYPE,
                                errorContext.error().getClass().getSimpleName());
                    }
                }
                recordActiveMetrics(0, 0, durationMs, false);
            }
        } finally {
            endSpanQuietly(errorContext.attributes());
            clearInvocationAttributes(errorContext.attributes());
        }
    }

    private static boolean manualInstrumentationActive() {
        return AgentOTelConstants.MANUAL_LLM_INSTRUMENTATION.isBound()
                && Boolean.TRUE.equals(AgentOTelConstants.MANUAL_LLM_INSTRUMENTATION.get());
    }

    private static boolean manualInstrumentation(Map<Object, Object> attributes) {
        return Boolean.TRUE.equals(attributes.get(ATTR_MANUAL_INSTRUMENTATION));
    }

    private static void clearInvocationAttributes(Map<Object, Object> attributes) {
        attributes.remove(ATTR_LEASE);
        attributes.remove(ATTR_START_NANOS);
        attributes.remove(ATTR_WORKSPACE_CONTEXT);
        attributes.remove(ATTR_MANUAL_INSTRUMENTATION);
        attributes.remove(ATTR_SPAN);
    }

    private static long startNanos(Map<Object, Object> attributes) {
        Object value = attributes.get(ATTR_START_NANOS);
        return value instanceof Number number ? number.longValue() : System.nanoTime();
    }

    private static void abortInvocation(
            Map<Object, Object> attributes,
            ChatModelListenerRuntimeControl.Lease lease,
            RuntimeException failure,
            String phase) {
        endSpanQuietly(attributes);
        closeLeaseQuietly(lease);
        clearInvocationAttributes(attributes);
        LOG.warnf(
                "AmshaChatModelListener %s telemetry skipped (%s)",
                phase, failure.getClass().getSimpleName());
    }

    private static void endSpanQuietly(Map<Object, Object> attributes) {
        Object spanObj = attributes.remove(ATTR_SPAN);
        if (spanObj instanceof Span span) {
            try {
                span.end();
            } catch (RuntimeException e) {
                LOG.debugf(
                        "AmshaChatModelListener span cleanup skipped (%s)",
                        e.getClass().getSimpleName());
            }
        }
    }

    private static void closeLeaseQuietly(ChatModelListenerRuntimeControl.Lease lease) {
        try {
            lease.close();
        } catch (RuntimeException e) {
            LOG.warnf(
                    "AmshaChatModelListener lease cleanup skipped (%s)",
                    e.getClass().getSimpleName());
        }
    }

    private static void withWorkspaceContext(Map<Object, Object> attributes, Runnable callback) {
        Object snapshot = attributes.get(ATTR_WORKSPACE_CONTEXT);
        if (snapshot instanceof WorkspaceContextSnapshot contextSnapshot) {
            contextSnapshot.run(callback);
        } else {
            callback.run();
        }
    }

    private void recordShadowMetrics(int inputTokens, int outputTokens, long durationMs, boolean success) {
        if (meterRegistryInstance.isResolvable()) {
            MeterRegistry registry = meterRegistryInstance.get();
            if (inputTokens > 0) {
                registry.counter("amsha.observability.listener.shadow.tokens", "type", "input")
                        .increment(inputTokens);
            }
            if (outputTokens > 0) {
                registry.counter("amsha.observability.listener.shadow.tokens", "type", "output")
                        .increment(outputTokens);
            }
            registry.counter("amsha.observability.listener.shadow.calls", "outcome", success ? "success" : "error")
                    .increment();
            registry.timer("amsha.observability.listener.shadow.duration").record(durationMs, TimeUnit.MILLISECONDS);
        }
    }

    private void recordActiveMetrics(int inputTokens, int outputTokens, long durationMs, boolean success) {
        if (meterRegistryInstance.isResolvable()) {
            MeterRegistry registry = meterRegistryInstance.get();
            if (inputTokens > 0) {
                registry.counter("amsha.observability.listener.active.tokens", "type", "input")
                        .increment(inputTokens);
            }
            if (outputTokens > 0) {
                registry.counter("amsha.observability.listener.active.tokens", "type", "output")
                        .increment(outputTokens);
            }
            registry.counter("amsha.observability.listener.active.calls", "outcome", success ? "success" : "error")
                    .increment();
            registry.timer("amsha.observability.listener.active.duration").record(durationMs, TimeUnit.MILLISECONDS);
        }
    }

    private String extractModelName(ChatModelRequestContext requestContext) {
        if (requestContext.chatRequest() != null && requestContext.chatRequest().parameters() != null) {
            String model = requestContext.chatRequest().parameters().modelName();
            if (model != null && !model.isBlank()) {
                return model;
            }
        }
        return "openai-model";
    }

    private record WorkspaceContextSnapshot(
            String sessionId,
            String userId,
            String chatId,
            AutonomyLevel autonomy,
            String agentRole,
            String channelType,
            String platformId) {

        private static WorkspaceContextSnapshot capture() {
            String sessionId = value(WorkspaceContext.SESSION_ID);
            if (sessionId == null || sessionId.isBlank()) {
                return null;
            }
            return new WorkspaceContextSnapshot(
                    sessionId,
                    value(WorkspaceContext.USER_ID),
                    value(WorkspaceContext.CHAT_ID),
                    value(WorkspaceContext.AUTONOMY),
                    value(WorkspaceContext.AGENT_ROLE),
                    value(WorkspaceContext.CHANNEL_TYPE),
                    value(WorkspaceContext.CHANNEL_PLATFORM_ID));
        }

        private void run(Runnable callback) {
            WorkspaceContext.run(
                    sessionId,
                    userId != null ? userId : "unknown",
                    chatId,
                    autonomy != null ? autonomy : AutonomyLevel.FULL,
                    agentRole != null ? agentRole : "unknown",
                    channelType,
                    platformId,
                    callback);
        }

        private static <T> T value(ScopedValue<T> scopedValue) {
            return scopedValue.isBound() ? scopedValue.get() : null;
        }
    }
}
