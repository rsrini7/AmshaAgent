package com.amsha.core.agent.role;

import java.lang.annotation.ElementType;
import java.lang.annotation.Inherited;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import com.amsha.core.tools.ToolCategory;

/**
 * Metadata for auto-registering a tool's capability and default role grants.
 * <p>
 * This annotation allows tool extensions to declare their capabilities without
 * modifying the core {@link ToolCapability} enum or {@link AgentRoleEnum} definitions.
 */
@Inherited
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface ToolMetadata {
    /**
     * The unique capability identifier (e.g., "BROWSER_NAVIGATE").
     * Should match the name returned by AgentTool.name() or be a symbolic constant.
     */
    String capabilityId();

    /**
     * A human-readable description of what this tool enables.
     */
    String description();

    /**
     * The list of built-in role IDs (e.g., "researcher", "synthesizer") that
     * should be granted this capability by default.
     */
    String[] defaultRoles() default {};

    /** Execution bulkhead category. INTERNAL preserves existing behavior. */
    ToolCategory category() default ToolCategory.INTERNAL;
}
