package com.arcclaw.app.routing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.regex.Pattern;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.arcclaw.core.agent.role.RoleDefinition;
import com.arcclaw.core.audit.TelemetryAuditService;
import com.arcclaw.core.skill.CompiledSkillEntry;
import com.arcclaw.core.skill.SkillDefinition;
import com.arcclaw.core.skill.SkillRegistry;

import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;

@QuarkusTest
class SkillAwareIntentClassifierTest {

    @InjectMock
    SkillRegistry skillRegistry;

    @InjectMock
    IntentClassifierService fallbackService;

    @InjectMock
    TelemetryAuditService telemetryAuditService;

    @Inject
    SkillAwareIntentClassifier classifier;

    @BeforeEach
    void setup() {
    }

    @Test
    void testFastPathRoutesToSkillAndBypassesLLM() {
        SkillDefinition def = new SkillDefinition("id", "code-reviewer", "desc", List.of(), List.of(), 5, "p");
        RoleDefinition roleDefinition = new com.arcclaw.core.agent.role.RoleDefinition(
                "skill:code-reviewer", "Code Reviewer", "desc", 5, false, java.util.Set.of());
        CompiledSkillEntry entry = new CompiledSkillEntry(def, new Pattern[] { Pattern.compile(".*review.*code.*") },
                roleDefinition, "db");

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
    void testNullOrBlankInputFallsBackToLLM() {
        when(fallbackService.classify(null)).thenReturn("coordinator");
        when(fallbackService.classify("   ")).thenReturn("coordinator");

        assertEquals("coordinator", classifier.classify(null));
        assertEquals("coordinator", classifier.classify("   "));

        verify(fallbackService, times(1)).classify(null);
        verify(fallbackService, times(1)).classify("   ");
    }
}
