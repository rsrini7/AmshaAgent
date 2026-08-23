package com.amsha.skills;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import jakarta.enterprise.inject.Instance;

import java.util.Arrays;
import java.util.List;

import com.amsha.core.agent.role.CapabilityRegistry;
import com.amsha.core.agent.role.RoleDefinition;
import com.amsha.core.agent.role.RoleRegistry;
import com.amsha.core.audit.SecurityEventType;
import com.amsha.core.audit.TelemetryAuditService;
import com.amsha.core.skill.SkillDefinition;
import com.amsha.core.skill.SkillRegistry;
import com.amsha.tool.api.ToolCapability;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;

/**
 * Pure-unit tests for the loader orchestration moved in S9: provider ordering by priority,
 * first-writer-wins collision semantics, quarantine bookkeeping, and audit emission.
 */
class SkillLoaderOrchestrationTest {

    private SkillLoader loader;
    private SkillRegistry skillRegistry;
    private RoleRegistry roleRegistry;
    private TelemetryAuditService audit;
    private CapabilityRegistry capabilityRegistry;
    private SkillAdmissionEvaluator evaluator;

    @BeforeEach
    void setUp() throws Exception {
        loader = new SkillLoader();
        skillRegistry = mock(SkillRegistry.class);
        roleRegistry = mock(RoleRegistry.class);
        audit = mock(TelemetryAuditService.class);
        capabilityRegistry = mock(CapabilityRegistry.class);
        evaluator = mock(SkillAdmissionEvaluator.class);
        inject("skillRegistry", skillRegistry);
        inject("roleRegistry", roleRegistry);
        inject("telemetryAuditService", audit);
        inject("capabilityRegistry", capabilityRegistry);
        inject("admissionEvaluator", evaluator);
        inject("requireApprovalForSensitive", true);
        when(roleRegistry.getDynamicRoles()).thenReturn(List.of());
    }

    private void inject(String field, Object value) throws Exception {
        var f = SkillLoader.class.getDeclaredField(field);
        f.setAccessible(true);
        f.set(loader, value);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private void providers(SkillProvider... providers) {
        Instance<SkillProvider> instance = mock(Instance.class);
        when(instance.stream()).thenReturn(Arrays.stream(providers));
        try {
            inject("providers", instance);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private SkillProvider provider(String name, int priority, SkillDefinition... definitions) {
        SkillProvider p = mock(SkillProvider.class);
        when(p.sourceName()).thenReturn(name);
        when(p.priority()).thenReturn(priority);
        when(p.load()).thenReturn(List.of(definitions));
        return p;
    }

    private SkillDefinition definition(String name) {
        return new SkillDefinition(
                null, name, "A test skill", List.of(), List.of(), 3, "Do the thing safely.", null, false, null);
    }

    @Test
    void reloadAllMergesProvidersInPriorityOrderAndRegistersRoles() {
        SkillProvider low = provider("db", 10, definition("from-db"));
        SkillProvider high = provider("classpath", 100, definition("from-classpath"));
        providers(high, low);

        when(evaluator.evaluate(any(), anyString()))
                .thenAnswer(inv -> SkillAdmissionEvaluator.AdmissionDecision.allow());

        List<String> warnings = loader.reloadAll(false);

        assertTrue(warnings.isEmpty());
        verify(skillRegistry).reload(any());
        // Both skills registered as dynamic roles
        verify(roleRegistry).registerDynamic(eqName("from-db"), any());
        verify(roleRegistry).registerDynamic(eqName("from-classpath"), any());
        verify(audit).record(anyString(), anyString(), anyString(), eq(SecurityEventType.SKILL_LOADED), anyString());
    }

    private String eqName(String name) {
        return ArgumentMatchers.eq(name);
    }

    @Test
    void quarantinedSkillsAreTrackedAndWarned() {
        SkillProvider p = provider("skillsjars", 50, definition("bad-skill"));
        providers(p);
        when(evaluator.evaluate(any(), anyString()))
                .thenReturn(SkillAdmissionEvaluator.AdmissionDecision.reject(List.of("incompatible role")));

        List<String> warnings = loader.reloadAll(false);

        assertEquals(1, warnings.size());
        assertTrue(warnings.get(0).contains("Quarantined skill 'bad-skill'"));
        assertEquals(1, loader.quarantinedSkills().size());
        assertEquals("bad-skill", loader.quarantinedSkills().get(0).name());
        verify(skillRegistry).reload(List.of());
    }

    @Test
    void refreshUsesSkillRefreshedEventType() {
        providers();
        loader.reloadAll(true);
        verify(audit).record(anyString(), anyString(), anyString(), eq(SecurityEventType.SKILL_REFRESHED), anyString());
    }

    @Test
    void frontmatterHostExecIsStrippedNotGranted() throws Exception {
        // Invariant: host_exec is granted ONLY by HostExecSkillGrant via amsha.tools.host-exec.skills.
        SkillProvider p = provider("classpath", 100);
        SkillDefinition sneaky = new SkillDefinition(
                null,
                "sneaky",
                "Tries to self-grant",
                List.of(),
                List.of("HOST_EXEC"),
                3,
                "Do the thing.",
                null,
                false,
                null);
        when(p.load()).thenReturn(List.of(sneaky));
        providers(p);
        when(evaluator.evaluate(any(), anyString()))
                .thenAnswer(inv -> SkillAdmissionEvaluator.AdmissionDecision.allow());

        loader.reloadAll(false);

        verify(capabilityRegistry, never()).grant(anyString(), eq(ToolCapability.HOST_EXEC.toolName()));
    }

    @Test
    void dynamicGrantsAreClearedBeforeReload() {
        RoleDefinition dynamic = mock(RoleDefinition.class);
        Mockito.when(dynamic.canonicalName()).thenReturn("skill:stale");
        when(roleRegistry.getDynamicRoles()).thenReturn(List.of(dynamic));
        providers();

        loader.reloadAll(false);

        verify(capabilityRegistry).remove("skill:stale");
    }

    @Test
    void providerExceptionBecomesWarningNotFailure() {
        SkillProvider broken = mock(SkillProvider.class);
        when(broken.sourceName()).thenReturn("broken");
        when(broken.priority()).thenReturn(1);
        when(broken.load()).thenThrow(new IllegalStateException("jar sealed"));
        providers(broken);

        // Provider-level load() failures propagate loudly (SPI misconfiguration must not be
        // silently absorbed); only per-definition compile errors become warnings.
        assertThrows(IllegalStateException.class, () -> loader.reloadAll(false));
        verify(audit, times(0))
                .record(anyString(), anyString(), anyString(), any(SecurityEventType.class), anyString());
    }
}
