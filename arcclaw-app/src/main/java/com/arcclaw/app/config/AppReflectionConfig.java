package com.arcclaw.app.config;

import com.arcclaw.app.mcp.McpConnectResource;
import com.arcclaw.app.security.AuthErrorResponse;
import com.arcclaw.app.security.RateLimitErrorResponse;
import com.arcclaw.app.skill.SkillResource;
import com.arcclaw.app.ui.dto.AgentEvent;
import com.arcclaw.app.ui.dto.AgentStatusDto;
import com.arcclaw.app.ui.dto.ChatMessageDto;
import com.arcclaw.app.ui.dto.ChatRequest;
import com.arcclaw.app.ui.dto.CheckpointApprovalRequest;
import com.arcclaw.app.ui.dto.CheckpointApprovalResponse;
import com.arcclaw.app.ui.dto.PageDto;
import com.arcclaw.app.ui.dto.PendingApprovalDto;

import io.quarkus.runtime.annotations.RegisterForReflection;

@RegisterForReflection(
        targets = {
            McpConnectResource.ConnectRequest.class,
            McpConnectResource.ConnectResponse.class,
            McpConnectResource.DisconnectResponse.class,
            McpConnectResource.ServerEntry.class,
            SkillResource.MessageResponse.class,
            SkillResource.SkillResponse.class,
            AuthErrorResponse.class,
            RateLimitErrorResponse.class,
            AgentEvent.class,
            AgentStatusDto.class,
            ChatRequest.class,
            CheckpointApprovalRequest.class,
            CheckpointApprovalResponse.class,
            PendingApprovalDto.class,
            PageDto.class,
            ChatMessageDto.class,
        })
public class AppReflectionConfig {}
