package com.arcclaw.core.agent.persona;

import jakarta.inject.Qualifier;
import java.lang.annotation.*;

/**
 * CDI qualifier to distinguish agent persona beans.
 * <p>
 * Usage:
 * 
 * <pre>
 * {@literal @}ApplicationScoped
 * {@literal @}AgentRole("coordinator")
 * public class VishnuAgent implements Agent { ... }
 * </pre>
 */
@Qualifier
@Retention(RetentionPolicy.RUNTIME)
@Target({ ElementType.TYPE, ElementType.METHOD, ElementType.FIELD, ElementType.PARAMETER })
@Documented
public @interface AgentRole {
    /**
     * The unique role identifier (e.g., "coordinator", "researcher", "coder",
     * "debugger").
     */
    String value();
}
