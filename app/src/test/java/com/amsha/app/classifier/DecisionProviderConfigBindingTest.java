package com.amsha.app.classifier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

import java.time.Duration;
import java.util.Map;

import com.amsha.classifier.api.DecisionEngine;
import com.amsha.core.classifier.wire.WireProfile;
import com.amsha.core.config.DecisionProviderConfig;
import com.amsha.core.config.DecisionProviderSelector;

import org.junit.jupiter.api.Test;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;

/**
 * Pins the {@code .env}-driven decision provider switch at the configuration boundary.
 *
 * <p>{@code DecisionProviderSelectorTest} drives the selector through a hand-written config double. That
 * proves the selection logic but says nothing about whether the {@code @ConfigMapping} actually binds: a
 * renamed property, an unparseable enum constant, or an unsupported {@code Map<String, Slot>} group would
 * leave every one of those tests green while the real configuration resolved to nothing. This test binds
 * the mapping inside the container.
 *
 * <p>Its most important assertion is {@link #configuringAProviderDoesNotMakeADecisionEngineResolvable()}.
 * Pointing the configuration at a concrete, enabled, valid provider must <em>not</em> make a
 * {@code DecisionEngine} resolvable — adding configuration is not the ADR change, wiring it to an engine
 * is.
 *
 * <p>{@link #bindsTheModelIndependentlyOfTheProviderId()} pins the two axes at the boundary: the provider
 * id names the endpoint and the {@code model} field names what is served there, exactly as
 * {@code amsha.llm.<provider>.model} does on the LLM side.
 */
@QuarkusTest
@TestProfile(DecisionProviderConfigBindingTest.ConfiguredProviderProfile.class)
class DecisionProviderConfigBindingTest {

    @Inject
    DecisionProviderConfig config;

    @Inject
    Instance<DecisionEngine> engineInstance;

    @Test
    void bindsTheSelectedProviderAndItsDeclaredSlot() {
        assertEquals("clm", config.selected());

        DecisionProviderConfig.Slot clm = config.slots().get("clm");
        assertNotNull(clm, "the clm slot must be declared");
        assertTrue(clm.enabled());
        assertEquals("http://127.0.0.1:8700/v1/systemone", clm.endpoint().orElseThrow());
        assertEquals("clm-latest", clm.model().orElseThrow());
        assertEquals("vault://decision/clm", clm.apiKeyRef().orElseThrow());
    }

    @Test
    void bindsTheModelIndependentlyOfTheProviderId() {
        // The provider id is the endpoint; `model` is a separate axis naming the checkpoint served
        // there. Both slots must carry their own model, and neither may inherit the other's.
        assertEquals("clm-latest", config.slots().get("clm").model().orElseThrow());
        assertEquals("jev-latest", config.slots().get("typesafe").model().orElseThrow());
    }

    @Test
    void convertsTheDurationAndTheEnumRatherThanLeavingThemAsText() {
        assertEquals(Duration.ofSeconds(7), config.slots().get("clm").timeout());
        assertEquals(
                WireProfile.PROSE,
                config.slots().get("clm").wireProfile(),
                "PROSE must bind to the enum constant, not survive as a string");
        assertEquals(
                WireProfile.JSON_OBJECT,
                config.slots().get("typesafe").wireProfile(),
                "a slot that omits wire-profile must fall back to the documented default");
    }

    @Test
    void resolvesTheConfiguredProviderToItsLaneAndDialect() {
        var selection = DecisionProviderSelector.select(config);

        var selected = assertInstanceOf(DecisionProviderSelector.Selection.Selected.class, selection);
        assertEquals("clm", selected.providerId());
        assertEquals(DecisionProviderSelector.Lane.SELF_HOSTED, selected.lane());
        assertEquals("clm-latest", selected.model());
        assertEquals(WireProfile.PROSE, selected.wireProfile());
        assertEquals(Duration.ofSeconds(7), selected.timeout());
    }

    @Test
    void configuringAProviderDoesNotMakeADecisionEngineResolvable() {
        // The Deferred invariant, asserted directly rather than inferred. If this fails, a production
        // DecisionEngine has been registered and the ADR status must be revisited on purpose.
        assertFalse(engineInstance.isResolvable(), "configuring a decision provider must not light the decision plane");
    }

    /** Declares two providers, selects one, and registers no engine anywhere. */
    public static class ConfiguredProviderProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            // Map.of caps at ten pairs and this profile needs eleven, so ofEntries is required here.
            return Map.ofEntries(
                    Map.entry("amsha.decision.providers.selected", "clm"),
                    Map.entry("amsha.decision.providers.slots.clm.enabled", "true"),
                    Map.entry("amsha.decision.providers.slots.clm.endpoint", "http://127.0.0.1:8700/v1/systemone"),
                    Map.entry("amsha.decision.providers.slots.clm.model", "clm-latest"),
                    Map.entry("amsha.decision.providers.slots.clm.api-key-ref", "vault://decision/clm"),
                    Map.entry("amsha.decision.providers.slots.clm.timeout", "PT7S"),
                    Map.entry("amsha.decision.providers.slots.clm.wire-profile", "PROSE"),
                    Map.entry("amsha.decision.providers.slots.typesafe.enabled", "true"),
                    Map.entry(
                            "amsha.decision.providers.slots.typesafe.endpoint", "https://api.typesafe.ai/v1/systemone"),
                    Map.entry("amsha.decision.providers.slots.typesafe.model", "jev-latest"),
                    Map.entry("amsha.decision.providers.slots.typesafe.api-key-ref", "vault://decision/typesafe"));
        }
    }
}
