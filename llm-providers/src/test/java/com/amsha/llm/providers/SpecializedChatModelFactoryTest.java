package com.amsha.llm.providers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

import dev.langchain4j.model.googleai.GoogleAiGeminiChatModel;
import dev.langchain4j.model.ollama.OllamaChatModel;

import java.time.Duration;

import com.amsha.llm.api.AgentLlmConfig;
import com.amsha.llm.providers.wrapper.AgyResponsesChatModel;
import com.amsha.llm.providers.wrapper.ClaudeCodeResponsesChatModel;
import com.amsha.llm.providers.wrapper.CodexResponsesChatModel;
import com.amsha.llm.providers.wrapper.FoundryResponsesChatModel;
import com.amsha.llm.providers.wrapper.KimchiResponsesChatModel;
import com.amsha.security.http.GovernedTransportService;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

import io.quarkus.arc.Arc;
import io.quarkus.arc.ArcContainer;
import io.quarkus.arc.InstanceHandle;

/**
 * Unit tests for {@link SpecializedChatModelFactory}.
 *
 * <h3>What is and is not asserted</h3>
 *
 * The factory's job is dispatch and default-resolution: pick the right builder for an adapter id and substitute a
 * default model name when the caller passes null/blank/legacy values. The dispatch half is directly observable and is
 * asserted by concrete type. The default-resolution half is <em>not</em> observable through the public surface — the
 * wrapper models keep the resolved name private and expose no accessor — so those tests assert successful
 * construction and leave the branch selection to coverage. Reaching into the private field with reflection purely to
 * strengthen the assertion was deliberately avoided.
 *
 * <h3>Why {@link Arc} is stubbed statically</h3>
 *
 * {@code resolveGovernedTransport()} catches a container failure and rethrows it as {@link IllegalStateException}, and
 * swallows {@link NoClassDefFoundError} for standalone (non-CDI) use. Neither path can be provoked from a plain unit
 * test without controlling {@link Arc#container()}, so the static is pinned with {@link Mockito#mockStatic}.
 */
class SpecializedChatModelFactoryTest {

    private MockedStatic<Arc> arc;

    @AfterEach
    void tearDown() {
        if (arc != null) {
            arc.close();
            arc = null;
        }
    }

    private static AgentLlmConfig config() {
        AgentLlmConfig config = Mockito.mock(AgentLlmConfig.class);
        when(config.maxTokens()).thenReturn(256);
        when(config.temperature()).thenReturn(0.2);
        when(config.timeout()).thenReturn(Duration.ofSeconds(30));
        when(config.logHttp()).thenReturn(false);
        when(config.maxRetries()).thenReturn(2);
        return config;
    }

    @SuppressWarnings("unchecked")
    private static InstanceHandle<GovernedTransportService> handle(boolean available, GovernedTransportService value) {
        InstanceHandle<GovernedTransportService> handle = Mockito.mock(InstanceHandle.class);
        when(handle.isAvailable()).thenReturn(available);
        when(handle.get()).thenReturn(value);
        return handle;
    }

    // ------------------------------------------------------- resolveGovernedTransport()

    @Test
    void resolveGovernedTransportReturnsNullWhenNoContainerIsRunning() {
        // A plain unit test has no CDI container, which is exactly the documented standalone fallback.
        assertNull(SpecializedChatModelFactory.resolveGovernedTransport());
    }

    @Test
    void resolveGovernedTransportReturnsTheContainerBeanWhenAvailable() {
        GovernedTransportService service = Mockito.mock(GovernedTransportService.class);
        ArcContainer container = Mockito.mock(ArcContainer.class);
        // Build the handle into a local first: calling a mock-creating helper *inside* when(...) is nested
        // stubbing, which Mockito rejects with UnfinishedStubbingException.
        InstanceHandle<GovernedTransportService> available = handle(true, service);
        when(container.instance(GovernedTransportService.class)).thenReturn(available);
        arc = Mockito.mockStatic(Arc.class);
        arc.when(Arc::container).thenReturn(container);

        assertEquals(service, SpecializedChatModelFactory.resolveGovernedTransport());
    }

    @Test
    void resolveGovernedTransportIgnoresAnUnavailableHandle() {
        ArcContainer container = Mockito.mock(ArcContainer.class);
        InstanceHandle<GovernedTransportService> unavailable = handle(false, null);
        when(container.instance(GovernedTransportService.class)).thenReturn(unavailable);
        arc = Mockito.mockStatic(Arc.class);
        arc.when(Arc::container).thenReturn(container);

        assertNull(SpecializedChatModelFactory.resolveGovernedTransport());
    }

