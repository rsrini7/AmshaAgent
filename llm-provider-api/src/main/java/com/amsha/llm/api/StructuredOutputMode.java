package com.amsha.llm.api;

/**
 * Defines the strategy for ensuring the LLM follows the requested JSON schema.
 */
public enum StructuredOutputMode {
    /**
     * The model is highly reliable and follows the schema natively (e.g., GPT-4, Gemini 1.5+).
     * No extra prompt directives are added.
     */
    NATIVE,

    /**
     * The model occasionally slips into prose. A strong "JSON ONLY" directive is injected
     * into the system prompt (e.g., Llama-3-70B, Llama-4-Scout).
     */
    PROMPT_DIRECTIVE,

    /**
     * The model is unreliable for structured output. The system relies primarily on
     * iterative correction and schema-validation retries.
     */
    LENIENT
}
