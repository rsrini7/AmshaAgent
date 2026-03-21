package com.arcclaw.app.config;

import com.arcclaw.channel.api.structured.AgentContribution;
import com.arcclaw.channel.api.structured.ConflictSummary;
import com.arcclaw.channel.api.structured.SynthesisResult;

import io.quarkus.runtime.annotations.RegisterForReflection;

@RegisterForReflection(
    targets = {
        AgentContribution.class,
        ConflictSummary.class,
        SynthesisResult.class
    }
)
public class ChannelReflectionConfig {
    
}
