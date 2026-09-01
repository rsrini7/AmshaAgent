package com.amsha.core.routing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.langchain4j.data.image.Image;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ImageContent;
import dev.langchain4j.data.message.TextContent;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.Capability;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;

import java.util.Base64;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import com.amsha.channel.api.AttachmentType;
import com.amsha.llm.api.MultimodalCapabilities;
import com.amsha.llm.api.MultimodalCapability;
import com.amsha.llm.api.StructuredSchemaRequests;
import com.amsha.llm.api.error.LlmAuthenticationException;
import com.amsha.llm.api.error.LlmMalformedResponseException;
import com.amsha.llm.api.error.LlmModelExhaustedException;
import com.amsha.llm.api.error.LlmServiceUnavailableException;

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
        ChatRequest last;

        @Override
        public ChatResponse chat(ChatRequest request) {
            calls.incrementAndGet();
            last = request;
            return response();
        }
    }

    /** ChatModel with fixed capabilities; optionally fails every call with the given exception. */
    static final class CapableModel implements ChatModel {
        private final Set<Capability> capabilities;
        private final RuntimeException failure;

        CapableModel(Capability... capabilities) {
            this(null, capabilities);
        }

        CapableModel(RuntimeException failure, Capability... capabilities) {
            this.capabilities = Set.of(capabilities);
            this.failure = failure;
        }

        @Override
        public ChatResponse chat(ChatRequest request) {
            if (failure != null) {
                throw failure;
            }
            return response();
        }

        @Override
        public Set<Capability> supportedCapabilities() {
            return capabilities;
        }
    }

    @Test
    void multimodalRequestSkipsCandidateWithoutMatchingCapability() {
        OkModel textModel = new OkModel();
        OkModel visionModel = new OkModel();
        MultimodalCapability visionCapability =
                new MultimodalCapability(Set.of(AttachmentType.IMAGE), Set.of("image/png"), 10, 1, true, false);
        ChatRequest imageRequest = ChatRequest.builder()
                .messages(List.of(UserMessage.from(
                        TextContent.from("describe"),
                        ImageContent.from(Image.builder()
                                .mimeType("image/png")
                                .base64Data(Base64.getEncoder().encodeToString(new byte[] {1, 2, 3}))
                                .build()))))
                .build();

        OrderedFailoverChatModel failover = new OrderedFailoverChatModel(
                "route-image",
                List.of(
                        new OrderedFailoverChatModel.Entry("text", textModel),
                        new OrderedFailoverChatModel.Entry("vision", visionModel, false, visionCapability)),
                5);

        failover.chat(imageRequest);

        assertEquals(0, textModel.calls.get());
        assertEquals(1, visionModel.calls.get());
    }

    @Test
    void capabilities_safeIntersectionOfCandidates() {
        var both = new OrderedFailoverChatModel(
                "route",
                List.of(
                        new OrderedFailoverChatModel.Entry(
                                "a", new CapableModel(Capability.RESPONSE_FORMAT_JSON_SCHEMA)),
                        new OrderedFailoverChatModel.Entry(
                                "b", new CapableModel(Capability.RESPONSE_FORMAT_JSON_SCHEMA))),
                5);
        assertTrue(both.supportedCapabilities().contains(Capability.RESPONSE_FORMAT_JSON_SCHEMA));

        var mixed = new OrderedFailoverChatModel(
                "route",
                List.of(
                        new OrderedFailoverChatModel.Entry(
                                "a", new CapableModel(Capability.RESPONSE_FORMAT_JSON_SCHEMA), true),
                        new OrderedFailoverChatModel.Entry("b", new CapableModel(), false)),
                5);
        assertFalse(
                mixed.supportedCapabilities().contains(Capability.RESPONSE_FORMAT_JSON_SCHEMA),
                "one non-capable candidate must shrink the whole wrapper to non-native");
        assertFalse(mixed.supportsStrictJsonSchema(), "wrapper admission must use the safe intersection");
    }

    @Test
    void capabilities_neverUpgradedWhenFailoverAdvances() {
        var wrapper = new OrderedFailoverChatModel(
                "route",
                List.of(
                        new OrderedFailoverChatModel.Entry(
                                "primary",
                                new CapableModel(
                                        new LlmServiceUnavailableException("down", new RuntimeException()),
                                        Capability.RESPONSE_FORMAT_JSON_SCHEMA)),
                        new OrderedFailoverChatModel.Entry(
                                "secondary", new CapableModel(Capability.RESPONSE_FORMAT_JSON_SCHEMA))),
                5);

        Set<Capability> before = wrapper.supportedCapabilities();
        assertTrue(before.contains(Capability.RESPONSE_FORMAT_JSON_SCHEMA));

        // Force failover to the secondary candidate.
        wrapper.chat(request());

        assertSame(
                before,
                wrapper.supportedCapabilities(),
                "capability set instance must survive failover advance unchanged");
        assertTrue(
                before.contains(Capability.RESPONSE_FORMAT_JSON_SCHEMA),
                "capability must be the composition-time intersection, never upgraded mid-request");
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
    void capabilities_nonCapablePrimary_capableSecondary_snapshotStaysEmpty() {
        var wrapper = new OrderedFailoverChatModel(
                "route",
                List.of(
                        new OrderedFailoverChatModel.Entry("primary", new CapableModel()),
                        new OrderedFailoverChatModel.Entry(
                                "secondary", new CapableModel(Capability.RESPONSE_FORMAT_JSON_SCHEMA))),
                5);

        Set<Capability> atComposition = wrapper.supportedCapabilities();
        assertTrue(atComposition.isEmpty(), "non-capable primary must shrink the whole wrapper to non-native");

        // Failover advances to the capable secondary; the snapshot must not upgrade.
        wrapper.chat(request());

        assertSame(atComposition, wrapper.supportedCapabilities());
        assertTrue(atComposition.isEmpty(), "snapshot must stay empty even after reaching the capable candidate");
    }

    @Test
    void capabilities_snapshotNeverReReadFromCandidates() {
        var wrapper = new OrderedFailoverChatModel(
                "route",
                List.of(
                        new OrderedFailoverChatModel.Entry(
                                "primary", new CapableModel(Capability.RESPONSE_FORMAT_JSON_SCHEMA)),
                        new OrderedFailoverChatModel.Entry("secondary", new MutatingCapabilityModel())),
                5);

        Set<Capability> atComposition = wrapper.supportedCapabilities();
        assertTrue(
                atComposition.contains(Capability.RESPONSE_FORMAT_JSON_SCHEMA),
                "composition-time read sees both candidates declaring");

        // secondary's declared set is now empty; a lazy per-call recomputation would shrink the set.
        wrapper.chat(request());
        wrapper.chat(request());

        assertSame(
                atComposition,
                wrapper.supportedCapabilities(),
                "wrapper must serve the composition-time snapshot, never re-read candidates");
    }

    @Test
    void schemaBearingRequest_strippedForNonCapableCandidate() {
        // Entry without advertisement flag and a non-declaring model: the wrapper
        // must rebuild the request without the native parameter before forwarding.
        var wrapper = new OrderedFailoverChatModel(
                "route", List.of(new OrderedFailoverChatModel.Entry("plain", new OkModel())), 5);

        ChatRequest schemaRequest = StructuredSchemaRequests.withNativeJsonSchema(
                request(), "op", "{\"type\":\"object\",\"properties\":{\"a\":{\"type\":\"string\"}}}");

        // The candidate records what it actually received.
        var recording = new OkModel();
        var recordingWrapper = new OrderedFailoverChatModel(
                "route2", List.of(new OrderedFailoverChatModel.Entry("plain", recording)), 5);

        wrapper.chat(schemaRequest);
        recordingWrapper.chat(schemaRequest);

        assertFalse(
                StructuredSchemaRequests.hasNativeJsonSchema(recording.last),
                "non-capable candidate must receive the rebuilt request without the native parameter");
    }

    @Test
    void schemaRejectedByPrimary_downgradedForSecondary() {
        // Advertised primary fails with a runtime schema rejection (503); the
        // advertised secondary must receive the stripped request, never a verbatim
        // replay of the failed schema request.
        var recording = new OkModel();
        var wrapper = new OrderedFailoverChatModel(
                "route",
                List.of(
                        new OrderedFailoverChatModel.Entry(
                                "primary",
                                new ThrowingModel(new LlmServiceUnavailableException("down", new RuntimeException())),
                                true),
                        new OrderedFailoverChatModel.Entry("secondary", recording, true)),
                5);

        ChatRequest schemaRequest = StructuredSchemaRequests.withNativeJsonSchema(
                request(), "op", "{\"type\":\"object\",\"properties\":{\"a\":{\"type\":\"string\"}}}");

        wrapper.chat(schemaRequest);

        assertFalse(
                StructuredSchemaRequests.hasNativeJsonSchema(recording.last),
                "after a candidate failure, the next candidate must receive the stripped request");
    }

    @Test
    void nonCapableFailure_doesNotSuppressNativeSchemaForLaterCapableCandidate() {
        var recording = new OkModel();
        var wrapper = new OrderedFailoverChatModel(
                "route",
                List.of(
                        new OrderedFailoverChatModel.Entry(
                                "plain", new ThrowingModel(new LlmServiceUnavailableException("down", null)), false),
                        new OrderedFailoverChatModel.Entry("capable", recording, true)),
                5);

        wrapper.chat(StructuredSchemaRequests.withNativeJsonSchema(
                request(), "op", "{\"type\":\"object\",\"properties\":{\"a\":{\"type\":\"string\"}}}"));

        assertTrue(
                StructuredSchemaRequests.hasNativeJsonSchema(recording.last),
                "a non-capable candidate must not consume native-schema eligibility for the next candidate");
    }

    @Test
    void allCapableCandidatesRejectingSchema_propagatesMalformedSchemaFailure() {
        var rejection = new LlmMalformedResponseException("schema rejected", null);
        var wrapper = new OrderedFailoverChatModel(
                "route",
                List.of(
                        new OrderedFailoverChatModel.Entry("one", new ThrowingModel(rejection), true),
                        new OrderedFailoverChatModel.Entry("two", new ThrowingModel(rejection), true)),
                5);

        assertThrows(
                LlmMalformedResponseException.class,
                () -> wrapper.chat(StructuredSchemaRequests.withNativeJsonSchema(
                        request(), "op", "{\"type\":\"object\",\"properties\":{\"a\":{\"type\":\"string\"}}}")));
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

        LlmModelExhaustedException ex = assertThrows(LlmModelExhaustedException.class, () -> model.chat(request()));
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

    @Test
    void stickyFailover_exhaustsAllModels_throwsModelExhausted() {
        ChatModel m1 = new ThrowingModel(new LlmServiceUnavailableException("down", new RuntimeException()));
        ChatModel m2 = new ThrowingModel(new LlmAuthenticationException("invalid key", new RuntimeException()));

        OrderedFailoverChatModel failover = new OrderedFailoverChatModel(
                "route-exhausted",
                List.of(new OrderedFailoverChatModel.Entry("m1", m1), new OrderedFailoverChatModel.Entry("m2", m2)),
                5);

        assertThrows(LlmModelExhaustedException.class, () -> failover.chat(request()));
    }

    @Test
    void multimodalCapability_computesSafeIntersection() {
        ChatModel m1 = new OkModel();
        ChatModel m2 = new OkModel();

        MultimodalCapability cap1 = MultimodalCapabilities.defaultCapabilityForModel("gemini", "gemini-1.5-pro");
        MultimodalCapability cap2 = MultimodalCapabilities.defaultCapabilityForModel("openai", "gpt-4o");

        OrderedFailoverChatModel failover = new OrderedFailoverChatModel(
                "route-multi",
                List.of(
                        new OrderedFailoverChatModel.Entry("m1", m1, false, cap1),
                        new OrderedFailoverChatModel.Entry("m2", m2, false, cap2)),
                5);

        MultimodalCapability composite = failover.multimodalCapability();
        assertTrue(composite.isMultimodal());
        assertTrue(composite.supportsType(AttachmentType.IMAGE));
        assertFalse(composite.supportsType(AttachmentType.AUDIO));
    }
}
