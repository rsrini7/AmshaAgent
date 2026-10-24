package com.amsha.core.routing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import jakarta.inject.Inject;

import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

import com.amsha.core.agent.role.RoleDefinition;
import com.amsha.core.audit.TelemetryAuditService;
import com.amsha.core.skill.CompiledSkillEntry;
import com.amsha.core.skill.SkillDefinition;
import com.amsha.core.skill.SkillRegistry;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;

@QuarkusTest
@SuppressWarnings("java:S1192")
class SkillAwareIntentDetectorTest {

    @InjectMock
    SkillRegistry skillRegistry;

    @InjectMock
    IntentDetectorService fallbackService;

    @InjectMock
    TelemetryAuditService telemetryAuditService;

    @Inject
    SkillAwareIntentDetector classifier;

    @BeforeEach
    void setup() {
        // No shared state to initialize — mocks injected per-test
    }

    @Test
    void testFastPathRoutesToSkillAndBypassesLLM() {
        SkillDefinition def = new SkillDefinition("id", "code-reviewer", "desc", List.of(), List.of(), 5, "p", null);
        RoleDefinition roleDefinition =
                new RoleDefinition("skill:code-reviewer", "Code Reviewer", "desc", 5, false, false, Set.of(), Set.of());
        CompiledSkillEntry entry =
                new CompiledSkillEntry(def, List.of(Pattern.compile(".*review.*code.*")), roleDefinition, "db");

        when(skillRegistry.currentSnapshot()).thenReturn(List.of(entry));

        String role = classifier.classify("review my code");

        assertEquals("skill:code-reviewer", role);
        verify(fallbackService, never()).classify(anyString());
    }

    @Test
    void testNoMatchFallsBackToLLM() {
        when(skillRegistry.currentSnapshot()).thenReturn(List.of());
        when(fallbackService.classify("what is the weather")).thenReturn("coordinator");

        String role = classifier.classify("what is the weather");

        assertEquals("coordinator", role);
        verify(fallbackService, times(1)).classify("what is the weather");
    }

    @Test
    void testNameMatchRoutesSkillWithZeroTriggerPatterns() {
        SkillDefinition def = new SkillDefinition("id2", "remote-browser", "desc", List.of(), List.of(), 5, "p", null);
        RoleDefinition rd = new RoleDefinition(
                "skill:remote-browser", "Remote Browser", "desc", 5, false, false, Set.of(), Set.of());
        CompiledSkillEntry entry = new CompiledSkillEntry(def, List.of(), rd, "skillsjars");

        when(skillRegistry.currentSnapshot()).thenReturn(List.of(entry));

        String role = classifier.classify("use remote-browser to navigate to https://example.com");

        assertEquals("skill:remote-browser", role);
        verify(fallbackService, never()).classify(anyString());
    }

    @Test
    void testNameMatchIsCaseInsensitive() {
        SkillDefinition def = new SkillDefinition("id3", "Remote-Browser", "desc", List.of(), List.of(), 5, "p", null);
        RoleDefinition rd = new RoleDefinition(
                "skill:remote-browser", "Remote Browser", "desc", 5, false, false, Set.of(), Set.of());
        CompiledSkillEntry entry = new CompiledSkillEntry(def, List.of(), rd, "skillsjars");

        when(skillRegistry.currentSnapshot()).thenReturn(List.of(entry));

        String role = classifier.classify("delegate to Remote-Browser to get page title");

        assertEquals("skill:Remote-Browser", role);
        verify(fallbackService, never()).classify(anyString());
    }

