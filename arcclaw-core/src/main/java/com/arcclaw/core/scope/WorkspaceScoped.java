package com.arcclaw.core.scope;

import jakarta.enterprise.context.NormalScope;
import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Inherited;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * CDI scope annotation tying bean lifecycle to an ArcClaw workspace session.
 * <p>
 * Beans annotated with {@code @WorkspaceScoped} are created once per sessionId
 * and shared across all threads operating within that session. The scope is
 * activated/deactivated by the REST filter or WebSocket lifecycle handler via
 * {@link WorkspaceScopeHolder}.
 * <p>
 * This is a <b>normal scope</b> (client proxies are generated), which means
 * injected references are proxy objects that delegate to the contextual
 * instance
 * bound to the current thread's session.
 *
 * @see WorkspaceScopeHolder
 * @see WorkspaceScopeContext
 */
@NormalScope(passivating = false)
@Inherited
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ ElementType.TYPE, ElementType.METHOD, ElementType.FIELD })
public @interface WorkspaceScoped {
}
