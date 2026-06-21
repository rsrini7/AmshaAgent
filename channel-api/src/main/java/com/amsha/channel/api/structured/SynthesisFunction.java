package com.amsha.channel.api.structured;

/**
 * Interface for the Dattatreya (Synthesizer) agent.
 */
@AgentFunction("Synthesizes multi-agent findings into a final resolution")
public interface SynthesisFunction {
    SynthesisResult synthesize(String combinedContext);
}
