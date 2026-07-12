package com.amsha.core.tools;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

import java.util.Optional;

import com.amsha.core.agent.role.ToolCapability;
import com.amsha.core.agent.role.ToolMetadata;
import com.amsha.core.tool.DynamicToolCategoryLookup;

import org.jboss.logging.Logger;

@ApplicationScoped
public class ToolCategoryMapper {

    private static final Logger log = Logger.getLogger(ToolCategoryMapper.class);

    @Inject
    ToolRegistry toolRegistry;

    @Inject
    Instance<DynamicToolCategoryLookup> dynamicCategoryLookups;

    public ToolCategory categoryFor(String toolName) {
        if (toolName == null) return ToolCategory.INTERNAL;
        String base = toolName.contains(":") ? toolName.split(":", 2)[1] : toolName;

        ToolCategory resolved = resolve(base);
        if (resolved == ToolCategory.INTERNAL
                && dynamicCategoryLookups != null
                && !dynamicCategoryLookups.isUnsatisfied()
                && dynamicCategoryLookups
                        .get()
                        .categoryFor(base)
                        .filter(category -> category == ToolCategory.INTERNAL)
                        .isPresent()) {
            log.warnf("Dynamic tool '%s' resolved to INTERNAL execution category", base);
        }
        return resolved;
    }

    private ToolCategory resolve(String base) {
        // 1. Check if a registered agent tool declares its own category
        if (toolRegistry != null) {
            Optional<AgentTool> tool = toolRegistry.findTool(base);
            if (tool.isPresent()) {
                ToolCategory declared = tool.get().category();
                if (declared != ToolCategory.INTERNAL) {
                    return declared;
                }
                ToolMetadata metadata = findMetadata(tool.get().getClass());
                if (metadata != null && metadata.category() != ToolCategory.INTERNAL) {
                    return metadata.category();
                }
            }
        }

        if (dynamicCategoryLookups != null && !dynamicCategoryLookups.isUnsatisfied()) {
            Optional<ToolCategory> dynamicCategory =
                    dynamicCategoryLookups.get().categoryFor(base);
            if (dynamicCategory.isPresent() && dynamicCategory.get() != ToolCategory.INTERNAL) {
                return dynamicCategory.get();
            }
        }

        // Fall back to ToolCapability for built-in tools
        return ToolCapability.fromToolName(base).map(ToolCapability::category).orElse(ToolCategory.INTERNAL);
    }

    private ToolMetadata findMetadata(Class<?> toolClass) {
        Class<?> current = toolClass;
        while (current != null && current != Object.class) {
            ToolMetadata metadata = current.getAnnotation(ToolMetadata.class);
            if (metadata != null) return metadata;
            current = current.getSuperclass();
        }
        return null;
    }
}