    @Test
    void testNameMatchFuzzyHyphenSpace() {
        SkillDefinition def = new SkillDefinition("id4", "algorithmic-art", "desc", List.of(), List.of(), 5, "p", null);
        RoleDefinition rd = new RoleDefinition(
                "skill:algorithmic-art", "Algorithmic Art", "desc", 5, false, false, Set.of(), Set.of());
        CompiledSkillEntry entry = new CompiledSkillEntry(def, List.of(), rd, "skillsjars");

        when(skillRegistry.currentSnapshot()).thenReturn(List.of(entry));

        String role = classifier.classify("use algorithmic art to generate particle art");

        assertEquals("skill:algorithmic-art", role);
        verify(fallbackService, never()).classify(anyString());
    }

    @Test
    void testNameMatchToleratesSingleCharacterTypoWhenSkillIsNamed() {
        SkillDefinition def =
                new SkillDefinition("id5", "capture-knowledge-note", "desc", List.of(), List.of(), 5, "p", null);
        RoleDefinition rd = new RoleDefinition(
                "skill:capture-knowledge-note", "Capture Knowledge Note", "desc", 5, false, false, Set.of(), Set.of());
        CompiledSkillEntry entry = new CompiledSkillEntry(def, List.of(), rd, "classpath");

        when(skillRegistry.currentSnapshot()).thenReturn(List.of(entry));

        String role = classifier.classify("use capture-knowledge-not skill and provide details");

        assertEquals("skill:capture-knowledge-note", role);
        verify(fallbackService, never()).classify(anyString());
    }

    @Test
    void testFuzzyNameMatchRequiresSkillMention() {
        SkillDefinition def =
                new SkillDefinition("id6", "capture-knowledge-note", "desc", List.of(), List.of(), 5, "p", null);
        RoleDefinition rd = new RoleDefinition(
                "skill:capture-knowledge-note", "Capture Knowledge Note", "desc", 5, false, false, Set.of(), Set.of());
        CompiledSkillEntry entry = new CompiledSkillEntry(def, List.of(), rd, "classpath");

        when(skillRegistry.currentSnapshot()).thenReturn(List.of(entry));
        when(fallbackService.classify(anyString())).thenReturn("coordinator");

        String role = classifier.classify("capture-knowledge-not was saved");

        assertEquals("coordinator", role);
        verify(fallbackService, times(1)).classify("capture-knowledge-not was saved");
    }

    @Test
    void testAmbiguousFuzzyNameMatchFallsBackToLLM() {
        SkillDefinition note =
                new SkillDefinition("id7", "capture-knowledge-note", "desc", List.of(), List.of(), 5, "p", null);
        RoleDefinition noteRole = new RoleDefinition(
                "skill:capture-knowledge-note", "Capture Knowledge Note", "desc", 5, false, false, Set.of(), Set.of());
        CompiledSkillEntry noteEntry = new CompiledSkillEntry(note, List.of(), noteRole, "classpath");

        SkillDefinition nots =
                new SkillDefinition("id8", "capture-knowledge-nots", "desc", List.of(), List.of(), 5, "p", null);
        RoleDefinition notsRole = new RoleDefinition(
                "skill:capture-knowledge-nots", "Capture Knowledge Nots", "desc", 5, false, false, Set.of(), Set.of());
        CompiledSkillEntry notsEntry = new CompiledSkillEntry(nots, List.of(), notsRole, "classpath");

        when(skillRegistry.currentSnapshot()).thenReturn(List.of(noteEntry, notsEntry));
        when(fallbackService.classify(anyString())).thenReturn("coordinator");

        String role = classifier.classify("use capture-knowledge-not skill and provide details");

        assertEquals("coordinator", role);
        verify(fallbackService, times(1)).classify("use capture-knowledge-not skill and provide details");
    }

    @Test
    void testNullOrBlankInputFallsBackToLLM() {
        when(fallbackService.classify(null)).thenReturn("coordinator");
        when(fallbackService.classify("   ")).thenReturn("coordinator");

        assertEquals("coordinator", classifier.classify(null));
        assertEquals("coordinator", classifier.classify("   "));

        verify(fallbackService, times(1)).classify(null);
        verify(fallbackService, times(1)).classify("   ");
    }
}
