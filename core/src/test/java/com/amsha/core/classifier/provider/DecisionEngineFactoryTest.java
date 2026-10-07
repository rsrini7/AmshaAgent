package com.amsha.core.classifier.provider;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Optional;

import com.amsha.core.classifier.remote.RemoteDecisionEngine;
import com.amsha.core.classifier.selfhosted.SelfHostedDecisionEngine;
import com.amsha.core.classifier.wire.WireProfile;
import com.amsha.core.config.DecisionGovernanceFixture;
import com.amsha.core.config.DecisionProviderConfig;
import com.amsha.core.config.DecisionProviderSelector;
import com.amsha.core.config.DecisionProviderSelector.Lane;
import com.amsha.core.config.DecisionProviderSelector.Selection.Selected;
import com.amsha.core.security.OutboundHttpPolicyService;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;

/**
 * Pins the single engine-construction path.
 *
 * <p>What is asserted is exactly what the factory owns: which engine a lane produces, that an incomplete
 * governance declaration or an unbuildable provider fails closed with one payload-free refusal type, and
 * that neither the built engine nor a refusal ever carries the credential. The provider <em>config</em>
 * is not reachable from the handle, so the config-level rules (blank self-hosted model omitted, prose
 * honoured) are covered by the mapping/selector tests instead; here they appear as "construction
 * succeeds", which is the factory's actual obligation.
 */
class DecisionEngineFactoryTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final OutboundHttpPolicyService OUTBOUND = mock(OutboundHttpPolicyService.class);
    private static final String CREDENTIAL_TEXT = "s3cret-token";
    private static final String LOOPBACK = "http://127.0.0.1:8000/v1/systemone";
    private static final String HOSTED = "https://api.typesafe.ai/v1/systemone";

    @Test
    void buildsASelfHostedEngineForTheLoopbackLane() {
        // This selection declares a credential reference (the helper defaults it to "REF") yet supplies no
        // material, and must still build: the credential requirement is provider-based, so it covers only
        // the bearer-token remote profiles, never the loopback lane's optional header.
        try (DecisionEngineHandle handle = create(
                selected("laya", Lane.SELF_HOSTED, LOOPBACK, "laya-multilingual", WireProfile.JSON_OBJECT),
                DecisionGovernanceFixture.valid(),
                Optional.empty())) {
            assertInstanceOf(SelfHostedDecisionEngine.class, handle.engine());
        }
    }

    @Test
    void buildsARemoteEngineForTheRemoteLane() {
        try (DecisionEngineHandle handle = create(
                selected("typesafe", Lane.REMOTE, HOSTED, "jev-latest", WireProfile.JSON_OBJECT),
                DecisionGovernanceFixture.valid(),
                Optional.of(credential()))) {
            assertInstanceOf(RemoteDecisionEngine.class, handle.engine());
        }
    }

    @Test
    void aSelfHostedSelectionMayOmitTheModel() {
        // The loopback lane's server selects its own checkpoint and the mapper omits the field, so the
        // factory must not demand a model there the way the hosted contract does.
        try (DecisionEngineHandle handle = create(
                selected("laya", Lane.SELF_HOSTED, LOOPBACK, "", WireProfile.JSON_OBJECT),
                DecisionGovernanceFixture.valid(),
                Optional.empty())) {
            assertInstanceOf(SelfHostedDecisionEngine.class, handle.engine());
        }
    }

    @Test
    void aSelfHostedSelectionMayDeclareProse() {
        try (DecisionEngineHandle handle = create(
                selected(
                        "clm", Lane.SELF_HOSTED, "http://127.0.0.1:8700/v1/systemone", "clm-latest", WireProfile.PROSE),
                DecisionGovernanceFixture.valid(),
                Optional.empty())) {
            assertInstanceOf(SelfHostedDecisionEngine.class, handle.engine());
        }
    }

    @Test
    void refusesAnIncompleteGovernanceDeclaration() {
        var thrown = assertThrows(
                DecisionProviderUnavailableException.class,
                () -> create(
                        selected("laya", Lane.SELF_HOSTED, LOOPBACK, "laya-multilingual", WireProfile.JSON_OBJECT),
                        DecisionGovernanceFixture.withTrainingUse(true),
                        Optional.empty()));

        assertTrue(thrown.getMessage().contains("laya"), thrown.getMessage());
        assertTrue(thrown.getMessage().contains("allow-training-use"), thrown.getMessage());
    }

    @Test
    void refusesARemoteSelectionWithNoModel() {
        // The hosted contract makes `model` mandatory, so the provider-config record refuses a blank one.
        // The factory normalises that into its own refusal type rather than letting the raw unchecked
        // exception escape.
        var thrown = assertThrows(
                DecisionProviderUnavailableException.class,
                () -> create(
                        selected("typesafe", Lane.REMOTE, HOSTED, "", WireProfile.JSON_OBJECT),
                        DecisionGovernanceFixture.valid(),
                        Optional.of(credential())));

        assertTrue(thrown.getMessage().contains("typesafe"), thrown.getMessage());
    }

    @Test
    void refusesARemoteSelectionWithNoCredentialReference() {
        // Selection.Selected is a publicly constructible record, so the factory must apply the
        // selector's credential rule itself rather than trusting that only the selector builds one.
        var thrown = assertThrows(
                DecisionProviderUnavailableException.class,
                () -> create(
                        selected(
                                "typesafe",
                                Lane.REMOTE,
                                HOSTED,
                                "jev-latest",
                                "",
                                Duration.ofSeconds(3),
                                WireProfile.JSON_OBJECT),
                        DecisionGovernanceFixture.valid(),
                        Optional.empty()));

        assertTrue(thrown.getMessage().contains("typesafe"), thrown.getMessage());
        assertTrue(thrown.getMessage().contains("api-key-ref"), thrown.getMessage());
    }

    @Test
    void refusesACredentialedProviderWithNoResolvedCredential() {
        // The mirror of the test above: the reference is declared, but the vault resolved it to nothing.
        // Building anyway would send an empty Authorization header and fail at the provider instead of
        // here, so a reference that resolved to no material is refused as firmly as a missing reference.
        var thrown = assertThrows(
                DecisionProviderUnavailableException.class,
                () -> create(
                        selected("typesafe", Lane.REMOTE, HOSTED, "jev-latest", WireProfile.JSON_OBJECT),
                        DecisionGovernanceFixture.valid(),
                        Optional.empty()));

        assertTrue(thrown.getMessage().contains("typesafe"), thrown.getMessage());
        assertTrue(thrown.getMessage().contains("api-key-ref"), thrown.getMessage());
        assertTrue(thrown.getMessage().contains("credential material"), thrown.getMessage());
    }

    @Test
    void refusesACredentialedProviderWithEmptyCredentialBytes() {
        // A resolver that returns an empty array rather than an empty Optional is the same gap.
        var thrown = assertThrows(
                DecisionProviderUnavailableException.class,
                () -> create(
                        selected("typesafe", Lane.REMOTE, HOSTED, "jev-latest", WireProfile.JSON_OBJECT),
                        DecisionGovernanceFixture.valid(),
                        Optional.of(new byte[0])));

        assertTrue(thrown.getMessage().contains("credential material"), thrown.getMessage());
    }

    @Test
    void refusesACredentialedProviderWithBlankCredentialMaterial() {
        // Whitespace is no more a bearer token than an empty string, so it is treated as absent.
        var thrown = assertThrows(
                DecisionProviderUnavailableException.class,
                () -> create(
                        selected("typesafe", Lane.REMOTE, HOSTED, "jev-latest", WireProfile.JSON_OBJECT),
                        DecisionGovernanceFixture.valid(),
                        Optional.of("   ".getBytes(StandardCharsets.UTF_8))));

        assertTrue(thrown.getMessage().contains("credential material"), thrown.getMessage());
    }

    @Test
    void refusesAClmSelectionWithTheWrongStateDialect() {
        var thrown = assertThrows(
                DecisionProviderUnavailableException.class,
                () -> create(
                        selected(
                                "clm",
                                Lane.SELF_HOSTED,
                                "http://127.0.0.1:8700/v1/systemone",
                                "clm-latest",
                                "REF",
                                Duration.ofSeconds(3),
                                WireProfile.JSON_OBJECT),
                        DecisionGovernanceFixture.valid(),
                        Optional.empty()));

        assertTrue(thrown.getMessage().contains("PROSE"), thrown.getMessage());
    }

    @Test
    void refusesANonPositiveTimeout() {
        var thrown = assertThrows(
                DecisionProviderUnavailableException.class,
                () -> create(
                        selected(
                                "laya",
                                Lane.SELF_HOSTED,
                                LOOPBACK,
                                "laya-multilingual",
                                "REF",
                                Duration.ZERO,
                                WireProfile.JSON_OBJECT),
                        DecisionGovernanceFixture.valid(),
                        Optional.empty()));

        assertTrue(thrown.getMessage().contains("timeout"), thrown.getMessage());
    }

    @Test
    void refusesAnEndpointTheLaneValidatorRejects() {
        // Plain HTTP on a non-loopback host: the remote lane validator requires HTTPS there.
        var thrown = assertThrows(
                DecisionProviderUnavailableException.class,
                () -> create(
                        selected(
                                "typesafe",
                                Lane.REMOTE,
                                "http://api.typesafe.ai/v1/systemone",
                                "jev-latest",
                                "REF",
                                Duration.ofSeconds(3),
                                WireProfile.JSON_OBJECT),
                        DecisionGovernanceFixture.valid(),
                        Optional.of(credential())));

        assertTrue(thrown.getMessage().contains("endpoint validation"), thrown.getMessage());
    }

    @Test
    void aRefusalNeverCarriesTheCredential() {
        var thrown = assertThrows(
                DecisionProviderUnavailableException.class,
                () -> create(
                        selected("typesafe", Lane.REMOTE, HOSTED, "jev-latest", WireProfile.JSON_OBJECT),
                        DecisionGovernanceFixture.withTenantIsolation(false),
                        Optional.of(credential())));

        assertFalse(thrown.getMessage().contains(CREDENTIAL_TEXT), thrown.getMessage());
    }

    @Test
    void theBuiltEngineNeverRendersTheCredential() {
        try (DecisionEngineHandle handle = create(
                selected("typesafe", Lane.REMOTE, HOSTED, "jev-latest", WireProfile.JSON_OBJECT),
                DecisionGovernanceFixture.valid(),
                Optional.of(credential()))) {
            assertFalse(handle.engine().toString().contains(CREDENTIAL_TEXT));
        }
    }

    @Test
    void closingTheHandleTwiceIsSafe() {
        DecisionEngineHandle handle = create(
                selected("laya", Lane.SELF_HOSTED, LOOPBACK, "laya-multilingual", WireProfile.JSON_OBJECT),
                DecisionGovernanceFixture.valid(),
                Optional.empty());

        assertDoesNotThrow(handle::close);
        assertDoesNotThrow(handle::close);
    }

    @Test
    void requiresTheSelectionAndItsGovernance() {
        Selected selection = selected("laya", Lane.SELF_HOSTED, LOOPBACK, "m", WireProfile.JSON_OBJECT);

        assertThrows(
                NullPointerException.class, () -> create(null, DecisionGovernanceFixture.valid(), Optional.empty()));
        assertThrows(NullPointerException.class, () -> create(selection, null, Optional.empty()));
        assertThrows(NullPointerException.class, () -> create(selection, DecisionGovernanceFixture.valid(), null));
    }

    private static byte[] credential() {
        return CREDENTIAL_TEXT.getBytes(StandardCharsets.UTF_8);
    }

    private static DecisionEngineHandle create(
            Selected selected, DecisionProviderConfig.Slot.Governance governance, Optional<byte[]> credential) {
        return DecisionEngineFactory.create(selected, governance, credential, MAPPER, OUTBOUND, null);
    }

    private static Selected selected(
            String providerId, Lane lane, String endpoint, String model, WireProfile wireProfile) {
        return selected(providerId, lane, endpoint, model, "REF", Duration.ofSeconds(3), wireProfile);
    }

    private static Selected selected(
            String providerId,
            Lane lane,
            String endpoint,
            String model,
            String apiKeyRef,
            Duration timeout,
            WireProfile wireProfile) {
        return new DecisionProviderSelector.Selection.Selected(
                providerId, lane, URI.create(endpoint), model, apiKeyRef, timeout, wireProfile);
    }
}
