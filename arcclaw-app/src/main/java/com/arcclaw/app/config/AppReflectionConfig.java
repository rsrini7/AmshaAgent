package com.arcclaw.app.config;

import com.arcclaw.app.channel.telegram.TelegramAgentHandler;
import com.arcclaw.app.mcp.McpConnectResource;
import com.arcclaw.app.security.AuthErrorResponse;
import com.arcclaw.app.security.RateLimitErrorResponse;
import com.arcclaw.app.skill.SkillResource;
import io.quarkus.runtime.annotations.RegisterForReflection;

@RegisterForReflection(
    targets = {
        TelegramAgentHandler.class,
        McpConnectResource.ConnectRequest.class,
        McpConnectResource.ConnectResponse.class,
        McpConnectResource.DisconnectResponse.class,
        McpConnectResource.ServerEntry.class,
        SkillResource.MessageResponse.class,
        SkillResource.SkillDto.class,
        AuthErrorResponse.class,
        RateLimitErrorResponse.class,
    }
)
public class AppReflectionConfig {
}
