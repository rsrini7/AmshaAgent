package com.amsha.core.agent.loop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import com.amsha.core.agent.loop.TaskClassifierService.TaskDomain;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

public class TaskClassifierServiceTest {

    private TaskClassifierService service;

    @BeforeEach
    public void setUp() {
        service = new TaskClassifierService();
    }

    @Test
    public void classifyReturnsEmptyForNullOrBlank() {
        assertTrue(service.classify(null).isEmpty());
        assertTrue(service.classify("   ").isEmpty());
        assertTrue(service.classify("").isEmpty());
    }

    @Test
    public void classifyFindsSingleDomain() {
        List<TaskDomain> domains = service.classify("please debug this stack trace");
        assertEquals(List.of(TaskDomain.DEBUGGING), domains);
    }

    @Test
    public void classifyOrdersDomainsByFirstKeywordOccurrence() {
        // "security" appears at index 0, "bug" later -> security leads debugging
        List<TaskDomain> domains = service.classify("security review of a bug report");
        assertEquals(2, domains.size());
        assertEquals(TaskDomain.SECURITY, domains.getFirst());
        assertEquals(TaskDomain.DEBUGGING, domains.get(1));
    }

    @Test
    public void classifyUnknownTextYieldsNoDomains() {
        assertTrue(service.classify("say hello to the user").isEmpty());
    }

    @Test
    public void rolesForDomainsMapsAndDeduplicates() {
        List<String> roles =
                service.rolesForDomains(List.of(TaskDomain.DEBUGGING, TaskDomain.CODING, TaskDomain.DEBUGGING));
        assertEquals(List.of("debugger", "coder"), roles);
        assertTrue(service.rolesForDomains(List.of()).isEmpty());
        assertTrue(service.rolesForDomains(null).isEmpty());
    }

    @Test
    public void determineLeadUsesFirstDomain() {
        assertEquals("sentinel", service.determineLead(List.of(TaskDomain.SECURITY, TaskDomain.CODING)));
        assertNull(service.determineLead(List.of()));
        assertNull(service.determineLead(null));
    }

    @Test
    public void formDynamicGroupBuildsOrderedGroupForMultiDomainGoal() {
        var group = service.formDynamicGroup("security vulnerability in the new feature code");
        assertTrue(group != null && group.memberRoleIds().containsAll(List.of("sentinel", "coder")));
        assertEquals("sentinel", group.leadRoleId());
        assertTrue(group.groupId().startsWith("dynamic-"));
    }

    @Test
    public void formDynamicGroupReturnsNullForSingleDomain() {
        assertNull(service.formDynamicGroup("debug the stack trace"));
        assertNull(service.formDynamicGroup(""));
    }
}
