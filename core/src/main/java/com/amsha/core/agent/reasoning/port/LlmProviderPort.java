package com.amsha.core.agent.reasoning.port;

import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;

/**
 * Abstraction seam between the reasoning engine and the underlying LLM provider SDK.
 *
 * NOTE: This port intentionally uses LangChain4j base types (ChatRequest/ChatResponse)
 * because owned message types would require re-implementing the full message/tool protocol.
 * The boundary prevents provider-SDK-specific exceptions from leaking into the engine.
 * Tracked for future owned-type extraction if the SDK dependency is ever removed.
 */
public interface LlmProviderPort {

    /**
     * Executes a chat request using the global default model.
     * @param request The chat request.
     * @return The chat response.
     * @throws com.amsha.llm.api.error.LlmProviderException if the provider throws an error.
     */
    ChatResponse nextStep(ChatRequest request);

    /**
     * Executes a chat request routed to the model resolved for the given agent role.
     * Resolution: persona override → group default → global default.
     *
     * @param request   The chat request.
     * @param agentRole The agent role ID (e.g. "coder", "debugger"). Used for per-persona model routing.
     * @return The chat response.
     * @throws com.amsha.llm.api.error.LlmProviderException if the provider throws an error.
     */
    ChatResponse nextStep(ChatRequest request, String agentRole);
}
