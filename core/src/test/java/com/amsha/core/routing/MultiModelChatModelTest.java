package com.amsha.core.routing;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.Capability;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;

import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import com.amsha.llm.api.StructuredSchemaRequests;
import com.amsha.llm.api.error.LlmAuthenticationException;
import com.amsha.llm.api.error.LlmContextOverflowException;
import com.amsha.llm.api.error.LlmRateLimitException;
import com.amsha.llm.api.error.LlmServiceUnavailableException;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class MultiModelChatModelTest {

    private static ChatRequest request() {
        return ChatRequest.builder().messages(List.of(UserMessage.from("hi"))).build();
    }

    private static ChatResponse response() {
        return ChatResponse.builder().aiMessage(AiMessage.from("ok")).build();
    }

    private static MultiModelChatModel.ModelEntry entry(String label, ChatModel model) {
        return new MultiModelChatModel.ModelEntry(label, model);
    }

    private static ChatModel capable(boolean declaresSchema) {
        ChatModel m = mock(ChatModel.class);
        when(m.supportedCapabilities())
                .thenReturn(declaresSchema ? Set.of(Capability.RESPONSE_FORMAT_JSON_SCHEMA) : Set.of());
        when(m.chat(any(ChatRequest.class)))
                .thenThrow(new LlmAuthenticationException("bad key", new RuntimeException()));
        return m;
    }

    /** Candidate whose declared capability set changes after its first read. */
    static final class MutatingCapabilityModel implements ChatModel {
        private final AtomicInteger reads = new AtomicInteger();

        @Override
        public ChatResponse chat(ChatRequest request) {
            return response();
        }

        @Override
        public Set<Capability> supportedCapabilities() {
            return reads.incrementAndGet() == 1 ? Set.of(Capability.RESPONSE_FORMAT_JSON_SCHEMA) : Set.of();
        }
    }

    @Test
    void capabilities_safeIntersectionOfCandidates() {
        var both = new MultiModelChatModel("test", List.of(entry("m1", capable(true)), entry("m2", capable(true))), 5);
        assertTrue(both.supportedCapabilities().contains(Capability.RESPONSE_FORMAT_JSON_SCHEMA));

        var mixed = new MultiModelChatModel(
                "test",
                List.of(
                        new MultiModelChatModel.ModelEntry("m1", capable(true), true),
                        new MultiModelChatModel.ModelEntry("m2", capable(false), false)),
                5);
        assertFalse(
                mixed.supportedCapabilities().contains(Capability.RESPONSE_FORMAT_JSON_SCHEMA),
                "one non-capable candidate must shrink the whole wrapper to non-native");
        assertFalse(mixed.supportsStrictJsonSchema(), "wrapper admission must use the safe intersection");
    }

    @Test
    void capabilities_stableAcrossPermanentDisable() {
        var wrapper =
                new MultiModelChatModel("test", List.of(entry("m1", capable(true)), entry("m2", capable(true))), 5);

        // Drive failures until every candidate is permanently disabled.
        for (int i = 0; i < 4; i++) {
            try {
                wrapper.chat(request());
            } catch (RuntimeException _) {
                // expected: candidates fail then all-disabled terminal state
            }
        }

        assertTrue(
                wrapper.supportedCapabilities().contains(Capability.RESPONSE_FORMAT_JSON_SCHEMA),
                "health transitions (cooldown/disable) must never change the composition-time capability set");
    }

    @Test
    void capabilities_stableDuringCooldownSkip() {
        ChatModel rateLimited = mock(ChatModel.class);
        when(rateLimited.supportedCapabilities()).thenReturn(Set.of(Capability.RESPONSE_FORMAT_JSON_SCHEMA));
        when(rateLimited.chat(any(ChatRequest.class)))
                .thenThrow(new LlmRateLimitException("rl", new RuntimeException()));

        ChatModel healthy = mock(ChatModel.class);
        when(healthy.supportedCapabilities()).thenReturn(Set.of(Capability.RESPONSE_FORMAT_JSON_SCHEMA));
        when(healthy.chat(any(ChatRequest.class))).thenReturn(response());

        var wrapper = new MultiModelChatModel("test", List.of(entry("rl", rateLimited), entry("ok", healthy)), 5);

        // First call: "rl" fails and enters cooldown; "ok" serves.
        wrapper.chat(request());
        Set<Capability> first = wrapper.supportedCapabilities();
        assertTrue(first.contains(Capability.RESPONSE_FORMAT_JSON_SCHEMA));

        // Second call: "rl" must be skipped in cooldown-skip state (no sleep, no re-read).
        wrapper.chat(request());

        assertSame(
                first,
                wrapper.supportedCapabilities(),
                "capability set instance must be reused across cooldown-skip serving");
        assertTrue(first.contains(Capability.RESPONSE_FORMAT_JSON_SCHEMA));
    }

    @Test
    void capabilities_snapshotNeverReReadFromCandidates() {
        var wrapper = new MultiModelChatModel(
                "test", List.of(entry("m1", capableStable()), entry("m2", new MutatingCapabilityModel())), 5);

        Set<Capability> atComposition = wrapper.supportedCapabilities();
        assertTrue(
                atComposition.contains(Capability.RESPONSE_FORMAT_JSON_SCHEMA),
                "composition-time read sees both candidates declaring");

        // m2's declared set is now empty; a lazy per-call recomputation would shrink the set.
        wrapper.chat(request());
        wrapper.chat(request());

        assertSame(
                atComposition,
                wrapper.supportedCapabilities(),
                "wrapper must serve the composition-time snapshot, never re-read candidates");
        assertTrue(
                atComposition.contains(Capability.RESPONSE_FORMAT_JSON_SCHEMA),
                "snapshot content unchanged despite candidate mutation after composition");
    }

    private static ChatRequest schemaBearing() {
        return StructuredSchemaRequests.withNativeJsonSchema(
                request(), "op", "{\"type\":\"object\",\"properties\":{\"a\":{\"type\":\"string\"}}}");
    }

    @Test
    void schemaBearingRequest_forwardedToAdvertisedCandidate_strippedForOther() {
        ChatRequest schemaRequest = schemaBearing();
        assertTrue(StructuredSchemaRequests.hasNativeJsonSchema(schemaRequest));

        ChatModel flagged = mock(ChatModel.class);
        when(flagged.chat(any(ChatRequest.class))).thenReturn(response());
        ChatModel unflagged = mock(ChatModel.class);
        when(unflagged.chat(any(ChatRequest.class))).thenReturn(response());

        var wrapper = new MultiModelChatModel(
                "test",
                List.of(new MultiModelChatModel.ModelEntry("adv", flagged, true), entry("plain", unflagged)),
                5);

        wrapper.chat(schemaRequest);
        wrapper.chat(schemaRequest);

        ArgumentCaptor<ChatRequest> captor = ArgumentCaptor.forClass(ChatRequest.class);
        verify(flagged).chat(captor.capture());
        assertTrue(
                StructuredSchemaRequests.hasNativeJsonSchema(captor.getValue()),
                "advertised candidate receives the schema verbatim");

        verify(unflagged).chat(captor.capture());
        assertFalse(
                StructuredSchemaRequests.hasNativeJsonSchema(captor.getValue()),
                "unflagged candidate receives the rebuilt request without the native parameter");
    }

    @Test
    void schemaRejectedByFirstCandidate_downgradedForNext() {
        // Round-robin serves entries[1] first: the advertised primary fails with a
        // 503; the advertised secondary must receive the downgraded request, never
        // a verbatim replay of the failed schema request.
        ChatRequest schemaRequest = schemaBearing();

        ChatModel failingPrimary = mock(ChatModel.class);
        when(failingPrimary.chat(any(ChatRequest.class)))
                .thenThrow(new LlmServiceUnavailableException("down", new RuntimeException()));
        ChatModel secondary = mock(ChatModel.class);
        when(secondary.chat(any(ChatRequest.class))).thenReturn(response());

        var wrapper = new MultiModelChatModel(
                "test",
                List.of(
                        new MultiModelChatModel.ModelEntry("sec", secondary, true),
                        new MultiModelChatModel.ModelEntry("pri", failingPrimary, true)),
                5);

        wrapper.chat(schemaRequest);

        ArgumentCaptor<ChatRequest> captor = ArgumentCaptor.forClass(ChatRequest.class);
        verify(secondary).chat(captor.capture());
        assertFalse(
                StructuredSchemaRequests.hasNativeJsonSchema(captor.getValue()),
                "after a candidate failure, the next candidate must receive the stripped request");
    }

    private static ChatModel capableStable() {
        ChatModel m = mock(ChatModel.class);
        when(m.supportedCapabilities()).thenReturn(Set.of(Capability.RESPONSE_FORMAT_JSON_SCHEMA));
        when(m.chat(any(ChatRequest.class))).thenReturn(response());
        return m;
    }

    @Test
    void singleEntry_alwaysServesFromIt() {
        ChatModel m = mock(ChatModel.class);
        when(m.chat(any(ChatRequest.class))).thenReturn(response());

        MultiModelChatModel multi = new MultiModelChatModel("test", List.of(entry("m1", m)), 5);

        multi.chat(request());
        multi.chat(request());

        verify(m, times(2)).chat(any(ChatRequest.class));
    }

    @Test
    void twoEntries_roundRobin() {
        ChatModel m1 = mock(ChatModel.class);
        ChatModel m2 = mock(ChatModel.class);
        when(m1.chat(any(ChatRequest.class))).thenReturn(response());
        when(m2.chat(any(ChatRequest.class))).thenReturn(response());

        MultiModelChatModel multi = new MultiModelChatModel("test", List.of(entry("m1", m1), entry("m2", m2)), 5);

        multi.chat(request());
        multi.chat(request());

        verify(m1, times(1)).chat(any(ChatRequest.class));
        verify(m2, times(1)).chat(any(ChatRequest.class));
    }

    @Test
    void rateLimited_rotatesToNextModel() {
        ChatModel m1 = mock(ChatModel.class);
        ChatModel m2 = mock(ChatModel.class);
        when(m1.chat(any(ChatRequest.class)))
                .thenThrow(new LlmRateLimitException("rate limited", new RuntimeException()));
        when(m2.chat(any(ChatRequest.class))).thenReturn(response());

        MultiModelChatModel multi = new MultiModelChatModel("test", List.of(entry("m1", m1), entry("m2", m2)), 5);

        ChatResponse result = multi.chat(request());
        assertNotNull(result);

        // At least one model served — round-robin may start on either
        verify(m2, atLeastOnce()).chat(any(ChatRequest.class));
    }

    @Test
    void authError_permanentlyDisables() {
        ChatModel m1 = mock(ChatModel.class);
        ChatModel m2 = mock(ChatModel.class);
        when(m1.chat(any(ChatRequest.class)))
                .thenThrow(new LlmAuthenticationException("invalid key", new RuntimeException()));
        when(m2.chat(any(ChatRequest.class))).thenReturn(response());

        MultiModelChatModel multi = new MultiModelChatModel("test", List.of(entry("m1", m1), entry("m2", m2)), 5);

        // First call: m1 disabled, m2 serves
        multi.chat(request());
        verify(m2, times(1)).chat(any(ChatRequest.class));

        // Second call: m1 still disabled, m2 serves again
        multi.chat(request());
        verify(m2, times(2)).chat(any(ChatRequest.class));

        // m1 never called again
        verify(m1, times(1)).chat(any(ChatRequest.class));
    }

    @Test
    void allDisabled_throws() {
        ChatModel m1 = mock(ChatModel.class);
        ChatModel m2 = mock(ChatModel.class);
        when(m1.chat(any(ChatRequest.class)))
                .thenThrow(new LlmAuthenticationException("invalid", new RuntimeException()));
        when(m2.chat(any(ChatRequest.class)))
                .thenThrow(new LlmAuthenticationException("invalid", new RuntimeException()));

        MultiModelChatModel multi = new MultiModelChatModel("test", List.of(entry("m1", m1), entry("m2", m2)), 5);

        assertThrows(RuntimeException.class, () -> multi.chat(request()));
    }

    @Test
    void transientFailure_disablesAfterThreshold() {
        ChatModel m1 = mock(ChatModel.class);
        ChatModel m2 = mock(ChatModel.class);
        when(m1.chat(any(ChatRequest.class)))
                .thenThrow(new LlmServiceUnavailableException("503", new RuntimeException()));
        when(m2.chat(any(ChatRequest.class))).thenReturn(response());

        MultiModelChatModel multi = new MultiModelChatModel("test", List.of(entry("m1", m1), entry("m2", m2)), 5);

        // After enough calls, m1 gets permanently disabled and m2 handles all remaining calls
        for (int i = 0; i < 10; i++) {
            multi.chat(request());
        }

        // m1 was called at most 3 times (then permanently disabled)
        verify(m1, atMost(3)).chat(any(ChatRequest.class));
        // m2 handled the rest
        verify(m2, atLeast(7)).chat(any(ChatRequest.class));
    }

    @Test
    void constructor_emptyEntries_throws() {
        assertThrows(IllegalArgumentException.class, () -> new MultiModelChatModel("test", List.of(), 5));
    }

    @Test
    void constructor_nullEntries_throws() {
        assertThrows(IllegalArgumentException.class, () -> new MultiModelChatModel("test", null, 5));
    }

    @Test
    void rateLimited_dailyQuotaExhausted_disablesPermanently() {
        ChatModel m = mock(ChatModel.class);
        when(m.chat(any(ChatRequest.class)))
                .thenThrow(new LlmRateLimitException("You have reached your limit per day.", new RuntimeException()));
        MultiModelChatModel multi =
                new MultiModelChatModel("test", List.of(new MultiModelChatModel.ModelEntry("m", m)), 5);

        assertThrows(RuntimeException.class, () -> multi.chat(request()));
        assertThrows(RuntimeException.class, () -> multi.chat(request()));
    }

    @Test
    void rateLimited_requestTooLarge_marksTpmBlocked() {
        ChatModel m1 = mock(ChatModel.class);
        when(m1.chat(any(ChatRequest.class)))
                .thenThrow(new LlmRateLimitException("Request too large for model limits", new RuntimeException()));
        MultiModelChatModel multi =
                new MultiModelChatModel("test", List.of(new MultiModelChatModel.ModelEntry("m1", m1)), 5);

        RuntimeException e = assertThrows(RuntimeException.class, () -> multi.chat(request()));
        assertTrue(e.getMessage().contains("all models blocked — request too large for TPM limits"));
    }

    @Test
    void serviceUnavailable_toolUseFailed_disablesPermanently() {
        ChatModel m = mock(ChatModel.class);
        when(m.chat(any(ChatRequest.class)))
                .thenThrow(new LlmServiceUnavailableException("tool_use_failed", new RuntimeException()));
        MultiModelChatModel multi =
                new MultiModelChatModel("test", List.of(new MultiModelChatModel.ModelEntry("m", m)), 5);

        assertThrows(RuntimeException.class, () -> multi.chat(request()));
    }

    @Test
    void unknownFailure_coolsDownAndRotates() {
        ChatModel m1 = mock(ChatModel.class);
        when(m1.chat(any(ChatRequest.class))).thenReturn(response());
        ChatModel m2 = mock(ChatModel.class);
        when(m2.chat(any(ChatRequest.class)))
                .thenThrow(new LlmContextOverflowException("unexpected overflow error", new RuntimeException()));

        MultiModelChatModel multi = new MultiModelChatModel(
                "test",
                List.of(new MultiModelChatModel.ModelEntry("m1", m1), new MultiModelChatModel.ModelEntry("m2", m2)),
                5);

        assertNotNull(multi.chat(request()));
        verify(m2).chat(any(ChatRequest.class));
        verify(m1).chat(any(ChatRequest.class));
    }
}
