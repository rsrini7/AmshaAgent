package com.amsha.app.config;

import com.amsha.app.api.AdminDirectiveResource;
import com.amsha.app.api.ArchivalMemoryAdminResource;
import com.amsha.app.api.CoreMemoryAdminResource;
import com.amsha.app.api.PromptAdminResource;
import com.amsha.app.api.RecallMemoryAdminResource;
import com.amsha.app.mcp.McpConnectResource;
import com.amsha.app.security.AuthErrorResponse;
import com.amsha.app.security.RateLimitErrorResponse;
import com.amsha.app.skill.SkillResource;
import com.amsha.app.ui.dto.AgentEvent;
import com.amsha.app.ui.dto.AgentStatusDto;
import com.amsha.app.ui.dto.ChatMessageDto;
import com.amsha.app.ui.dto.ChatRequest;
import com.amsha.app.ui.dto.CheckpointApprovalRequest;
import com.amsha.app.ui.dto.CheckpointApprovalResponse;
import com.amsha.app.ui.dto.PageDto;
import com.amsha.app.ui.dto.PendingApprovalDto;
import com.amsha.core.api.dto.ActiveAgentResponse;

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
            AdminDirectiveResource.ReconcileResponse.class,
            ArchivalMemoryAdminResource.EvictResponse.class,
            RecallMemoryAdminResource.PurgeResponse.class,
            CoreMemoryAdminResource.CoreMemoryUpdateRequest.class,
            PromptAdminResource.PromptUpdateRequest.class,
            ActiveAgentResponse.class,
        })
public class AppReflectionConfig {}
