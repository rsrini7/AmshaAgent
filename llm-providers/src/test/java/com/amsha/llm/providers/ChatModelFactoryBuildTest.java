package com.amsha.llm.providers;

import static org.junit.jupiter.api.Assertions.assertNotNull;

import jakarta.inject.Inject;

import java.time.Duration;
import java.util.Optional;

import com.amsha.llm.api.AgentLlmConfig;
import com.amsha.llm.api.LlmModelConfig;
import com.amsha.llm.api.StructuredOutputMode;
import com.amsha.security.http.GovernedTransportService;

import org.junit.jupiter.api.Test;

import io.quarkus.test.junit.QuarkusTest;

/**
 * Container-backed coverage for the parts of {@link ChatModelFactory} that actually build a model.
 *
 * <p>{@code ChatModelFactoryUnitTest} is a plain unit test and can therefore only reach the
 * fail-closed arms (a missing API key aborts before any builder runs). Every construction path —
 * {@code createFromConfig}, {@code createForAdapter} and the static delegates to
 * {@code SpecializedChatModelFactory} — needs a live CDI container, because the underlying
 * {@code QuarkusOpenAiClient} resolves an auth provider through {@code CDI.current()}.
 *
 * <p>{@code TestBeanProducer} supplies the {@code GovernedTransportService} mock, so no provider
 * performs real network I/O here.
 */
@QuarkusTest
class ChatModelFactoryBuildTest {

    private static final String BASE_URL = "http://localhost:11434/v1";
    private static final String MODEL = "test-model";

    @Inject
    AgentLlmConfig agentConfig;

    @Inject
    GovernedTransportService governedTransport;

    @Inject
    ChatModelFactory chatModelFactory;

    @Test
    void createFromConfigBuildsAModelFromACompleteConfig() {
        LlmModelConfig config = new LlmModelConfig(
                "openai",
                "gpt-4o",
                BASE_URL,
                Optional.of("test-key"),
                Optional.of(4096),
                Optional.of(Duration.ofSeconds(30)),
                Optional.of(3),
                StructuredOutputMode.NATIVE,
                false,
                true);

        assertNotNull(ChatModelFactory.createFromConfig(config));
    }

    @Test
    void createFromConfigAppliesDefaultsWhenOptionalParametersAreAbsent() {
        LlmModelConfig config = new LlmModelConfig(
                "openai",
                "gpt-4o",
                BASE_URL,
                Optional.of("test-key"),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                StructuredOutputMode.NATIVE,
                true);

        assertNotNull(ChatModelFactory.createFromConfig(config));
    }

    @Test
    void createForAdapterDispatchesToTheSpecializedFactory() {
        assertNotNull(
                chatModelFactory.createForAdapter("gemini", BASE_URL, "test-key", "gemini-2.5-flash", agentConfig));
    }

    @Test
    void createForAdapterFallsBackToTheOpenAiCompatiblePath() {
        // "openai" is not a specialized adapter, and a null id normalizes to "" — both fall through
        // to the plain OpenAI-compatible builder.
        assertNotNull(chatModelFactory.createForAdapter("openai", BASE_URL, "test-key", MODEL, agentConfig));
        assertNotNull(chatModelFactory.createForAdapter(null, BASE_URL, "test-key", MODEL, agentConfig));
    }

    @Test
    void staticDelegatesForwardToTheSpecializedFactory() {
        String token = "test-token";

        assertNotNull(ChatModelFactory.createAgy(BASE_URL, token, MODEL, agentConfig));
        assertNotNull(ChatModelFactory.createAgy(BASE_URL, token, MODEL, agentConfig, governedTransport));
        assertNotNull(ChatModelFactory.createClaudeCode(BASE_URL, token, MODEL, agentConfig));
        assertNotNull(ChatModelFactory.createClaudeCode(BASE_URL, token, MODEL, agentConfig, governedTransport));
        assertNotNull(ChatModelFactory.createCodex(BASE_URL, token, "account-1", MODEL, agentConfig));
        assertNotNull(ChatModelFactory.createCodex(
                BASE_URL, token, "account-1", MODEL, 4096, Duration.ofSeconds(30), 3, 0.0, false));
        assertNotNull(ChatModelFactory.createCodex(
                BASE_URL, token, "account-1", MODEL, 4096, Duration.ofSeconds(30), 3, 0.0, false, governedTransport));
        assertNotNull(ChatModelFactory.createGemini(BASE_URL, "test-key", MODEL, agentConfig));
        assertNotNull(ChatModelFactory.createAnthropic(BASE_URL, "test-key", MODEL, agentConfig));
        assertNotNull(ChatModelFactory.createOllama(BASE_URL, MODEL, agentConfig));
        assertNotNull(ChatModelFactory.createKimchi(BASE_URL, token, MODEL, agentConfig));
        assertNotNull(ChatModelFactory.createKimchi(BASE_URL, token, MODEL, agentConfig, governedTransport));
        assertNotNull(ChatModelFactory.createFoundry(BASE_URL, MODEL, agentConfig));
        assertNotNull(ChatModelFactory.createFoundry(BASE_URL, MODEL, agentConfig, governedTransport));
    }
}
