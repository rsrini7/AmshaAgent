package com.amsha.core.routing;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;

import com.amsha.llm.api.StructuredOutputMode;

import org.junit.jupiter.api.Test;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.quarkus.runtime.LaunchMode;

/**
 * Branch/edge coverage for {@link InfiniteLoopCompactionChatModelProvider}: the slot-rotation build
 * path, the per-provider config ladders (api-key / account-id / structured-output-mode), the
 * {@code {account_id}} substitution, and the test-profile dummy-slot fallback.
 *
 * <p>Complements {@link InfiniteLoopProvidersCoverageTest}, which owns the "no providers
 * configured" throw and the pure {@code sanitizeAccountId} / {@code normalizeBaseUrl} helpers.
 *
 * <p>The provider reads config through {@code ConfigProvider.getConfig()} — a static call with no
 * injection point — but the thread context classloader's SmallRye config reads system properties
 * live, so {@link #withProperties} is the seam. The test-profile branch is driven by
 * {@link LaunchMode#set}, which is a bare static write.
 */
class InfiniteLoopCompactionProviderBranchTest {

    /** Cloudflare-style URL so the {@code {account_id}} substitution path is exercised. */
    private static final String CLOUDFLARE_TEMPLATE =
            "https://api.cloudflare.com/client/v4/accounts/{account_id}/ai/v1";

    private static final String CLOUDFLARE_RESOLVED = "https://api.cloudflare.com/client/v4/accounts/acc-9/ai/v1";

    private static InfiniteLoopCompactionChatModelProvider provider(String loopProviders, LlmModelFactory factory) {
        InfiniteLoopCompactionChatModelProvider provider = new InfiniteLoopCompactionChatModelProvider();
        provider.loopProviders = loopProviders;
        provider.cooldownBaseSeconds = 10;
        provider.cooldownMaxSeconds = 60;
        provider.cooldownTransientSeconds = 2;
        provider.maxTokens = 512;
        provider.modelFactory = factory;
        provider.meterRegistry = new SimpleMeterRegistry();
        return provider;
    }

    private static LlmModelFactory factoryReturning(ChatModel model) {
        LlmModelFactory factory = mock(LlmModelFactory.class);
        when(factory.createModel(
                        anyString(),
                        anyString(),
                        anyString(),
                        anyString(),
                        any(),
                        any(),
                        any(),
                        anyString(),
                        anyString(),
                        any(),
                        anyBoolean()))
                .thenReturn(model);
        return factory;
    }

    @Test
    void compactionModelBuildsASlotFromThePerProviderConfigLadder() throws Exception {
        ChatModel slotModel = mock(ChatModel.class);
        LlmModelFactory factory = factoryReturning(slotModel);

        InfiniteLoopChatModel loop = withProperties(
                Map.of(
                        "amsha.compaction.groq.base-url", CLOUDFLARE_TEMPLATE,
                        "amsha.compaction.groq.model", "llama-3.3-70b",
                        "amsha.compaction.groq.max-retries", "5",
                        "AMSHA_GROQ_API_KEY", "sk-groq",
                        "AMSHA_GROQ_ACCOUNT_ID", "acc-9"),
                () -> provider("groq", factory).compactionModel());

        assertNotNull(loop);
        // The system-property fallbacks must be honoured, and the account id must be substituted
        // into the templated base URL before it reaches the factory.
        verify(factory)
                .createModel(
                        eq("groq"),
                        eq(CLOUDFLARE_RESOLVED),
                        eq("sk-groq"),
                        eq("llama-3.3-70b"),
                        eq(512),
                        eq(Duration.ofSeconds(10)),
                        eq(5),
                        eq(CLOUDFLARE_RESOLVED),
                        eq("llama-3.3-70b"),
                        eq(StructuredOutputMode.PROMPT_DIRECTIVE),
                        eq(true));
    }

    @Test
    void compactionModelFallsThroughToBlankApiKeyAndDefaultStructuredMode() throws Exception {
        ChatModel slotModel = mock(ChatModel.class);
        LlmModelFactory factory = factoryReturning(slotModel);

        // A synthetic provider name guarantees no ambient AMSHA_*_API_KEY / _ACCOUNT_ID env var can
        // satisfy the fallback chain, so the terminal .orElse("") arms are genuinely exercised.
        withProperties(
                Map.of(
                        "amsha.compaction.synthetic-provider.base-url", "https://api.example.com/v1",
                        "amsha.compaction.synthetic-provider.model", "some-model",
                        "amsha.compaction.synthetic-provider.structured-output-mode", "bogus-mode"),
                () -> provider("synthetic-provider", factory).compactionModel());

        // An unrecognised mode must degrade to the prompt-directive default rather than failing.
        verify(factory)
                .createModel(
                        eq("synthetic-provider"),
                        eq("https://api.example.com/v1"),
                        eq(""),
                        eq("some-model"),
                        eq(512),
                        eq(Duration.ofSeconds(10)),
                        eq(3),
                        eq("https://api.example.com/v1"),
                        eq("some-model"),
                        eq(StructuredOutputMode.PROMPT_DIRECTIVE),
                        eq(true));
    }

