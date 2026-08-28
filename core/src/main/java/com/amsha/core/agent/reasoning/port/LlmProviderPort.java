package com.amsha.core.agent.reasoning.port;

import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;

import com.amsha.llm.api.MultimodalCapability;

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

    /**
     * Executes a toolless structured-output request with its raw JSON schema.
     * The provider seam owns route resolution and may attach the schema only to
     * the active model that explicitly supports strict JSON Schema. Implementations
     * that do not support native carriage retain the prompt-directive path.
     *
     * @param request chat request whose prompt contains the structured-output
     *                directive
     * @param agentRole agent role used for model routing
     * @param schemaJson raw JSON Schema for this request, or {@code null} when
     *                   native attachment is not applicable
     * @return chat response
     */
    default ChatResponse nextStep(ChatRequest request, String agentRole, String schemaJson) {
        return nextStep(request, agentRole);
    }

    /**
     * Executes a chat request for the given agent role, optionally bypassing lightweight-goal
     * model downgrading for exactly this call (memory-synthesis escalation).
     *
     * @param request                   The chat request.
     * @param agentRole                 The agent role ID used for per-persona model routing.
     * @param bypassLightweightRouting  When true, persona/group/global resolution applies even
     *                                  if the goal was classified as lightweight.
     * @return The chat response.
     * @throws com.amsha.llm.api.error.LlmProviderException if the provider throws an error.
     */
    default ChatResponse nextStep(ChatRequest request, String agentRole, boolean bypassLightweightRouting) {
        return nextStep(request, agentRole);
    }

    /**
     * Resolves the multimodal capability supported by the model for the given agent role.
     * Defaults to {@link com.amsha.llm.api.MultimodalCapability#TEXT_ONLY}.
     */
    default MultimodalCapability multimodalCapability(String agentRole) {
        return MultimodalCapability.TEXT_ONLY;
    }
}
