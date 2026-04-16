package com.arcclaw.core.tools;

import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

import com.arcclaw.core.agent.role.CapabilityRegistry;
import com.arcclaw.core.agent.role.ToolMetadata;
import org.jboss.logging.Logger;

/**
 * Handles CDI tool discovery, metadata scanning, and initialization-time
 * capability registration.
 */
@ApplicationScoped
public class ToolDiscoveryService {

    private static final Logger log = Logger.getLogger(ToolDiscoveryService.class);

    @Inject Instance<AgentTool> discoveredTools;
    @Inject CapabilityRegistry capabilityRegistry;
    @Inject ToolSchemaProvider schemaProvider;
    @Inject ToolSecretExtractor secretExtractor;

    private Map<String, AgentTool> toolIndex;
    private Map<String, ToolRegistry.ToolDescriptor> descriptorCache;
    private Map<String, Map<String, String>> secretsCache;

    public void discover() {
        log.info("ToolDiscoveryService: Discovering and indexing agent tools...");
        Map<String, AgentTool> mutableToolIndex = new HashMap<>();
        Map<String, ToolRegistry.ToolDescriptor> mutableDescriptorCache = new LinkedHashMap<>();
        Map<String, Map<String, String>> mutableSecretsCache = new HashMap<>();

        for (AgentTool tool : discoveredTools) {
            if (!tool.isEnabled()) continue;
            
            String name = tool.name();
            if (mutableToolIndex.containsKey(name)) {
                throw new IllegalStateException("Duplicate tool name registered: " + name + 
                    ". Tool beans must have unique names.");
            }
            
            mutableToolIndex.put(name, tool);
            
            // 1. Build secrets cache
            mutableSecretsCache.put(name, secretExtractor.extractRequiredSecrets(tool.getClass(), "sandboxCommand"));

            // 2. Build descriptor cache
            mutableDescriptorCache.put(name, buildDescriptor(tool));

            // 3. Discover metadata-driven capabilities
            registerCapabilities(tool);
        }
        
        this.toolIndex = Map.copyOf(mutableToolIndex);
        this.descriptorCache = Collections.unmodifiableMap(mutableDescriptorCache);
        this.secretsCache = Map.copyOf(mutableSecretsCache);
        log.infof("ToolDiscoveryService: Indexing complete. Total tools: %d", toolIndex.size());
    }

    private ToolRegistry.ToolDescriptor buildDescriptor(AgentTool tool) {
        String schema = schemaProvider.getJsonSchema(tool);
        return new ToolRegistry.ToolDescriptor(tool.name(), tool.description(), schema);
    }

    private void registerCapabilities(AgentTool tool) {
        String name = tool.name();
        var meta = tool.getClass().getAnnotation(ToolMetadata.class);
        if (meta == null) {
            Class<?> current = tool.getClass();
            while (current != null && current != Object.class) {
                meta = current.getAnnotation(ToolMetadata.class);
                if (meta != null) break;
                current = current.getSuperclass();
            }
        }

        if (meta != null) {
            log.debugf("Discovered dynamic tool capability: %s (granting to: %s)",
                      meta.capabilityId(), String.join(", ", meta.defaultRoles()));
            for (String roleId : meta.defaultRoles()) {
                capabilityRegistry.grant(roleId, name);
            }
        }
    }

    public Map<String, AgentTool> getToolIndex() {
        return toolIndex;
    }

    public Map<String, ToolRegistry.ToolDescriptor> getDescriptorCache() {
        return descriptorCache;
    }

    public Map<String, Map<String, String>> getSecretsCache() {
        return secretsCache;
    }
}