    @Test
    void compactionModelServesADummySlotInTheTestProfile() throws Exception {
        LlmModelFactory factory = factoryReturning(mock(ChatModel.class));

        InfiniteLoopChatModel loop = withLaunchMode(
                LaunchMode.TEST,
                () -> withProperties(
                        Map.of("amsha.test.force-error", "false"),
                        () -> provider("synthetic-missing", factory).compactionModel()));

        ChatResponse response = loop.chat(ChatRequest.builder()
                .messages(List.of(UserMessage.from("compact this")))
                .build());

        assertEquals(
                "Dummy compaction response",
                response.aiMessage().text(),
                "the test-profile fallback must answer instead of throwing, so unrelated suites can boot");
    }

    @Test
    void compactionModelThrowsInTheTestProfileWhenTheDummyIsForcedOff() throws Exception {
        LlmModelFactory factory = factoryReturning(mock(ChatModel.class));

        IllegalStateException thrown = assertThrows(IllegalStateException.class, () -> {
            withLaunchMode(
                    LaunchMode.TEST,
                    () -> withProperties(
                            Map.of("amsha.test.force-error", "true"),
                            () -> provider("synthetic-missing", factory).compactionModel()));
        });

        assertTrue(
                thrown.getMessage().contains("No compaction providers configured"),
                "the escape hatch must still name the config key so the misconfiguration is actionable");
    }

    @Test
    void compactionModelThrowsOutsideTheTestProfile() throws Exception {
        LlmModelFactory factory = factoryReturning(mock(ChatModel.class));

        IllegalStateException thrown = assertThrows(
                IllegalStateException.class,
                () -> withLaunchMode(
                        LaunchMode.NORMAL,
                        () -> provider("synthetic-missing", factory).compactionModel()));

        assertTrue(thrown.getMessage().contains("synthetic-missing"));
    }

    @Test
    void compactionModelCachesTheFirstSuccessfulInstance() throws Exception {
        LlmModelFactory factory = factoryReturning(mock(ChatModel.class));

        InfiniteLoopChatModel[] calls = withProperties(
                Map.of(
                        "amsha.compaction.groq.base-url", "https://api.groq.com/openai/v1",
                        "amsha.compaction.groq.model", "llama-3.3-70b"),
                () -> {
                    InfiniteLoopCompactionChatModelProvider provider = provider("groq", factory);
                    return new InfiniteLoopChatModel[] {provider.compactionModel(), provider.compactionModel()};
                });

        assertSame(
                calls[0],
                calls[1],
                "a @Produces @ApplicationScoped model must be published once, not rebuilt per injection");
    }

    @Test
    void compactionModelIgnoresEmptyProviderEntries() throws Exception {
        LlmModelFactory factory = factoryReturning(mock(ChatModel.class));

        // ",," splits into blank names, all of which must be skipped without ever reaching the factory.
        IllegalStateException thrown = assertThrows(
                IllegalStateException.class,
                () -> withLaunchMode(
                        LaunchMode.NORMAL, () -> provider(",,", factory).compactionModel()));

        assertTrue(thrown.getMessage().contains("No compaction providers configured"));
        verifyNoInteractions(factory);
    }

    @Test
    void compactionModelHonoursAnExplicitPerProviderApiKeyAndAccountId() throws Exception {
        ChatModel slotModel = mock(ChatModel.class);
        LlmModelFactory factory = factoryReturning(slotModel);

        withProperties(
                Map.of(
                        "amsha.compaction.groq.base-url", "https://api.groq.com/openai/v1",
                        "amsha.compaction.groq.model", "llama-3.3-70b",
                        "amsha.compaction.groq.api-key", "sk-explicit",
                        "amsha.compaction.groq.account-id", "acc-explicit"),
                () -> provider("groq", factory).compactionModel());

        // An explicitly configured value must win outright, without consulting the AMSHA_* fallbacks.
        verify(factory)
                .createModel(
                        eq("groq"),
                        eq("https://api.groq.com/openai/v1"),
                        eq("sk-explicit"),
                        eq("llama-3.3-70b"),
                        eq(512),
                        eq(Duration.ofSeconds(10)),
                        eq(3),
                        eq("https://api.groq.com/openai/v1"),
                        eq("llama-3.3-70b"),
                        eq(StructuredOutputMode.PROMPT_DIRECTIVE),
                        eq(true));
    }

