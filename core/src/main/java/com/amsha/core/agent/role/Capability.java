package com.amsha.core.agent.role;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a method (usually exhibit() or execute()) as providing a specific tool capability.
 * Requirement T2-5 implementation.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface Capability {
    /**
     * The unique capability identifier (e.g., "browser_navigate").
     * Should match the value in ToolCapability enum or AgentTool.name().
     */
    String value();

    /**
     * Optional list of built-in role IDs (e.g., "researcher", "coordinator") that
     * should be granted this capability by default.
     */
    String[] defaultRoles() default {};
}
