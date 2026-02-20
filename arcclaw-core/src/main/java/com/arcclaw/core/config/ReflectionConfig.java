package com.arcclaw.core.config;

import com.arcclaw.core.agent.AgentResult;
import com.arcclaw.core.agent.ReasoningStep;
import com.arcclaw.core.api.dto.CreateSessionResponse;
import com.arcclaw.core.api.dto.RunAgentRequest;
import com.arcclaw.core.api.dto.SessionStatusResponse;
import io.quarkus.runtime.annotations.RegisterForReflection;

/**
 * Ensures critical DTOs and Agent models are protected from dead-code
 * elimination
 * and are fully available for reflection in GraalVM Native Image mode.
 */
@RegisterForReflection(targets = {
        ReasoningStep.class,
        AgentResult.class,
        RunAgentRequest.class,
        CreateSessionResponse.class,
        SessionStatusResponse.class
})
public class ReflectionConfig {
}
