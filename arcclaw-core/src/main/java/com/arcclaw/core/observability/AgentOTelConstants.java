package com.arcclaw.core.observability;

import io.opentelemetry.api.common.AttributeKey;

/**
 * Shared OpenTelemetry semantic names and attribute keys for the ArcClaw agent loop.
 */
public final class AgentOTelConstants {
    private AgentOTelConstants() {}

    // Span Names
    public static final String SPAN_SESSION         = "arcclaw.session";
    public static final String SPAN_AGENT_ITERATION = "agent.iteration";
    public static final String SPAN_TOOL_CALL        = "tool.call";
    public static final String SPAN_REFLEX_EXECUTE   = "reflex.execute";
    
    // Attribute Keys
    public static final AttributeKey<String> ATTR_SESSION_ID = AttributeKey.stringKey("arcclaw.session.id");
    public static final AttributeKey<String> ATTR_USER_ID    = AttributeKey.stringKey("arcclaw.user.id");
    public static final AttributeKey<String> ATTR_AGENT_ROLE = AttributeKey.stringKey("arcclaw.agent.role");
    public static final AttributeKey<String> ATTR_TOOL_NAME  = AttributeKey.stringKey("arcclaw.tool.name");
    public static final AttributeKey<Long>   ATTR_ITERATION  = AttributeKey.longKey("arcclaw.iteration");
}
