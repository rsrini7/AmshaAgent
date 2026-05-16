package com.arcclaw.core.agent.reasoning;

import java.time.Duration;

import jakarta.enterprise.context.ApplicationScoped;

import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * Shared CDI bean holding common LLM configuration properties
 * previously duplicated across every {@link AgentChatModelProvider} implementation.
 * <p>
 * Inject this instead of redeclaring the same 6 {@code @ConfigProperty} fields
 * in each provider factory.
 */
@ApplicationScoped
public class AgentLlmConfig {

    @ConfigProperty(name = "arcclaw.llm.max-output-tokens", defaultValue = "8192")
    int maxTokens;

    @ConfigProperty(name = "arcclaw.llm.temperature", defaultValue = "0.1")
    double temperature;

    @ConfigProperty(name = "arcclaw.llm.timeout", defaultValue = "PT60S")
    Duration timeout;

    @ConfigProperty(name = "arcclaw.llm.log-http", defaultValue = "false")
    boolean logHttp;

    @ConfigProperty(name = "arcclaw.llm.max-retries", defaultValue = "3")
    int maxRetries;

    @ConfigProperty(name = "arcclaw.llm.structured-output-mode", defaultValue = "NATIVE")
    StructuredOutputMode structuredOutputMode;

    public int maxTokens() { return maxTokens; }
    public double temperature() { return temperature; }
    public Duration timeout() { return timeout; }
    public boolean logHttp() { return logHttp; }
    public int maxRetries() { return maxRetries; }
    public StructuredOutputMode structuredOutputMode() { return structuredOutputMode; }
}
