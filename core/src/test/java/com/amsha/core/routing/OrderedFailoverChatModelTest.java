package com.amsha.core.routing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import com.amsha.core.agent.reasoning.error.LlmAuthenticationException;
import com.amsha.core.agent.reasoning.error.LlmServiceUnavailableException;

import org.junit.jupiter.api.Test;

class OrderedFailoverChatModelTest {

    private static ChatResponse response() {
        return ChatResponse.builder().aiMessage(AiMessage.from("ok")).build();
    }

    private static ChatRequest request() {
        return ChatRequest.builder().messages(List.of(UserMessage.from("hi"))).build();
    }

    /** ChatModel that throws a fixed exception on every call, counting invocations. */
    static final class ThrowingModel implements ChatModel {
        final AtomicInteger calls = new AtomicInteger();
        final RuntimeException toThrow;

        ThrowingModel(RuntimeException toThrow) {
            this.toThrow = toThrow;
        }

        @Override
        public ChatResponse chat(ChatRequest request) {
            calls.incrementAndGet();
            throw toThrow;
        }
    }

    /** ChatModel that succeeds on every call, counting invocations. */
    static final class OkModel implements ChatModel {
        final AtomicInteger calls = new AtomicInteger();

        @Override
        public ChatResponse chat(ChatRequest request) {
            calls.incrementAndGet();
            return response();
        }
    }

    @Test
    void stickyPrimary_usesFirstEntryUntilFailureThenFailover() {
        OkModel primary = new OkModel();
        OkModel secondary = new OkModel();
        OrderedFailoverChatModel model = new OrderedFailoverChatModel(
                "route:1",
                List.of(
                        new OrderedFailoverChatModel.Entry("p", primary),
                        new OrderedFailoverChatModel.Entry("s", secondary)),
                30);

        model.chat(request());
        model.chat(request());

        assertEquals(2, primary.calls.get(), "primary should serve both requests (sticky, no round-robin)");
        assertEquals(0, secondary.calls.get(), "secondary must not be used while primary healthy");
    }

    @Test
    void failover_advancesToSecondaryOnPrimaryUnavailable() {
        ThrowingModel primary = new ThrowingModel(new LlmServiceUnavailableException("timeout"));
        OkModel secondary = new OkModel();
        OrderedFailoverChatModel model = new OrderedFailoverChatModel(
                "route:2",
                List.of(
                        new OrderedFailoverChatModel.Entry("p", primary),
                        new OrderedFailoverChatModel.Entry("s", secondary)),
                30);

        ChatResponse r = model.chat(request());

        assertEquals(1, primary.calls.get());
        assertEquals(1, secondary.calls.get());
        assertEquals("ok", r.aiMessage().text());
    }

    @Test
    void permanentDisable_onAuthError_skipsProfileForever() {
        ThrowingModel primary = new ThrowingModel(new LlmAuthenticationException("bad key"));
        OkModel secondary = new OkModel();
        OrderedFailoverChatModel model = new OrderedFailoverChatModel(
                "route:3",
                List.of(
                        new OrderedFailoverChatModel.Entry("p", primary),
                        new OrderedFailoverChatModel.Entry("s", secondary)),
                30);

        // First call: primary auth-fails (permanently disabled), failover to secondary.
        model.chat(request());
        // Second call: primary must not even be attempted again (permanently disabled).
        model.chat(request());

        assertEquals(1, primary.calls.get(), "auth-failed profile must be permanently disabled");
        assertEquals(2, secondary.calls.get());
    }

    @Test
    void allProfilesExhausted_throws() {
        ThrowingModel a = new ThrowingModel(new LlmAuthenticationException("x"));
        ThrowingModel b = new ThrowingModel(new LlmAuthenticationException("y"));
        OrderedFailoverChatModel model = new OrderedFailoverChatModel(
                "route:4",
                List.of(new OrderedFailoverChatModel.Entry("a", a), new OrderedFailoverChatModel.Entry("b", b)),
                30);

        RuntimeException ex = assertThrows(RuntimeException.class, () -> model.chat(request()));
        assertTrue(ex.getMessage().contains("all profiles exhausted"));
        assertEquals(1, a.calls.get());
        assertEquals(1, b.calls.get());
    }

    @Test
    void noEntries_throwsInConstructor() {
        assertThrows(IllegalArgumentException.class, () -> new OrderedFailoverChatModel("route:5", List.of(), 30));
    }

    /**
     * ADR §208: health state is keyed by route/revision, not by provider/model. Two private
     * accounts using the same provider/model must not share cooldown, permanent-disable, or
     * rotation state. A permanent disable in one account's wrapper must not affect the other.
     */
    @Test
    void twoAccountsSameProviderModel_haveIndependentHealthState() {
        // Distinct underlying models per account (each account's own primary/secondary).
        ThrowingModel primaryA = new ThrowingModel(new LlmAuthenticationException("bad key"));
        OkModel secondaryA = new OkModel();
        ThrowingModel primaryB = new ThrowingModel(new LlmAuthenticationException("bad key"));
        OkModel secondaryB = new OkModel();

        OrderedFailoverChatModel accountA = new OrderedFailoverChatModel(
                "user-a:role",
                List.of(
                        new OrderedFailoverChatModel.Entry("p", primaryA),
                        new OrderedFailoverChatModel.Entry("s", secondaryA)),
                30);
        OrderedFailoverChatModel accountB = new OrderedFailoverChatModel(
                "user-b:role",
                List.of(
                        new OrderedFailoverChatModel.Entry("p", primaryB),
                        new OrderedFailoverChatModel.Entry("s", secondaryB)),
                30);

        // Account A auth-fails → its primary permanently disabled, falls to its secondary.
        accountA.chat(request());
        // Account B (independent health) must still attempt ITS primary, not inherit A's disable.
        accountB.chat(request());

        // Each account disabled only its own primary; neither crossed into the other's state.
        assertEquals(1, primaryA.calls.get(), "account A primary called exactly once then disabled");
        assertEquals(1, secondaryA.calls.get());
        assertEquals(1, primaryB.calls.get(), "account B primary independent of A's disable");
        assertEquals(1, secondaryB.calls.get());
    }
}