    @Test
    void resolveGovernedTransportSurvivesAMissingArcClass() {
        arc = Mockito.mockStatic(Arc.class);
        arc.when(Arc::container).thenThrow(new NoClassDefFoundError("io/quarkus/arc/Arc"));

        assertNull(SpecializedChatModelFactory.resolveGovernedTransport());
    }

    @Test
    void resolveGovernedTransportSurfacesAContainerFailure() {
        arc = Mockito.mockStatic(Arc.class);
        arc.when(Arc::container).thenThrow(new IllegalStateException("container is broken"));

        IllegalStateException thrown =
                assertThrows(IllegalStateException.class, SpecializedChatModelFactory::resolveGovernedTransport);
        assertTrue(
                thrown.getMessage().contains("Failed to resolve GovernedTransportService from CDI container"),
                thrown.getMessage());
        assertNotNull(thrown.getCause());
    }

    // ----------------------------------------------------- createSpecializedAdapter()

    @Test
    void createSpecializedAdapterDispatchesEveryKnownAdapter() {
        AgentLlmConfig config = config();

        assertInstanceOf(
                GoogleAiGeminiChatModel.class,
                SpecializedChatModelFactory.createSpecializedAdapter("gemini", "https://x", "k", "m", config));
        assertInstanceOf(
                ClaudeCodeResponsesChatModel.class,
                SpecializedChatModelFactory.createSpecializedAdapter("claude-code", "https://x", "k", "m", config));
        assertInstanceOf(
                AgyResponsesChatModel.class,
                SpecializedChatModelFactory.createSpecializedAdapter("agy", "https://x", "k", "m", config));
        assertInstanceOf(
                AgyResponsesChatModel.class,
                SpecializedChatModelFactory.createSpecializedAdapter("antigravity", "https://x", "k", "m", config));
        assertInstanceOf(
                KimchiResponsesChatModel.class,
                SpecializedChatModelFactory.createSpecializedAdapter("kimchi", "https://x", "k", "m", config));
        assertInstanceOf(
                FoundryResponsesChatModel.class,
                SpecializedChatModelFactory.createSpecializedAdapter("foundry", "https://x", "k", "m", config));
        assertInstanceOf(
                FoundryResponsesChatModel.class,
                SpecializedChatModelFactory.createSpecializedAdapter("foundrylocal", "https://x", "k", "m", config));
        assertInstanceOf(
                OllamaChatModel.class,
                SpecializedChatModelFactory.createSpecializedAdapter("ollama", "https://x", "k", "m", config));
        assertInstanceOf(
                CodexResponsesChatModel.class,
                SpecializedChatModelFactory.createSpecializedAdapter(
                        "codex", "https://chatgpt.com/backend-api/codex", "k", "m", config));
        // The "anthropic" / "claude" ids route to createAnthropic, which needs a live CDI context — see
        // createAnthropicRoutesToTheAnthropicBuilder below.
    }

    @Test
    void createSpecializedAdapterReturnsNullForAnUnknownAdapter() {
        assertNull(SpecializedChatModelFactory.createSpecializedAdapter(
                "not-a-provider", "https://x", "k", "m", config()));
    }

    // -------------------------------------------------------------- per-adapter builders

    @Test
    void createAgySubstitutesTheDefaultModelForNullOrBlankOrLegacyNames() {
        AgentLlmConfig config = config();

        // The resolved name is private to the wrapper, so these assert successful construction only.
        assertInstanceOf(
                AgyResponsesChatModel.class, SpecializedChatModelFactory.createAgy("https://x", "t", null, config));
        assertInstanceOf(
                AgyResponsesChatModel.class, SpecializedChatModelFactory.createAgy("https://x", "t", "  ", config));
        assertInstanceOf(
                AgyResponsesChatModel.class,
                SpecializedChatModelFactory.createAgy("https://x", "t", "mistral-small", config));
        assertInstanceOf(
                AgyResponsesChatModel.class,
                SpecializedChatModelFactory.createAgy("https://x", "t", "gemini-3.1", config));
    }

    @Test
    void createAgyHonoursAnExplicitTransport() {
        GovernedTransportService transport = Mockito.mock(GovernedTransportService.class);
        assertInstanceOf(
                AgyResponsesChatModel.class,
                SpecializedChatModelFactory.createAgy("https://x", "t", "m", config(), transport));
    }

