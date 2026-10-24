package com.amsha.app;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import jakarta.enterprise.inject.Any;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import com.amsha.core.agent.reasoning.PromptTemplateEngine;
import com.amsha.core.agent.role.AgentRoleEnum;
import com.amsha.core.agent.role.CapabilityRegistry;
import com.amsha.core.config.CoreSpiResolutionValidator;
import com.amsha.core.routing.SkillAwareIntentDetector;
import com.amsha.core.security.ExternalIdentityProvider;
import com.amsha.core.skill.SkillRegistry;
import com.amsha.memory.tiered.ArchivalMemoryService;
import com.amsha.memory.tiered.CoreMemoryService;
import com.amsha.memory.tiered.DirectiveFeedbackPort;
import com.amsha.memory.tiered.DirectiveProvider;
import com.amsha.memory.tiered.RecallMemoryService;
import com.amsha.memory.tiered.TieredBackendLiteral;
import com.amsha.persistence.api.ArchivalMemoryStorePort;
import com.amsha.persistence.api.CoreMemoryStorePort;
import com.amsha.persistence.api.RecallMemoryStorePort;
import com.amsha.security.api.SecretVault;
import com.amsha.security.http.GovernedTransportService;
import com.amsha.skills.SkillLoader;
import com.amsha.skills.SkillProvider;
import com.amsha.tool.api.ToolCapability;

import org.junit.jupiter.api.Test;

import io.quarkus.test.junit.QuarkusTest;

/**
 * Issue 10 JVM CDI startup proof for the extract-core-subsystems program.
 * <p>
 * A green {@code @QuarkusTest} boot already proves the {@link CoreSpiResolutionValidator}
 * startup cardinality check passed (it is a {@code @Startup} observer validated in
 * {@code @PostConstruct}); this suite additionally binds the four subsystems extracted by
 * the program — amsha-security, amsha-memory, amsha-skills, and the tiered persistence
 * stores — into explicit assertions so wiring regressions surface as test failures rather
 * than silent NoOp fallbacks.
 */
@QuarkusTest
class FinalWiringStartupProofTest {

    @Inject
    CoreMemoryService coreMemoryService;

    @Inject
    RecallMemoryService recallMemoryService;

    @Inject
    ArchivalMemoryService archivalMemoryService;

    @Inject
    CoreMemoryStorePort coreMemoryStorePort;

    @Inject
    RecallMemoryStorePort recallMemoryStorePort;

    @Inject
    ArchivalMemoryStorePort archivalMemoryStorePort;

    @Inject
    SecretVault secretVault;

    @Inject
    GovernedTransportService governedTransportService;

    @Inject
    SkillLoader skillLoader;

    @Inject
    Instance<SkillProvider> skillProviders;

    @Inject
    @Any
    Instance<ExternalIdentityProvider> identityProviders;

    @Inject
    @Any
    Instance<CoreMemoryStorePort> coreStores;

    @Inject
    @Any
    Instance<RecallMemoryStorePort> recallStores;

    @Inject
    @Any
    Instance<ArchivalMemoryStorePort> archivalStores;

    @Inject
    CoreSpiResolutionValidator validator;

    @Inject
    CapabilityRegistry capabilityRegistry;

    @Inject
    PromptTemplateEngine promptTemplateEngine;

    @Inject
    SkillAwareIntentDetector skillAwareIntentDetector;

    @Inject
    SkillRegistry skillRegistry;

    @Test
    void memoryServicesAndPortsBoundAtBoot() {
        assertNotNull(coreMemoryService, "CoreMemoryService must be bound at startup");
        assertNotNull(recallMemoryService, "RecallMemoryService must be bound at startup");
        assertNotNull(archivalMemoryService, "ArchivalMemoryService must be bound at startup");
        // TieredMemoryProducer always resolves a port (backend impl or safe NoOp when disabled);
        // injection success proves the producer chain wired.
        assertNotNull(coreMemoryStorePort, "CoreMemoryStorePort producer must resolve");
        assertNotNull(recallMemoryStorePort, "RecallMemoryStorePort producer must resolve");
        assertNotNull(archivalMemoryStorePort, "ArchivalMemoryStorePort producer must resolve");
    }

