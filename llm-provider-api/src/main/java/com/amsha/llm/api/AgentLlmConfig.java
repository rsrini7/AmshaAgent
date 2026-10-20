package com.amsha.llm.api;

import jakarta.enterprise.context.ApplicationScoped;

import java.time.Duration;

import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * Shared CDI bean holding common LLM configuration properties
 * used across LLM provider implementations.
 */
@ApplicationScoped
public class AgentLlmConfig {

    @ConfigProperty(name = "amsha.llm.max-output-tokens", defaultValue = "8192")
    int maxTokens;

    @ConfigProperty(name = LlmConfigKeys.TEMPERATURE, defaultValue = "0.1")
    double temperature;

    @ConfigProperty(name = "amsha.llm.timeout", defaultValue = "PT60S")
    Duration timeout;

    @ConfigProperty(name = LlmConfigKeys.LOG_HTTP, defaultValue = "false")
    boolean logHttp;

    @ConfigProperty(name = "amsha.llm.max-retries", defaultValue = "3")
    int maxRetries;

    @ConfigProperty(name = "amsha.llm.structured-output-mode", defaultValue = "NATIVE")
    StructuredOutputMode structuredOutputMode;

    public int maxTokens() {
        return maxTokens;
    }

    public double temperature() {
        return temperature;
    }

    public Duration timeout() {
        return timeout;
    }

    public boolean logHttp() {
        return logHttp;
    }

    public int maxRetries() {
        return maxRetries;
    }

    public StructuredOutputMode structuredOutputMode() {
        return structuredOutputMode;
    }
}
