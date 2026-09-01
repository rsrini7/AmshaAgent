package com.amsha.llm.api;

/**
 * Deliberate admission marker for a routed chat model that can consume a
 * request-scoped strict JSON-Schema response format.
 *
 * <p>LangChain4j's {@code ChatModel#supportedCapabilities()} is useful
 * diagnostic information, but it can be inferred from builder configuration.
 * This marker is attached only by an Amsha provider factory after the provider
 * contract and proxy gates have passed.</p>
 */
public interface StrictJsonSchemaCapable {

    /**
     * Returns whether this model may receive a native strict JSON-Schema
     * response format for a toolless structured-output request.
     */
    boolean supportsStrictJsonSchema();
}
