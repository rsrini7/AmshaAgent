package com.amsha.core.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import com.amsha.core.classifier.wire.WireProfile;

import org.junit.jupiter.api.Test;

/**
 * Pins the {@code .env}-driven provider switch.
 *
 * <p>The selector is the only place that decides which governance lane a configured provider belongs
 * to, so the interesting cases are the ones it must <em>refuse</em>: an undeclared id, a disabled
 * slot, a missing endpoint, a missing model, and — most importantly — a loopback-ish host that is not
 * the literal {@code 127.0.0.1}. Such a host is neither a valid self-hosted endpoint nor honestly a
 * remote one, and silently choosing a lane would quietly change which governance applies.
 *
 * <p>The model checks pin the second axis: a provider id names the endpoint, the model names what is
 * served there. The System One contract makes {@code model} mandatory, so an enabled slot without one
 * is refused; and a CLM model — whose heads were trained on prose — declared without
 * {@link WireProfile#PROSE} is refused too, because that mismatch degrades answers silently instead of
 * failing loudly.
 */
class DecisionProviderSelectorTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(3);
    private static final String CREDENTIAL_REF = "vault://decision/api-key";
    private static final String DEFAULT_MODEL = "model-x";

    /** In-memory slot; the mapping is a plain interface, so a hand-written double is enough. */
    private static final class TestSlot implements DecisionProviderConfig.Slot {

        private final boolean enabled;
        private final String endpoint;
        private final String model;
        private final String apiKeyRef;
        private final Duration timeout;
        private final WireProfile wireProfile;

        private TestSlot(
                boolean enabled,
                String endpoint,
                String model,
                String apiKeyRef,
                Duration timeout,
                WireProfile wireProfile) {
            this.enabled = enabled;
            this.endpoint = endpoint;
            this.model = model;
            this.apiKeyRef = apiKeyRef;
            this.timeout = timeout;
            this.wireProfile = wireProfile;
        }

        static TestSlot on(String endpoint) {
            return on(endpoint, DEFAULT_MODEL, WireProfile.JSON_OBJECT);
        }

        static TestSlot on(String endpoint, WireProfile wireProfile) {
            return on(endpoint, DEFAULT_MODEL, wireProfile);
        }

        static TestSlot on(String endpoint, String model, WireProfile wireProfile) {
            return new TestSlot(true, endpoint, model, CREDENTIAL_REF, TIMEOUT, wireProfile);
        }

        static TestSlot off(String endpoint) {
            return new TestSlot(false, endpoint, null, null, TIMEOUT, WireProfile.JSON_OBJECT);
        }

        @Override
        public boolean enabled() {
            return enabled;
        }

        @Override
        public Optional<String> endpoint() {
            return Optional.ofNullable(endpoint);
        }

        @Override
        public Optional<String> model() {
            return Optional.ofNullable(model);
        }

        @Override
        public Optional<String> apiKeyRef() {
            return Optional.ofNullable(apiKeyRef);
        }

        @Override
        public Duration timeout() {
            return timeout;
        }

        @Override
        public WireProfile wireProfile() {
            return wireProfile;
        }
    }

    private static DecisionProviderConfig config(String selected, Map<String, DecisionProviderConfig.Slot> slots) {
        Map<String, DecisionProviderConfig.Slot> declared = new LinkedHashMap<>(slots);
        return new DecisionProviderConfig() {
            @Override
            public String selected() {
                return selected;
            }

            @Override
            public Map<String, Slot> slots() {
                return declared;
            }
        };
    }

    private static DecisionProviderConfig one(String selected, DecisionProviderConfig.Slot slot) {
        return config(selected, Map.of(selected, slot));
    }

    private static DecisionProviderSelector.Selection.Selected selected(DecisionProviderConfig config) {
        return assertInstanceOf(
                DecisionProviderSelector.Selection.Selected.class, DecisionProviderSelector.select(config));
    }

    private static DecisionProviderSelector.Selection.Unavailable unavailable(DecisionProviderConfig config) {
        return assertInstanceOf(
                DecisionProviderSelector.Selection.Unavailable.class, DecisionProviderSelector.select(config));
    }

    @Test
    void resolvesNothingWhenNoProviderIsSelected() {
        var refused = unavailable(one("", TestSlot.on("http://127.0.0.1:8000/v1/systemone")));

        assertTrue(refused.reason().contains("blank"), refused.reason());
        assertTrue(refused.reason().contains("inert"), refused.reason());
    }

    @Test
    void treatsAWhitespaceSelectedValueAsNoSelection() {
        assertTrue(unavailable(config("   ", Map.of())).reason().contains("blank"));
    }

    @Test
    void refusesAProviderThatIsNotDeclared() {
        var declared =
                Map.<String, DecisionProviderConfig.Slot>of("omlx", TestSlot.on("http://127.0.0.1:8000/v1/systemone"));

        var refused = unavailable(config("typo", declared));

        assertTrue(refused.reason().contains("not declared"), refused.reason());
    }

    @Test
    void refusesADeclaredButDisabledProvider() {
        var refused = unavailable(one("omlx", TestSlot.off("http://127.0.0.1:8000/v1/systemone")));

        assertTrue(refused.reason().contains("not enabled"), refused.reason());
    }

    @Test
    void refusesAProviderWithoutAnEndpoint() {
        var refused = unavailable(one("omlx", new TestSlot(true, null, null, null, TIMEOUT, WireProfile.JSON_OBJECT)));

        assertTrue(refused.reason().contains("no endpoint"), refused.reason());
    }

    @Test
    void refusesAnEndpointThatIsNotAValidUri() {
        var refused = unavailable(one("omlx", TestSlot.on("http://exa mple.com/v1/systemone")));

        assertTrue(refused.reason().contains("not a valid URI"), refused.reason());
    }

    @Test
    void refusesAnEndpointWithoutAHost() {
        var refused = unavailable(one("omlx", TestSlot.on("file:///tmp/systemone")));

        assertTrue(refused.reason().contains("no host"), refused.reason());
    }

    @Test
    void refusesARemoteProviderThatNamesNoModel() {
        // The hosted System One contract makes `model` mandatory, so a remote slot that is enabled but
        // names no model is refused rather than sent with a silently defaulted one.
        var refused = unavailable(
                one("typesafe", TestSlot.on("https://api.typesafe.ai/v1/systemone", null, WireProfile.JSON_OBJECT)));

        assertTrue(refused.reason().contains("no model"), refused.reason());
        assertTrue(refused.reason().contains("remote"), refused.reason());
    }

    @Test
    void treatsAWhitespaceModelOnARemoteProviderAsNoModel() {
        var refused = unavailable(
                one("typesafe", TestSlot.on("https://api.typesafe.ai/v1/systemone", "   ", WireProfile.JSON_OBJECT)));

        assertTrue(refused.reason().contains("no model"), refused.reason());
    }

    @Test
    void allowsASelfHostedProviderToOmitTheModel() {
        // The self-hosted lane is deliberately different: those servers select their own checkpoint and
        // the mapper omits the field, so a blank model is a legitimate configuration, not a defect.
        var resolved =
                selected(one("laya", TestSlot.on("http://127.0.0.1:8000/v1/systemone", null, WireProfile.JSON_OBJECT)));

        assertEquals("", resolved.model());
    }

    @Test
    void refusesANonPositiveTimeout() {
        // Both provider config constructors reject this, so Selected must not carry it either.
        var zero = unavailable(one(
                "laya",
                new TestSlot(
                        true,
                        "http://127.0.0.1:8000/v1/systemone",
                        DEFAULT_MODEL,
                        CREDENTIAL_REF,
                        Duration.ZERO,
                        WireProfile.JSON_OBJECT)));
        assertTrue(zero.reason().contains("non-positive timeout"), zero.reason());

        var negative = unavailable(one(
                "laya",
                new TestSlot(
                        true,
                        "http://127.0.0.1:8000/v1/systemone",
                        DEFAULT_MODEL,
                        CREDENTIAL_REF,
                        Duration.ofSeconds(-1),
                        WireProfile.JSON_OBJECT)));
        assertTrue(negative.reason().contains("non-positive timeout"), negative.reason());
    }

    @Test
    void refusesACredentialedRemoteProviderWithoutACredentialReference() {
        // TypeSafe's documented profile authenticates with a bearer token, so a slot naming it with no
        // api-key-ref is incomplete rather than merely unauthenticated.
        var refused = unavailable(one(
                "typesafe",
                new TestSlot(
                        true,
                        "https://api.typesafe.ai/v1/systemone",
                        DEFAULT_MODEL,
                        null,
                        TIMEOUT,
                        WireProfile.JSON_OBJECT)));

        assertTrue(refused.reason().contains("api-key-ref"), refused.reason());
    }

    @Test
    void allowsAnUncredentialedRemoteProviderWithoutACredentialReference() {
        // The rule is deliberately narrow: "remote" alone does not imply "needs a bearer token", because
        // a remote deployment may authenticate by mTLS or network policy instead.
        var resolved = selected(one(
                "internal",
                new TestSlot(
                        true,
                        "https://decision.internal.example.com/v1/systemone",
                        DEFAULT_MODEL,
                        null,
                        TIMEOUT,
                        WireProfile.JSON_OBJECT)));

        assertEquals("", resolved.apiKeyRef());
    }

    @Test
    void refusesAClmModelThatIsNotDeclaredAsProse() {
        // CLM's heads were trained on prose: a JSON `state` is not rejected by the server, it
        // degrades the answer. So the contradiction is refused before anything is sent.
        var refused = unavailable(
                one("clm", TestSlot.on("http://127.0.0.1:8700/v1/systemone", "clm-latest", WireProfile.JSON_OBJECT)));

        assertTrue(refused.reason().contains("clm-latest"), refused.reason());
        assertTrue(refused.reason().contains("PROSE"), refused.reason());
    }

    @Test
    void refusesAClmModelWithoutTheDashSuffixDeclaredAsJson() {
        // The guard keys off the model name, not the provider id: a CLM family member named with a
        // dot or underscore separator is caught too.
        assertTrue(unavailable(one(
                        "clm",
                        TestSlot.on(
                                "http://127.0.0.1:8700/v1/systemone", "clm_v0.1_8B_MLX_8bit", WireProfile.JSON_OBJECT)))
                .reason()
                .contains("PROSE"));
    }

    @Test
    void acceptsAClmModelThatDeclaresProse() {
        var resolved = selected(
                one("clm", TestSlot.on("http://127.0.0.1:8700/v1/systemone", "clm-latest", WireProfile.PROSE)));

        assertEquals("clm-latest", resolved.model());
        assertEquals(WireProfile.PROSE, resolved.wireProfile());
    }

    @Test
    void doesNotRequireProseForAModelThatMerelyContainsClm() {
        // The pattern is anchored at the start of the family name, so a model that merely mentions
        // "clm" mid-string is not dragged into the prose requirement.
        var resolved = selected(one(
                "typesafe",
                TestSlot.on("https://api.typesafe.ai/v1/systemone", "not-clm-model", WireProfile.JSON_OBJECT)));

        assertEquals(WireProfile.JSON_OBJECT, resolved.wireProfile());
    }

    @Test
    void resolvesTheSelfHostedLaneForTheLiteralLoopbackHost() {
        var resolved = selected(one("clm", TestSlot.on("http://127.0.0.1:8700/v1/systemone", WireProfile.PROSE)));

        assertEquals("clm", resolved.providerId());
        assertEquals(DecisionProviderSelector.Lane.SELF_HOSTED, resolved.lane());
        assertEquals(URI.create("http://127.0.0.1:8700/v1/systemone"), resolved.endpoint());
        assertEquals(DEFAULT_MODEL, resolved.model());
        assertEquals(TIMEOUT, resolved.timeout());
    }

    @Test
    void carriesTheWireProfileOfTheSelectedProvider() {
        assertEquals(
                WireProfile.PROSE,
                selected(one("clm", TestSlot.on("http://127.0.0.1:8700/v1/systemone", WireProfile.PROSE)))
                        .wireProfile());
        assertEquals(
                WireProfile.JSON_OBJECT,
                selected(one("laya", TestSlot.on("http://127.0.0.1:8000/v1/systemone")))
                        .wireProfile());
    }

    @Test
    void resolvesTheRemoteLaneForAHttpsHost() {
        var resolved = selected(one("typesafe", TestSlot.on("https://api.typesafe.ai/v1/systemone")));

        assertEquals(DecisionProviderSelector.Lane.REMOTE, resolved.lane());
    }

    @Test
    void refusesLocalhostBecauseTheLaneWouldBeAmbiguous() {
        var refused = unavailable(one("omlx", TestSlot.on("http://localhost:8000/v1/systemone")));

        assertTrue(refused.reason().contains("ambiguous"), refused.reason());
    }

    @Test
    void refusesIpv6LoopbackBecauseTheLaneWouldBeAmbiguous() {
        var refused = unavailable(one("omlx", TestSlot.on("http://[::1]:8000/v1/systemone")));

        assertTrue(refused.reason().contains("ambiguous"), refused.reason());
    }

    @Test
    void refusesOtherAddressesInTheLoopbackRangeBecauseTheLaneWouldBeAmbiguous() {
        var refused = unavailable(one("omlx", TestSlot.on("http://127.0.0.2:8000/v1/systemone")));

        assertTrue(refused.reason().contains("ambiguous"), refused.reason());
    }

    @Test
    void refusesAWildcardBinding() {
        var refused = unavailable(one("omlx", TestSlot.on("http://0.0.0.0:8700/v1/systemone")));

        assertTrue(refused.reason().contains("failed endpoint validation"), refused.reason());
        assertTrue(refused.reason().contains("0.0.0.0"), refused.reason());
    }

    @Test
    void refusesPlainHttpToANonLoopbackHost() {
        var refused = unavailable(one("typesafe", TestSlot.on("http://api.typesafe.ai/v1/systemone")));

        assertTrue(refused.reason().contains("failed endpoint validation"), refused.reason());
    }

    @Test
    void refusesALoopbackEndpointWithoutAnExplicitPort() {
        var refused = unavailable(one("omlx", TestSlot.on("http://127.0.0.1/v1/systemone")));

        assertTrue(refused.reason().contains("failed endpoint validation"), refused.reason());
    }

    @Test
    void carriesTheCredentialReferenceWithoutResolvingIt() {
        var resolved = selected(one("typesafe", TestSlot.on("https://api.typesafe.ai/v1/systemone")));

        assertEquals(CREDENTIAL_REF, resolved.apiKeyRef());
    }

    @Test
    void aRefusalNamesTheProviderButNeverTheCredentialReference() {
        var slot = new TestSlot(true, null, DEFAULT_MODEL, CREDENTIAL_REF, TIMEOUT, WireProfile.JSON_OBJECT);

        var refused = unavailable(one("omlx", slot));

        assertTrue(refused.reason().contains("omlx"), refused.reason());
        assertFalse(refused.reason().contains("vault://"), refused.reason());
    }
}
