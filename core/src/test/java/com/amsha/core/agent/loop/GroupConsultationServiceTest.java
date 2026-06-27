package com.amsha.core.agent.loop;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;

import com.amsha.core.agent.role.AgentGroupRegistry;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class GroupConsultationServiceTest {

    private GroupConsultationService service;
    private AgentGroupRegistry registry;

    @BeforeEach
    void setUp() {
        registry = new AgentGroupRegistry();
        service = new GroupConsultationService();
        service.groupRegistry = registry;
        service.taskClassifier = new TaskClassifierService();
    }

    @Test
    void testIsGroupLead() {
        assertTrue(service.isGroupLead("debugger"));
        assertTrue(service.isGroupLead("coder"));
        assertFalse(service.isGroupLead("optimizer"));
    }

    @Test
    void testTriagePureTask() {
        // Pure debugging should be self-handled
        GroupConsultationService.TriageResult result =
                service.triage("debugger", "Fix NullPointerException in UserService line 42");
        assertTrue(result.canSelfHandle());
        assertTrue(result.recommendedMembers().isEmpty());
    }

    @Test
    void testTriageConsultationTask() {
        // Debugging + complex recursion should consult optimizer (Ganesha)
        GroupConsultationService.TriageResult result =
                service.triage("debugger", "Fix recursive traversal stack overflow and optimize the logic");
        assertFalse(result.canSelfHandle());
        assertTrue(result.recommendedMembers().contains("optimizer"));
        assertNotNull(result.consultationPrompt());
        assertTrue(result.consultationPrompt().contains("Ganesha"));
    }

    @Test
    void testTriageSecurityConsultationTask() {
        // Debugging + security vulnerability should consult sentinel (Kartikeya)
        GroupConsultationService.TriageResult result =
                service.triage("debugger", "Remediate SQL injection vulnerability in search query");
        assertFalse(result.canSelfHandle());
        assertTrue(result.recommendedMembers().contains("sentinel"));
        assertNotNull(result.consultationPrompt());
        assertTrue(result.consultationPrompt().contains("Kartikeya"));
    }

    @Test
    void testBuildConsultationPromptInvalidRole() {
        var group = registry.findById("kailasa").get();
        // Check that invalid role ID "invalid_role" doesn't throw exception and is printed gracefully
        String prompt = service.buildConsultationPrompt(group, "Goal", List.of("optimizer", "invalid_role"));
        assertNotNull(prompt);
        assertTrue(prompt.contains("Ganesha"));
        assertTrue(prompt.contains("invalid_role"));
    }

    @Test
    void testTaskClassifierDeterministicOrder() {
        TaskClassifierService classifier = service.taskClassifier;

        // "implement" (coding) appears first, "security" appears second
        List<TaskClassifierService.TaskDomain> domains1 =
                classifier.classify("implement the new feature and fix security issues");
        assertEquals(2, domains1.size());
        assertEquals(TaskClassifierService.TaskDomain.CODING, domains1.get(0));
        assertEquals(TaskClassifierService.TaskDomain.SECURITY, domains1.get(1));

        // "vulnerability" (security) appears first, "refactor" (coding) appears second
        List<TaskClassifierService.TaskDomain> domains2 =
                classifier.classify("remediate security vulnerability and refactor the code");
        assertEquals(2, domains2.size());
        assertEquals(TaskClassifierService.TaskDomain.SECURITY, domains2.get(0));
        assertEquals(TaskClassifierService.TaskDomain.CODING, domains2.get(1));
    }

    @Test
    void testTriageWithDynamicFallback_SingleDomain() {
        // Single domain should self-handle
        GroupConsultationService.TriageResult result =
                service.triageWithDynamicFallback("coder", "write java code for user registration");
        assertTrue(result.canSelfHandle());
        assertTrue(result.recommendedMembers().isEmpty());
    }

    @Test
    void testTriageWithDynamicFallback_MultiDomain() {
        // Multi domain (coding + security) should form dynamic group and trigger consultation
        GroupConsultationService.TriageResult result = service.triageWithDynamicFallback(
                "coder", "implement user login and check for security injection vulnerabilities");
        assertFalse(result.canSelfHandle());
        assertTrue(result.recommendedMembers().contains("sentinel"));
        assertNotNull(result.consultationPrompt());
        assertTrue(result.consultationPrompt().contains("sentinel"));
    }
}
