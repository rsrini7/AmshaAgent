package com.arcclaw.core.scope;

import io.quarkus.arc.InjectableBean;
import io.quarkus.arc.InjectableContext;
import jakarta.enterprise.context.ContextNotActiveException;
import jakarta.enterprise.context.spi.Contextual;
import jakarta.enterprise.context.spi.CreationalContext;

import java.lang.annotation.Annotation;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * Arc {@link InjectableContext} implementation for {@link WorkspaceScoped}.
 * <p>
 * <b>Architecture</b>:
 * <ul>
 * <li><b>Outer store</b>: {@code ConcurrentHashMap<String, SessionStore>} — one
 * entry per active session ID.
 * CHM handles cross-session concurrency without locking.</li>
 * <li><b>Inner {@code SessionStore}</b>: per-session bean instances guarded by
 * {@code synchronized}
 * on the store object. This is safe with virtual threads on JDK 25 (JEP 491
 * eliminates
 * pinning on synchronized blocks).</li>
 * </ul>
 * <p>
 * <b>Lifecycle</b>: The context is considered <i>active</i> when
 * {@link WorkspaceScopeHolder#get()} returns a non-null session ID.
 * Typically activated by a REST filter or WebSocket handler.
 *
 * @see WorkspaceScoped
 * @see WorkspaceScopeHolder
 */
public class WorkspaceScopeContext implements InjectableContext {

    /**
     * Outer store: sessionId → per-session bean instances.
     * <p>
     * ConcurrentHashMap provides safe concurrent access across sessions
     * without a global lock.
     */
    public WorkspaceScopeContext() {
        System.out.println("[ArcClaw Context] WorkspaceScopeContext INSTANTIATED! " + this);
        // throw new RuntimeException("Context Verified");
    }

    private final ConcurrentHashMap<String, SessionStore> sessions = new ConcurrentHashMap<>();

    // ── InjectableContext contract ──────────────────────────────────────────

    @Override
    public Class<? extends Annotation> getScope() {
        return WorkspaceScoped.class;
    }

    @Override
    public boolean isActive() {
        return WorkspaceScopeHolder.get() != null;
    }

    @Override
    public boolean isNormal() {
        return true; // @NormalScope → client proxies
    }

    /**
     * Get a contextual instance, creating it if absent.
     * <p>
     * Called by Arc-generated client proxies on every method invocation.
     * Uses {@code synchronized} on the per-session store (safe on JDK 25
     * virtual threads per JEP 491).
     */
    @SuppressWarnings("unchecked")
    @Override
    public <T> T get(Contextual<T> contextual, CreationalContext<T> creationalContext) {
        SessionStore store = getActiveStore();
        synchronized (store) {
            Object existing = store.beans.get(contextual);
            if (existing != null) {
                return (T) existing;
            }
            if (creationalContext == null) {
                return null;
            }
            T instance = contextual.create(creationalContext);
            store.beans.put(contextual, instance);
            store.creationalContexts.put(contextual, creationalContext);
            return instance;
        }
    }

    /**
     * Get an existing contextual instance without creating one.
     */
    @SuppressWarnings("unchecked")
    @Override
    public <T> T get(Contextual<T> contextual) {
        SessionStore store = getActiveStore();
        synchronized (store) {
            return (T) store.beans.get(contextual);
        }
    }

    /**
     * Optimized path for Arc — avoids proxy indirection when state is known.
     */
    @SuppressWarnings("unchecked")
    @Override
    public <T> T getIfActive(Contextual<T> contextual,
            Function<Contextual<T>, CreationalContext<T>> creationalContextSupplier) {
        String sessionId = WorkspaceScopeHolder.get();
        if (sessionId == null) {
            return null; // scope not active → return null, not exception
        }
        SessionStore store = sessions.get(sessionId);
        if (store == null) {
            store = sessions.computeIfAbsent(sessionId, k -> new SessionStore());
        }
        synchronized (store) {
            Object existing = store.beans.get(contextual);
            if (existing != null) {
                return (T) existing;
            }
            CreationalContext<T> ctx = creationalContextSupplier.apply(contextual);
            T instance = contextual.create(ctx);
            store.beans.put(contextual, instance);
            store.creationalContexts.put(contextual, ctx);
            return instance;
        }
    }

    /**
     * Destroy a single contextual instance within the current session.
     */
    @SuppressWarnings("unchecked")
    @Override
    public void destroy(Contextual<?> contextual) {
        SessionStore store = getActiveStore();
        synchronized (store) {
            Object instance = store.beans.remove(contextual);
            CreationalContext<?> ctx = store.creationalContexts.remove(contextual);
            if (instance != null) {
                ((Contextual<Object>) contextual).destroy(instance, (CreationalContext<Object>) ctx);
            }
        }
    }

    /**
     * Destroy ALL beans in the current session's scope.
     * <p>
     * Called when a workspace session ends (e.g., session close, timeout GC).
     */
    @Override
    public void destroy() {
        String sessionId = WorkspaceScopeHolder.get();
        if (sessionId != null) {
            destroySession(sessionId);
        }
    }

    /**
     * Destroy all beans and remove the store for a specific session.
     * <p>
     * Called by session lifecycle management, independent of the current thread's
     * bound session.
     *
     * @param sessionId the session to destroy
     */
    @SuppressWarnings("unchecked")
    public void destroySession(String sessionId) {
        SessionStore store = sessions.remove(sessionId);
        if (store != null) {
            synchronized (store) {
                for (var entry : store.beans.entrySet()) {
                    Contextual<Object> contextual = (Contextual<Object>) entry.getKey();
                    CreationalContext<Object> ctx = (CreationalContext<Object>) store.creationalContexts
                            .get(contextual);
                    contextual.destroy(entry.getValue(), ctx);
                }
                store.beans.clear();
                store.creationalContexts.clear();
            }
        }
    }

    // ── ContextState (Arc Dev UI integration) ──────────────────────────────

    @Override
    public ContextState getState() {
        String sessionId = WorkspaceScopeHolder.get();
        if (sessionId == null) {
            return new WorkspaceContextState(Collections.emptyMap());
        }
        SessionStore store = sessions.get(sessionId);
        if (store == null) {
            return new WorkspaceContextState(Collections.emptyMap());
        }
        synchronized (store) {
            // Snapshot — safe to read outside the lock
            Map<InjectableBean<?>, Object> snapshot = new HashMap<>();
            for (var entry : store.beans.entrySet()) {
                if (entry.getKey() instanceof InjectableBean<?> bean) {
                    snapshot.put(bean, entry.getValue());
                }
            }
            return new WorkspaceContextState(Collections.unmodifiableMap(snapshot));
        }
    }

    @Override
    public ContextState getStateIfActive() {
        if (!isActive()) {
            return null;
        }
        return getState();
    }

    @Override
    public void destroy(ContextState state) {
        // State is a read-only snapshot; nothing to destroy.
        // Individual session destruction is handled by destroySession().
    }

    // ── Internal helpers ───────────────────────────────────────────────────

    private SessionStore getActiveStore() {
        String sessionId = WorkspaceScopeHolder.get();
        if (sessionId == null) {
            throw new ContextNotActiveException(
                    "@WorkspaceScoped context is not active. " +
                            "Ensure WorkspaceScopeHolder.set(sessionId) was called before accessing scoped beans.");
        }
        return sessions.computeIfAbsent(sessionId, k -> new SessionStore());
    }

    // ── Inner classes ──────────────────────────────────────────────────────

    /**
     * Per-session bean store. Guarded by {@code synchronized(this)} for all
     * mutations. Using synchronized (not ReentrantLock) because JDK 25
     * JEP 491 eliminates virtual-thread pinning on monitors.
     */
    private static final class SessionStore {
        final Map<Contextual<?>, Object> beans = new HashMap<>();
        final Map<Contextual<?>, CreationalContext<?>> creationalContexts = new HashMap<>();
    }

    /**
     * Read-only snapshot of a session's bean state.
     * Required by Arc Dev UI for context introspection.
     */
    private record WorkspaceContextState(
            Map<InjectableBean<?>, Object> instances) implements ContextState {

        @Override
        public Map<InjectableBean<?>, Object> getContextualInstances() {
            return instances;
        }

        @Override
        public boolean isValid() {
            return true;
        }
    }
}