    @Test
    void createClaudeCodeSubstitutesTheDefaultModelForNullOrBlankOrLegacyNames() {
        AgentLlmConfig config = config();

        assertInstanceOf(
                ClaudeCodeResponsesChatModel.class,
                SpecializedChatModelFactory.createClaudeCode("https://x", "t", null, config));
        assertInstanceOf(
                ClaudeCodeResponsesChatModel.class,
                SpecializedChatModelFactory.createClaudeCode("https://x", "t", "  ", config));
        assertInstanceOf(
                ClaudeCodeResponsesChatModel.class,
                SpecializedChatModelFactory.createClaudeCode("https://x", "t", "mistral-small", config));
        assertInstanceOf(
                ClaudeCodeResponsesChatModel.class,
                SpecializedChatModelFactory.createClaudeCode("https://x", "t", "claude-sonnet-4-5", config));
    }

    @Test
    void createClaudeCodeHonoursAnExplicitTransport() {
        GovernedTransportService transport = Mockito.mock(GovernedTransportService.class);
        assertInstanceOf(
                ClaudeCodeResponsesChatModel.class,
                SpecializedChatModelFactory.createClaudeCode("https://x", "t", "m", config(), transport));
    }

    @Test
    void createKimchiSubstitutesTheDefaultModelForNullOrBlankOrLegacyNames() {
        AgentLlmConfig config = config();

        assertInstanceOf(
                KimchiResponsesChatModel.class,
                SpecializedChatModelFactory.createKimchi("https://x", "t", null, config));
        assertInstanceOf(
                KimchiResponsesChatModel.class,
                SpecializedChatModelFactory.createKimchi("https://x", "t", "  ", config));
        assertInstanceOf(
                KimchiResponsesChatModel.class,
                SpecializedChatModelFactory.createKimchi("https://x", "t", "mistral-small", config));
        assertInstanceOf(
                KimchiResponsesChatModel.class,
                SpecializedChatModelFactory.createKimchi("https://x", "t", "minimax-m3", config));
    }

    @Test
    void createFoundrySubstitutesTheDefaultModelForNullOrBlankOrLegacyNames() {
        AgentLlmConfig config = config();

        assertInstanceOf(
                FoundryResponsesChatModel.class, SpecializedChatModelFactory.createFoundry("https://x", null, config));
        assertInstanceOf(
                FoundryResponsesChatModel.class, SpecializedChatModelFactory.createFoundry("https://x", "  ", config));
        assertInstanceOf(
                FoundryResponsesChatModel.class,
                SpecializedChatModelFactory.createFoundry("https://x", "mistral-small", config));
        assertInstanceOf(
                FoundryResponsesChatModel.class,
                SpecializedChatModelFactory.createFoundry("https://x", "qwen3-14b", config));
    }

    @Test
    void createFoundryHonoursAnExplicitTransport() {
        GovernedTransportService transport = Mockito.mock(GovernedTransportService.class);
        assertInstanceOf(
                FoundryResponsesChatModel.class,
                SpecializedChatModelFactory.createFoundry("https://x", "m", config(), transport));
    }

    // ----------------------------------------------------------------------- gemini

    @Test
    void createGeminiAppliesTheBaseUrlOnlyWhenItIsPresent() {
        AgentLlmConfig config = config();

        assertInstanceOf(
                GoogleAiGeminiChatModel.class, SpecializedChatModelFactory.createGemini(null, "k", "m", config));
        assertInstanceOf(
                GoogleAiGeminiChatModel.class, SpecializedChatModelFactory.createGemini("  ", "k", "m", config));
        assertInstanceOf(
                GoogleAiGeminiChatModel.class,
                SpecializedChatModelFactory.createGemini("https://gemini.example", "k", "m", config));
    }

    @Test
    void createGeminiSubstitutesTheDefaultModel() {
        assertInstanceOf(
                GoogleAiGeminiChatModel.class, SpecializedChatModelFactory.createGemini(null, "k", null, config()));
        assertInstanceOf(
                GoogleAiGeminiChatModel.class, SpecializedChatModelFactory.createGemini(null, "k", "  ", config()));
        assertInstanceOf(
                GoogleAiGeminiChatModel.class,
                SpecializedChatModelFactory.createGemini(null, "k", "gemini-2.5-pro", config()));
    }

    // -------------------------------------------------------------------- anthropic

