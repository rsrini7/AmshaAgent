package com.amsha.tool.api;

import io.quarkus.runtime.annotations.RegisterForReflection;

/**
 * GraalVM Native Image reflection configuration for amsha-tool-api.
 * Contract types moved here from core (extract-core-subsystems S5); the
 * {@code ToolResponse} family entries were relocated from CoreReflectionConfig.
 */
@RegisterForReflection(
        targets = {
            AgentTool.class,
            ToolResponse.class,
            ToolResponse.ToolSuccess.class,
            ToolResponse.ToolFailure.class,
            ToolResponse.ToolPending.class,
            ToolExecutionContext.class,
            ToolCategory.class,
            IdempotencyMode.class,
            ToolCapability.class,
            ToolMetadata.class,
            AgentSentinels.class
        })
public class ToolApiReflectionConfig {}
