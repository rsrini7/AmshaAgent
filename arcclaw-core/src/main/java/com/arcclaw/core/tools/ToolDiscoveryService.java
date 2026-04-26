package com.arcclaw.core.tools;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

import com.arcclaw.core.agent.role.Capability;
import com.arcclaw.core.agent.role.CapabilityRegistry;
import com.arcclaw.core.agent.role.ToolMetadata;
import com.arcclaw.core.util.ClassUtils;
import io.quarkus.arc.Unremovable;
import org.jboss.logging.Logger;

/**
 * Handles CDI tool discovery, metadata scanning, and initialization-time
 * capability registration.
 */
@ApplicationScoped
@Unremovable
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
        capabilityRegistry.clear(); // idempotent - safe on hot-reload (§4.2)
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
        Class<?> toolClass = tool.getClass();

        // 1. Scan class-level ToolMetadata (backward compatibility)
        ToolMetadata classMeta = findAnnotation(toolClass, ToolMetadata.class);
        if (classMeta != null) {
            grantToRoles(classMeta.defaultRoles(), name);
        }

        // 2. Scan method-level @Capability (Tier 2-5 requirement)
        for (Method method : toolClass.getMethods()) {
            Capability cap = method.getAnnotation(Capability.class);
            if (cap != null) {
                if (!cap.value().equals(name)) {
                    throw new IllegalStateException(String.format(
                        "Mismatch between tool name '%s' and @Capability value '%s' on %s.%s. " +
                        "Capability name must match the tool name (§T2-5).",
                        name, cap.value(), ClassUtils.getCleanName(tool), method.getName()));
                }
                log.debugf("Discovered @Capability on %s.%s: %s", 
                    ClassUtils.getCleanName(tool), method.getName(), cap.value());
                grantToRoles(cap.defaultRoles(), name);
            }
        }
    }

    private <T extends Annotation> T findAnnotation(Class<?> clazz, Class<T> annotationClass) {
        Class<?> current = clazz;
        while (current != null && current != Object.class) {
            T ann = current.getAnnotation(annotationClass);
            if (ann != null) return ann;
            current = current.getSuperclass();
        }
        return null;
    }

    private void grantToRoles(String[] roles, String toolName) {
        if (roles == null) return;
        for (String roleId : roles) {
            log.debugf("Granting tool %s to role: %s", toolName, roleId);
            capabilityRegistry.grant(roleId, toolName);
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