    @Test
    void sqliteTieredBackendBeansSelectableByLiteral() {
        // The default backend's beans exist and match the TieredBackendLiteral selector —
        // the dynamic-discovery path whose impls carry @Unremovable for native retention.
        assertTrue(
                coreStores.select(new TieredBackendLiteral("sqlite")).isResolvable(),
                "sqlite CoreMemoryStorePort bean must be selectable via TieredBackendLiteral");
        assertTrue(
                recallStores.select(new TieredBackendLiteral("sqlite")).isResolvable(),
                "sqlite RecallMemoryStorePort bean must be selectable via TieredBackendLiteral");
        assertTrue(
                archivalStores.select(new TieredBackendLiteral("sqlite")).isResolvable(),
                "sqlite ArchivalMemoryStorePort bean must be selectable via TieredBackendLiteral");
        // Non-default backends are build-time gated by @IfBuildProperty and correctly absent.
        assertTrue(
                coreStores.select(new TieredBackendLiteral("postgres")).isUnsatisfied(),
                "postgres beans must be excluded by build-time property gating");
        assertTrue(
                coreStores.select(new TieredBackendLiteral("fs")).isUnsatisfied(),
                "fs beans must be excluded by build-time property gating");
    }

    @Test
    void securitySubsystemResolved() {
        assertNotNull(secretVault, "SecretVault provider must be resolved at startup");
        assertNotNull(governedTransportService, "GovernedTransportService must be active at startup");
        assertEquals(
                2,
                identityProviders.stream().count(),
                "Github + OIDC external identity providers must both be discovered");
        // Explicit re-run of the validator's cardinality algorithm for the security SPIs
        // (already proven once during @PostConstruct; repeated here as retained evidence).
        validator.validateCardinality(SecretVault.class);
        validator.validateCardinality(DirectiveProvider.class);
        validator.validateCardinality(DirectiveFeedbackPort.class);
    }

    @Test
    void skillsMachineryRegisteredAtBootstrap() {
        assertNotNull(skillLoader, "SkillLoader (@Startup) must be registered at bootstrap");
        List<SkillProvider> providers = skillProviders.stream().toList();
        assertEquals(
                3,
                providers.size(),
                "all three SkillProvider impls (classpath, database, skills-jar) must be discovered: " + providers);
    }

    @Test
    void memoryCapabilitiesAreAvailableAcrossBuiltInRoles() {
        List<ToolCapability> memoryCapabilities = List.of(
                ToolCapability.RECALL_MEMORY_SEARCH,
                ToolCapability.ARCHIVAL_MEMORY_SEARCH,
                ToolCapability.CORE_MEMORY_READ,
                ToolCapability.CORE_MEMORY_APPEND,
                ToolCapability.CORE_MEMORY_REPLACE,
                ToolCapability.ARCHIVAL_MEMORY_INSERT,
                ToolCapability.INGEST_DOCUMENT);

        for (AgentRoleEnum role : AgentRoleEnum.values()) {
            for (ToolCapability capability : memoryCapabilities) {
                assertTrue(
                        capabilityRegistry.hasCapability(role.id(), capability.toolName()),
                        () -> role.id() + " lacks memory capability " + capability.toolName());
            }
        }
    }

    @Test
    void researchAndSynthesisTemplatesIncludeEvidenceStandards() {
        String researcherPrompt = promptTemplateEngine.buildSystemPrompt(AgentRoleEnum.RESEARCHER, null, null);
        String synthesizerPrompt = promptTemplateEngine.buildSystemPrompt(AgentRoleEnum.SYNTHESIZER, null, null);

        assertTrue(researcherPrompt.contains("EVIDENCE AND UNCERTAINTY"));
        assertTrue(synthesizerPrompt.contains("EVIDENCE AND UNCERTAINTY"));
        assertTrue(synthesizerPrompt.contains("never invent citations"));
    }

    @Test
    void classpathKnowledgeCaptureSkillHasNarrowRoutingAndOneTool() {
        var skill = skillRegistry.findByName("capture-knowledge-note").orElseThrow();

        assertEquals("classpath", skill.source());
        assertEquals(
                Set.of(ToolCapability.INGEST_DOCUMENT.toolName()),
                capabilityRegistry.getSupplementalTools("skill:capture-knowledge-note"));
        assertEquals(
                Optional.of("skill:capture-knowledge-note"),
                skillAwareIntentDetector.findSkillMatch("Summarize and ingest this document"));
        assertTrue(
                skillAwareIntentDetector.findSkillMatch("Research this article").isEmpty());
        assertTrue(skillAwareIntentDetector
                .findSkillMatch("Summarize this document")
                .isEmpty());
        assertTrue(
                skillAwareIntentDetector.findSkillMatch("Ingest this document").isEmpty());
        assertTrue(skillAwareIntentDetector
                .findSkillMatch("How do I summarize and ingest this document?")
                .isEmpty());
        assertTrue(skillAwareIntentDetector
                .findSkillMatch("What is a knowledge note?")
                .isEmpty());
        assertTrue(skillAwareIntentDetector
                .findSkillMatch("Summarize and save this article to a file")
                .isEmpty());
        assertTrue(skillAwareIntentDetector
                .findSkillMatch("Save this report to memory and summarize it")
                .isPresent());
    }
}
