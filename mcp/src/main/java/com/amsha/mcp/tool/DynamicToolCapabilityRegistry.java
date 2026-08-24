package com.amsha.mcp.tool;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

import java.time.Duration;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.LockSupport;

import com.amsha.core.scope.WorkspaceContext;
import com.amsha.core.tool.DynamicToolCatalog;
import com.amsha.tool.api.AgentTool;
import com.amsha.tool.api.DynamicToolDescriptor;

import io.quarkus.arc.Unremovable;

@ApplicationScoped
@Unremovable
public class DynamicToolCapabilityRegistry implements DynamicToolCatalog {
    private static final String UNKNOWN_SCOPE = "unknown";

    /** Maximum number of executable tools one MCP server may advertise per user. */
    public static final int MAX_TOOLS_PER_SERVER = 128;

    private final ConcurrentHashMap<String, ConcurrentHashMap<String, DynamicToolCapability>> registry =
            new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, AtomicInteger> activeInvocations = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Object> invocationLocks = new ConcurrentHashMap<>();
    private final Set<String> draining = ConcurrentHashMap.newKeySet();

    @Inject
    Instance<AgentTool> internalTools;

    /** Registers a tool for the current workspace owner. */
    public void register(DynamicToolCapability capability) {
        registerForScope(currentScope(), capability);
    }

    public void registerForUser(String userId, DynamicToolCapability capability) {
        registerForScope(userId == null ? currentScope() : userId, capability);
    }

    public void registerForScope(String scope, DynamicToolCapability capability) {
        Objects.requireNonNull(capability);
        String normalizedScope = normalizeScope(scope);
        if (isInternalToolName(capability.toolName())) {
            throw new IllegalArgumentException("Dynamic tool collides with internal tool: " + capability.toolName());
        }
        ConcurrentHashMap<String, DynamicToolCapability> scoped =
                registry.computeIfAbsent(normalizedScope, ignored -> new ConcurrentHashMap<>());
        synchronized (scoped) {
            DynamicToolCapability previous = scoped.get(capability.toolName());
            if (previous == null && countServerTools(scoped, capability.mcpServerName()) >= MAX_TOOLS_PER_SERVER) {
                throw new IllegalArgumentException("MCP server '" + capability.mcpServerName()
                        + "' exceeds the limit of " + MAX_TOOLS_PER_SERVER + " tools");
            }
            if (previous != null && !sameServer(previous, capability)) {
                throw new IllegalArgumentException("Dynamic tool name already registered in scope '" + normalizedScope
                        + "': " + capability.toolName());
            }
            // A reconnect may legitimately publish a revised description or schema. Replace only
            // when the name remains owned by the same MCP server; cross-server collisions stay fail-closed.
            scoped.put(capability.toolName(), capability);
        }
    }

    public void deregister(String toolName) {
        deregister(currentScope(), toolName);
    }

    public void deregister(String scope, String toolName) {
        ConcurrentHashMap<String, DynamicToolCapability> scoped = registry.get(normalizeScope(scope));
        if (scoped != null) {
            scoped.remove(toolName);
            if (scoped.isEmpty()) {
                registry.remove(normalizeScope(scope), scoped);
            }
        }
        draining.remove(invocationKey(scope, toolName));
        activeInvocations.remove(invocationKey(scope, toolName));
    }

    /** Claims an invocation before the capability is executed. Disabled/draining tools fail closed. */
    public boolean acquireInvocation(String scope, String toolName) {
        String key = invocationKey(scope, toolName);
        Object lock = invocationLocks.computeIfAbsent(key, ignored -> new Object());
        synchronized (lock) {
            if (draining.contains(key) || find(scope, toolName).isEmpty()) return false;
            activeInvocations
                    .computeIfAbsent(key, ignored -> new AtomicInteger())
                    .incrementAndGet();
            return true;
        }
    }

    /** Releases a previously claimed invocation. */
    public void releaseInvocation(String scope, String toolName) {
        String key = invocationKey(scope, toolName);
        AtomicInteger count = activeInvocations.get(key);
        if (count != null && count.decrementAndGet() <= 0) activeInvocations.remove(key, count);
    }

    /** Stops new invocations, waits briefly for accepted work, then removes the capability. */
    public void drainAndDeregister(String scope, String toolName, Duration timeout) {
        String key = invocationKey(scope, toolName);
        Object lock = invocationLocks.computeIfAbsent(key, ignored -> new Object());
        synchronized (lock) {
            draining.add(key);
        }
        long deadline = System.nanoTime() + timeout.toNanos();
        while (activeInvocations.getOrDefault(key, new AtomicInteger()).get() > 0 && System.nanoTime() < deadline) {
            LockSupport.parkNanos(Duration.ofMillis(10).toNanos());
        }
        deregister(scope, toolName);
        invocationLocks.remove(key, lock);
    }

    public Optional<DynamicToolCapability> find(String toolName) {
        return find(currentScope(), toolName);
    }

    public Optional<DynamicToolCapability> find(String scope, String toolName) {
        ConcurrentHashMap<String, DynamicToolCapability> scoped = registry.get(normalizeScope(scope));
        return scoped == null ? Optional.empty() : Optional.ofNullable(scoped.get(toolName));
    }

    public Collection<DynamicToolCapability> getAll() {
        return getAll(currentScope());
    }

    public Collection<DynamicToolCapability> getAll(String scope) {
        ConcurrentHashMap<String, DynamicToolCapability> scoped = registry.get(normalizeScope(scope));
        return scoped == null ? List.of() : Collections.unmodifiableCollection(scoped.values());
    }

    public boolean isRegistered(String toolName) {
        return find(toolName).isPresent();
    }

    @Override
    public Collection<DynamicToolDescriptor> listTools() {
        return getAll().stream()
                .map(capability -> new DynamicToolDescriptor(
                        capability.toolName(),
                        capability.description(),
                        capability.inputSchema(),
                        capability.category(),
                        capability.requiresApproval(),
                        capability.requiredOperation()))
                .toList();
    }

    public boolean isInternalToolName(String toolName) {
        if (internalTools == null || internalTools.isUnsatisfied()) {
            return false;
        }
        return internalTools.stream().anyMatch(tool -> toolName.equals(tool.name()));
    }

    private static boolean sameServer(DynamicToolCapability first, DynamicToolCapability second) {
        return first.mcpServerName().equals(second.mcpServerName());
    }

    private static long countServerTools(
            ConcurrentHashMap<String, DynamicToolCapability> scoped, String mcpServerName) {
        return scoped.values().stream()
                .filter(capability -> mcpServerName.equals(capability.mcpServerName()))
                .count();
    }

    private static String currentScope() {
        try {
            return normalizeScope(WorkspaceContext.getUserIdOrUnknown());
        } catch (RuntimeException _) {
            return UNKNOWN_SCOPE;
        }
    }

    private static String invocationKey(String scope, String toolName) {
        return normalizeScope(scope) + "\u0000" + toolName;
    }

    private static String normalizeScope(String scope) {
        return scope == null || scope.isBlank() ? UNKNOWN_SCOPE : scope;
    }
}