    @Test
    void compactionModelFailsClosedWhenATemplatedBaseUrlHasNoAccountId() throws Exception {
        LlmModelFactory factory = factoryReturning(mock(ChatModel.class));

        // With no account id to substitute, normalizeBaseUrl rejects the residual '}' rather than
        // handing a malformed URL to the provider — the slot is dropped, so the loop fails closed.
        IllegalStateException thrown = assertThrows(
                IllegalStateException.class,
                () -> withLaunchMode(
                        LaunchMode.NORMAL,
                        () -> withProperties(
                                Map.of(
                                        "amsha.compaction.synthetic-templated.base-url",
                                                "https://api.example.com/accounts/{account_id}/v1",
                                        "amsha.compaction.synthetic-templated.model", "some-model"),
                                () -> provider("synthetic-templated", factory).compactionModel())));

        assertTrue(thrown.getMessage().contains("No compaction providers configured"));
        verifyNoInteractions(factory);
    }

    @Test
    void compactionModelInitialisesExactlyOnceUnderConcurrentFirstUse() throws Exception {
        ChatModel slotModel = mock(ChatModel.class);
        LlmModelFactory factory = mock(LlmModelFactory.class);
        CountDownLatch firstCallInside = new CountDownLatch(1);
        CountDownLatch secondCallBlocked = new CountDownLatch(1);
        when(factory.createModel(
                        anyString(),
                        anyString(),
                        anyString(),
                        anyString(),
                        any(),
                        any(),
                        any(),
                        anyString(),
                        anyString(),
                        any(),
                        anyBoolean()))
                .thenAnswer(invocation -> {
                    firstCallInside.countDown();
                    secondCallBlocked.await(2, TimeUnit.SECONDS);
                    return slotModel;
                });

        withProperties(
                Map.of(
                        "amsha.compaction.groq.base-url", "https://api.groq.com/openai/v1",
                        "amsha.compaction.groq.model", "llama-3.3-70b"),
                () -> {
                    InfiniteLoopCompactionChatModelProvider provider = provider("groq", factory);
                    InfiniteLoopChatModel[] results = new InfiniteLoopChatModel[2];

                    Thread first = new Thread(() -> results[0] = provider.compactionModel(), "compaction-first");
                    first.start();
                    assertTrue(firstCallInside.await(5, TimeUnit.SECONDS), "the first caller should be building");

                    // The second caller reads a still-null instance, so it parks on the monitor and
                    // must observe the published instance on the double-checked re-read.
                    Thread second = new Thread(() -> results[1] = provider.compactionModel(), "compaction-second");
                    second.start();
                    long limit = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                    while (second.getState() != Thread.State.BLOCKED && System.nanoTime() < limit) {
                        LockSupport.parkNanos(200_000L);
                    }
                    assertEquals(
                            Thread.State.BLOCKED,
                            second.getState(),
                            "the second caller should be waiting on the initialisation monitor");
                    secondCallBlocked.countDown();

                    first.join(5_000L);
                    second.join(5_000L);
                    assertSame(results[0], results[1]);
                    return null;
                });

        verify(factory, times(1))
                .createModel(
                        anyString(),
                        anyString(),
                        anyString(),
                        anyString(),
                        any(),
                        any(),
                        any(),
                        anyString(),
                        anyString(),
                        any(),
                        anyBoolean());
    }

    // ── Helpers ─────────────────────────────────────────────────────────────

    /** Installs the keys as system properties and restores the previous values afterwards. */
    private static <T> T withProperties(Map<String, String> properties, Callable<T> action) throws Exception {
        Map<String, String> previous = new HashMap<>();
        properties.keySet().forEach(key -> previous.put(key, System.getProperty(key)));
        try {
            properties.forEach(System::setProperty);
            return action.call();
        } finally {
            previous.forEach((key, value) -> {
                if (value == null) {
                    System.clearProperty(key);
                } else {
                    System.setProperty(key, value);
                }
            });
        }
    }

    /** {@link LaunchMode#set} is a bare static write, so the previous value must be restored. */
    private static <T> T withLaunchMode(LaunchMode mode, Callable<T> action) throws Exception {
        LaunchMode previous = LaunchMode.current();
        try {
            LaunchMode.set(mode);
            return action.call();
        } finally {
            LaunchMode.set(previous);
        }
    }
}
