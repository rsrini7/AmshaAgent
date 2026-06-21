package com.amsha.mcp.tool;

import jakarta.enterprise.context.ApplicationScoped;

import java.util.Collection;
import java.util.Collections;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import io.quarkus.arc.Unremovable;

@ApplicationScoped
@Unremovable
public class DynamicToolCapabilityRegistry {
    private final ConcurrentHashMap<String, DynamicToolCapability> registry = new ConcurrentHashMap<>();

    public void register(DynamicToolCapability capability) {
        Objects.requireNonNull(capability);
        registry.put(capability.toolName(), capability);
    }

    public void deregister(String toolName) {
        registry.remove(toolName);
    }

    public Optional<DynamicToolCapability> find(String toolName) {
        return Optional.ofNullable(registry.get(toolName));
    }

    public Collection<DynamicToolCapability> getAll() {
        return Collections.unmodifiableCollection(registry.values());
    }

    public boolean isRegistered(String toolName) {
        return registry.containsKey(toolName);
    }
}