    /**
     * {@code AnthropicChatModel.builder().build()} constructs a Quarkus <em>Reactive REST Client</em>, which refuses to
     * initialise outside a running ArC context ("The Reactive REST Client needs to be built within the context of a
     * Quarkus application with a valid ArC (CDI) context running"). Gemini and Ollama take a different HTTP path and
     * therefore do build here, which is why only this adapter is exercised through the CDI boundary.
     *
     * <p>The assertions below deliberately do not match on the third-party message — only on the fact that routing to
     * this builder happens and cannot complete without CDI. Covering the happy path needs a {@code @QuarkusTest};
     * that is recorded as a follow-up in the coverage report rather than faked here.
     */
    @Test
    void createAnthropicRoutesToTheAnthropicBuilder() {
        AgentLlmConfig config = config();

        assertThrows(
                IllegalStateException.class,
                () -> SpecializedChatModelFactory.createAnthropic(null, "k", null, config));
        assertThrows(
                IllegalStateException.class,
                () -> SpecializedChatModelFactory.createAnthropic(null, "k", "  ", config));
        assertThrows(
                IllegalStateException.class,
                () -> SpecializedChatModelFactory.createAnthropic(null, "k", "claude-sonnet-4-5", config));
        // A non-blank baseUrl takes the extra builder.baseUrl(...) branch before build().
        assertThrows(
                IllegalStateException.class,
                () -> SpecializedChatModelFactory.createAnthropic("https://anthropic.example", "k", "m", config));
        assertThrows(
                IllegalStateException.class,
                () -> SpecializedChatModelFactory.createSpecializedAdapter("anthropic", "https://x", "k", "m", config));
        assertThrows(
                IllegalStateException.class,
                () -> SpecializedChatModelFactory.createSpecializedAdapter("claude", "https://x", "k", "m", config));
    }

    // ----------------------------------------------------------------------- ollama

    @Test
    void createOllamaSubstitutesTheDefaultBaseUrlAndModel() {
        AgentLlmConfig config = config();

        assertInstanceOf(OllamaChatModel.class, SpecializedChatModelFactory.createOllama(null, null, config));
        assertInstanceOf(OllamaChatModel.class, SpecializedChatModelFactory.createOllama("  ", "  ", config));
        assertInstanceOf(
                OllamaChatModel.class,
                SpecializedChatModelFactory.createOllama("http://ollama.example:11434", "qwen", config));
    }

    // ------------------------------------------------------------------------ codex

    @Test
    void createCodexFromConfigDelegatesToTheFullOverload() {
        assertInstanceOf(
                CodexResponsesChatModel.class,
                SpecializedChatModelFactory.createCodex(
                        "https://chatgpt.com/backend-api/codex", "t", "acct", "gpt-5", config()));
    }

    @Test
    void createCodexFromExplicitParametersDelegatesToTheTransportOverload() {
        assertInstanceOf(
                CodexResponsesChatModel.class,
                SpecializedChatModelFactory.createCodex(
                        "https://chatgpt.com/backend-api/codex",
                        "t",
                        "acct",
                        "gpt-5",
                        256,
                        Duration.ofSeconds(30),
                        2,
                        0.2,
                        false));
    }

    @Test
    void createCodexBuildsTheResponsesEndpointFromTheBaseUrl() {
        // A null/blank baseUrl, or one already pointing at the codex backend, takes the Responses wrapper.
        assertInstanceOf(
                CodexResponsesChatModel.class,
                SpecializedChatModelFactory.createCodex(
                        null, "t", "acct", "gpt-5", 256, Duration.ofSeconds(30), 2, 0.2, false));
        assertInstanceOf(
                CodexResponsesChatModel.class,
                SpecializedChatModelFactory.createCodex(
                        "  ", "t", "acct", "gpt-5", 256, Duration.ofSeconds(30), 2, 0.2, false));
        assertInstanceOf(
                CodexResponsesChatModel.class,
                SpecializedChatModelFactory.createCodex(
                        "https://chatgpt.com/backend-api/codex",
                        "t",
                        "acct",
                        "gpt-5",
                        256,
                        Duration.ofSeconds(30),
                        2,
                        0.2,
                        false));
        assertInstanceOf(
                CodexResponsesChatModel.class,
                SpecializedChatModelFactory.createCodex(
                        "https://chatgpt.com/backend-api/codex/responses",
                        "t",
                        "acct",
                        "gpt-5",
                        256,
                        Duration.ofSeconds(30),
                        2,
                        0.2,
                        false));
    }

    /**
     * A baseUrl that is neither blank nor a codex endpoint routes to {@code ChatModelFactory.createOpenAi} instead of
     * the Responses wrapper. That OpenAI builder also constructs a Reactive REST Client, so outside CDI it throws
     * rather than returning — which still proves the routing, because the other branch would have returned a
     * {@link CodexResponsesChatModel}.
     */
    @Test
    void createCodexFallsBackToTheOpenAiAdapterForACustomBaseUrl() {
        assertThrows(
                IllegalStateException.class,
                () -> SpecializedChatModelFactory.createCodex(
                        "https://my-proxy.example/v1",
                        "t",
                        "acct",
                        "gpt-5",
                        256,
                        Duration.ofSeconds(30),
                        2,
                        0.2,
                        false));
    }
}
