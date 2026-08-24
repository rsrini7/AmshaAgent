package com.amsha.core.tools;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.spi.CreationalContext;
import jakarta.enterprise.inject.spi.Bean;
import jakarta.enterprise.inject.spi.BeanManager;
import jakarta.inject.Inject;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import com.amsha.core.agent.role.Capability;
import com.amsha.core.agent.role.CapabilityRegistry;
import com.amsha.core.util.ClassUtils;
import com.amsha.tool.api.AgentTool;
import com.amsha.tool.api.ToolMetadata;

import org.jboss.logging.Logger;

import io.quarkus.arc.Unremovable;

/**
 * Handles CDI tool discovery, metadata scanning, and initialization-time
 * capability registration via BeanManager.
 */
@ApplicationScoped
@Unremovable
public class ToolDiscoveryService {

    private static final Logger log = Logger.getLogger(ToolDiscoveryService.class);

    @Inject
    BeanManager beanManager;

    @Inject
    CapabilityRegistry capabilityRegistry;

    @Inject
    ToolSchemaProvider schemaProvider;

    @Inject
    ToolSecretExtractor secretExtractor;

    private Map<String, AgentTool> toolIndex;
    private Map<String, ToolRegistry.ToolDescriptor> descriptorCache;
    private Map<String, Map<String, String>> secretsCache;

    public void discover() {
        log.info("ToolDiscoveryService: Discovering and indexing agent tools...");
        Map<String, AgentTool> mutableToolIndex = new TreeMap<>();
        Map<String, ToolRegistry.ToolDescriptor> mutableDescriptorCache = new TreeMap<>();
        Map<String, Map<String, String>> mutableSecretsCache = new TreeMap<>();

        Set<Bean<?>> allBeans = beanManager != null ? beanManager.getBeans(Object.class) : null;
        if (allBeans != null) {
            List<Bean<?>> toolBeans = allBeans.stream()
                    .filter(b -> b != null && b.getBeanClass() != null)
                    .filter(b -> (b.getTypes() != null && b.getTypes().contains(AgentTool.class))
                            || AgentTool.class.isAssignableFrom(b.getBeanClass()))
                    .sorted(Comparator.comparing(b -> b.getBeanClass().getName()))
                    .toList();

            for (Bean<?> bean : toolBeans) {
                CreationalContext<?> ctx = beanManager.createCreationalContext(bean);
                AgentTool tool = (AgentTool) beanManager.getReference(bean, AgentTool.class, ctx);
                if (tool == null || !tool.isEnabled()) continue;

                String name = tool.name();
                if (mutableToolIndex.containsKey(name)) {
                    throw new IllegalStateException(
                            "Duplicate tool name registered: " + name + ". Tool beans must have unique names.");
                }

                mutableToolIndex.put(name, tool);

                // 1. Build secrets cache
                mutableSecretsCache.put(
                        name, secretExtractor.extractRequiredSecrets(tool.getClass(), "sandboxCommand"));

                // 2. Build descriptor cache
                mutableDescriptorCache.put(name, buildDescriptor(tool));

                // 3. Discover metadata-driven capabilities
                registerCapabilities(tool);
            }
        }

        this.toolIndex = Collections.unmodifiableMap(mutableToolIndex);
        this.descriptorCache = Collections.unmodifiableMap(mutableDescriptorCache);
        this.secretsCache = Collections.unmodifiableMap(mutableSecretsCache);
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
                            "Mismatch between tool name '%s' and @Capability value '%s' on %s.%s. "
                                    + "Capability name must match the tool name (§T2-5).",
                            name, cap.value(), ClassUtils.getCleanName(tool), method.getName()));
                }
                log.debugf(
                        "Discovered @Capability on %s.%s: %s",
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
