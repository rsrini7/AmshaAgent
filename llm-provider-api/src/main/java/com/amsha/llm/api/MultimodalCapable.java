package com.amsha.llm.api;

/**
 * Deliberate admission marker for a routed chat model or provider that advertises
 * model-specific multimodal capabilities (images, audio, PDF documents).
 */
public interface MultimodalCapable {

    /**
     * Returns the multimodal capabilities supported by this model instance.
     * Default returns {@link MultimodalCapability#TEXT_ONLY}.
     */
    default MultimodalCapability multimodalCapability() {
        return MultimodalCapability.TEXT_ONLY;
    }
}
