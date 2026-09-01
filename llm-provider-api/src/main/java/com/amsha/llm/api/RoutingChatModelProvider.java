package com.amsha.llm.api;

import dev.langchain4j.model.chat.ChatModel;

/**
 * SPI for LLM chat model providers for internal routing (intent classification, compaction, etc.).
 */
public interface RoutingChatModelProvider {

    /** Lower-case canonical provider identifier e.g. "groq", "ollama", "openai". */
    String providerId();

    /** Creates the {@link ChatModel} from the given config bundle. */
    ChatModel create(LlmModelConfig config);

    /**
     * True if this provider requires a non-blank API key to function.
     * Default: true.
     */
    default boolean requiresApiKey() {
        return true;
    }

    /**
     * Deliberate declaration that this provider/model family accepts a strict
     * JSON-Schema response format ({@code type: json_schema}), not merely generic
     * JSON mode. Resolution combines this advertisement with
     * {@link StructuredOutputCapabilities#resolvesToStrictSchema}.
     *
     * <p>Defaults to {@code false}; a factory must only override with evidence
     * that the upstream API enforces the declared schema at decoding time.</p>
     */
    default boolean supportsStrictJsonSchema() {
        return false;
    }
}
