package com.amsha.core.tool.attempt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import com.amsha.persistence.api.ActionLockScope;
import com.amsha.tool.api.ToolCapability;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ToolTargetResolverTest {

    private ToolTargetResolver resolver;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        resolver = new ToolTargetResolver(objectMapper);
    }

    @Test
    @DisplayName("Tools addressing the same resource produce identical targetFingerprint regardless of tool name")
    void testSameResourceAcrossToolsProducesSameFingerprint() {
        Map<String, Object> appendParams = Map.of("block_key", "user_profile", "content", "Senior dev");
        Map<String, Object> replaceParams = Map.of("block_key", "user_profile", "content", "Staff dev");
        Map<String, Object> readParams = Map.of("block_key", "user_profile");

        ToolTargetResolution appendRes =
                resolver.resolve("ws-1", "sess-1", ToolCapability.CORE_MEMORY_APPEND.toolName(), appendParams);
        ToolTargetResolution replaceRes =
                resolver.resolve("ws-1", "sess-1", ToolCapability.CORE_MEMORY_REPLACE.toolName(), replaceParams);
        ToolTargetResolution readRes =
                resolver.resolve("ws-1", "sess-1", ToolCapability.CORE_MEMORY_READ.toolName(), readParams);

        assertEquals(ActionLockScope.TARGET_RESOURCE, appendRes.scope());
        assertEquals(ActionLockScope.TARGET_RESOURCE, replaceRes.scope());
        assertEquals(ActionLockScope.TARGET_RESOURCE, readRes.scope());

        assertTrue(appendRes.isTargetResource());
        assertEquals("core_memory", appendRes.resourceType());
        assertEquals("user_profile", appendRes.resourceIdentity());

        assertNotNull(appendRes.targetFingerprint());
        assertTrue(appendRes.targetFingerprint().startsWith(ToolTargetResolver.TARGET_PREFIX));

        // Invariance across different tool names targeting the same resource
        assertEquals(appendRes.targetFingerprint(), replaceRes.targetFingerprint());
        assertEquals(appendRes.targetFingerprint(), readRes.targetFingerprint());
    }

    @Test
    @DisplayName("Target fingerprint isolates different workspaces and sessions")
    void testTargetFingerprintIsolation() {
        Map<String, Object> params = Map.of("block_key", "project_context");

        ToolTargetResolution res1 =
                resolver.resolve("ws-1", "sess-1", ToolCapability.CORE_MEMORY_APPEND.toolName(), params);
        ToolTargetResolution resDifferentWs =
                resolver.resolve("ws-2", "sess-1", ToolCapability.CORE_MEMORY_APPEND.toolName(), params);
        ToolTargetResolution resDifferentSess =
                resolver.resolve("ws-1", "sess-2", ToolCapability.CORE_MEMORY_APPEND.toolName(), params);

        assertNotNull(res1.targetFingerprint());
        assertNotNull(resDifferentWs.targetFingerprint());
        assertNotNull(resDifferentSess.targetFingerprint());

        assertFalse(res1.targetFingerprint().equals(resDifferentWs.targetFingerprint()));
        assertFalse(res1.targetFingerprint().equals(resDifferentSess.targetFingerprint()));
    }

    @Test
    @DisplayName("Tool absent from table falls back to SESSION_MUTATIONS with null targetFingerprint")
    void testAbsentToolFallsBackToSessionMutations() {
        Map<String, Object> params = Map.of("code", "echo 1", "language", "bash");
        ToolTargetResolution res = resolver.resolve("ws-1", "sess-1", ToolCapability.SANDBOX_EXEC.toolName(), params);

        assertEquals(ActionLockScope.SESSION_MUTATIONS, res.scope());
        assertNull(res.targetFingerprint());
        assertNull(res.resourceType());
        assertNull(res.resourceIdentity());
        assertFalse(res.isTargetResource());
    }

    @Test
    @DisplayName("Missing target parameter falls back to SESSION_MUTATIONS")
    void testMissingParameterFallsBackToSessionMutations() {
        Map<String, Object> paramsMissingKey = Map.of("content", "hello");
        ToolTargetResolution res =
                resolver.resolve("ws-1", "sess-1", ToolCapability.CORE_MEMORY_APPEND.toolName(), paramsMissingKey);

        assertEquals(ActionLockScope.SESSION_MUTATIONS, res.scope());
        assertNull(res.targetFingerprint());
    }

    @Test
    @DisplayName("Blank or empty target parameter falls back to SESSION_MUTATIONS")
    void testBlankParameterFallsBackToSessionMutations() {
        Map<String, Object> blankParams = Map.of("block_key", "   ");
        ToolTargetResolution res =
                resolver.resolve("ws-1", "sess-1", ToolCapability.CORE_MEMORY_APPEND.toolName(), blankParams);

        assertEquals(ActionLockScope.SESSION_MUTATIONS, res.scope());
        assertNull(res.targetFingerprint());

        Map<String, Object> emptyParams = Map.of("block_key", "");
        ToolTargetResolution resEmpty =
                resolver.resolve("ws-1", "sess-1", ToolCapability.CORE_MEMORY_APPEND.toolName(), emptyParams);

        assertEquals(ActionLockScope.SESSION_MUTATIONS, resEmpty.scope());
        assertNull(resEmpty.targetFingerprint());
    }

    @Test
    @DisplayName("Malformed/non-string target parameter falls back to SESSION_MUTATIONS")
    void testMalformedParameterFallsBackToSessionMutations() {
        Map<String, Object> numberParams = Map.of("block_key", 12345);
        ToolTargetResolution resNum =
                resolver.resolve("ws-1", "sess-1", ToolCapability.CORE_MEMORY_APPEND.toolName(), numberParams);

        assertEquals(ActionLockScope.SESSION_MUTATIONS, resNum.scope());
        assertNull(resNum.targetFingerprint());

        Map<String, Object> listParams = Map.of("block_key", List.of("invalid"));
        ToolTargetResolution resList =
                resolver.resolve("ws-1", "sess-1", ToolCapability.CORE_MEMORY_APPEND.toolName(), listParams);

        assertEquals(ActionLockScope.SESSION_MUTATIONS, resList.scope());
        assertNull(resList.targetFingerprint());
    }

    @Test
    @DisplayName("Null parameter map falls back to SESSION_MUTATIONS")
    void testNullParametersFallBackToSessionMutations() {
        ToolTargetResolution res = resolver.resolve(
                "ws-1", "sess-1", ToolCapability.CORE_MEMORY_APPEND.toolName(), (Map<String, Object>) null);

        assertEquals(ActionLockScope.SESSION_MUTATIONS, res.scope());
        assertNull(res.targetFingerprint());
    }

    @Test
    @DisplayName(
            "Core memory alias-only 'block' parameter falls back to SESSION_MUTATIONS since tools require block_key")
    void testCoreMemoryAliasOnlyBlockFallsBackToSessionMutations() {
        Map<String, Object> params = Map.of("block", "user_profile");
        ToolTargetResolution res =
                resolver.resolve("ws-1", "sess-1", ToolCapability.CORE_MEMORY_APPEND.toolName(), params);

        assertEquals(ActionLockScope.SESSION_MUTATIONS, res.scope());
        assertNull(res.targetFingerprint());
        assertNull(res.resourceType());
        assertNull(res.resourceIdentity());
    }

    @Test
    @DisplayName("Core memory matching block and block_key aliases resolves cleanly")
    void testCoreMemoryMatchingBlockAliasResolves() {
        Map<String, Object> params = Map.of("block_key", "user_profile", "block", "user_profile");
        ToolTargetResolution res =
                resolver.resolve("ws-1", "sess-1", ToolCapability.CORE_MEMORY_APPEND.toolName(), params);

        assertEquals(ActionLockScope.TARGET_RESOURCE, res.scope());
        assertEquals("core_memory", res.resourceType());
        assertEquals("user_profile", res.resourceIdentity());
        assertNotNull(res.targetFingerprint());
    }

    @Test
    @DisplayName("Core memory conflicting block and block_key aliases fall back to SESSION_MUTATIONS")
    void testCoreMemoryConflictingBlockAliasFallsBack() {
        Map<String, Object> params = Map.of("block_key", "user_profile", "block", "system");
        ToolTargetResolution res =
                resolver.resolve("ws-1", "sess-1", ToolCapability.CORE_MEMORY_APPEND.toolName(), params);

        assertEquals(ActionLockScope.SESSION_MUTATIONS, res.scope());
        assertNull(res.targetFingerprint());
    }

    @Test
    @DisplayName("Malformed aliases fall back to SESSION_MUTATIONS")
    void testMalformedAliasesFallBack() {
        ToolTargetResolution memoryRes = resolver.resolve(
                "ws-1",
                "sess-1",
                ToolCapability.CORE_MEMORY_APPEND.toolName(),
                Map.of("block_key", "user_profile", "block", 42));
        Path taskPath = Path.of("./data/workspaces/tasks/2026-11-03/task.md")
                .toAbsolutePath()
                .normalize();
        ToolTargetResolution taskRes = resolver.resolve(
                "ws-1",
                "sess-1",
                ToolCapability.AGENT_TASK.toolName(),
                Map.of("action", "get", "taskId", taskPath.toString(), "path", 42));
        ToolTargetResolution scheduleRes = resolver.resolve(
                "ws-1",
                "sess-1",
                ToolCapability.MANAGE_SCHEDULE.toolName(),
                Map.of("action", "delete", "cronId", "cron-1", "cron_id", 42));

        assertEquals(ActionLockScope.SESSION_MUTATIONS, memoryRes.scope());
        assertNull(memoryRes.targetFingerprint());
        assertEquals(ActionLockScope.SESSION_MUTATIONS, taskRes.scope());
        assertNull(taskRes.targetFingerprint());
        assertEquals(ActionLockScope.SESSION_MUTATIONS, scheduleRes.scope());
        assertNull(scheduleRes.targetFingerprint());
    }

    @Test
    @DisplayName("Agent task tool relative identifier falls back to SESSION_MUTATIONS")
    void testAgentTaskRelativeIdentifierFallsBack() {
        Map<String, Object> params = Map.of("action", "transition", "taskId", "TASK-42", "newStatus", "completed");
        ToolTargetResolution res = resolver.resolve("ws-1", "sess-1", ToolCapability.AGENT_TASK.toolName(), params);

        assertEquals(ActionLockScope.SESSION_MUTATIONS, res.scope());
        assertNull(res.targetFingerprint());
    }

    @Test
    @DisplayName("Agent task tool resolves real task absolute path constrained to tasksRoot")
    void testAgentTaskRealAbsolutePathResolves() {
        Path tasksRoot = Path.of("./data/workspaces/tasks").toAbsolutePath().normalize();
        Path realPath = tasksRoot.resolve("2026-11-03/123456-my-task.md");
        Map<String, Object> params = Map.of("action", "get", "taskId", realPath.toString());
        ToolTargetResolution res = resolver.resolve("ws-1", "sess-1", ToolCapability.AGENT_TASK.toolName(), params);

        assertEquals(ActionLockScope.TARGET_RESOURCE, res.scope());
        assertEquals("agent_task", res.resourceType());
        assertEquals(realPath.toString(), res.resourceIdentity());
        assertNotNull(res.targetFingerprint());
    }

    @Test
    @DisplayName("Agent task symlink path falls back to SESSION_MUTATIONS")
    void testAgentTaskSymlinkPathFallsBack(@TempDir Path tempDir) throws IOException {
        Path tasksRoot = tempDir.resolve("tasks");
        Files.createDirectories(tasksRoot);
        Path outsideTask = tempDir.resolve("outside.md");
        Files.writeString(outsideTask, "task");
        Path symlinkTask = tasksRoot.resolve("linked.md");
        Files.createSymbolicLink(symlinkTask, outsideTask);

        ToolTargetResolver tempResolver = new ToolTargetResolver(objectMapper, tempDir.toString());
        Map<String, Object> params = Map.of("action", "update", "taskId", symlinkTask.toString(), "body", "updated");
        ToolTargetResolution res = tempResolver.resolve("ws-1", "sess-1", ToolCapability.AGENT_TASK.toolName(), params);

        assertEquals(ActionLockScope.SESSION_MUTATIONS, res.scope());
        assertNull(res.targetFingerprint());
    }

    @Test
    @DisplayName("Agent task tool relative path falls back to SESSION_MUTATIONS")
    void testAgentTaskRelativePathFallsBack() {
        Map<String, Object> params =
                Map.of("action", "transition", "taskId", "2026-11-03/123456-my-task.md", "newStatus", "in_progress");
        ToolTargetResolution res = resolver.resolve("ws-1", "sess-1", ToolCapability.AGENT_TASK.toolName(), params);

        assertEquals(ActionLockScope.SESSION_MUTATIONS, res.scope());
        assertNull(res.targetFingerprint());
    }

    @Test
    @DisplayName("Agent task path escaping tasksRoot falls back to SESSION_MUTATIONS")
    void testAgentTaskEscapingTaskRootFallsBack() {
        Map<String, Object> params = Map.of("action", "get", "taskId", "/etc/passwd.md");
        ToolTargetResolution res = resolver.resolve("ws-1", "sess-1", ToolCapability.AGENT_TASK.toolName(), params);

        assertEquals(ActionLockScope.SESSION_MUTATIONS, res.scope());
        assertNull(res.targetFingerprint());
    }

    @Test
    @DisplayName("Agent task path traversal escaping tasksRoot falls back to SESSION_MUTATIONS")
    void testAgentTaskTraversalEscapingTaskRootFallsBack() {
        Map<String, Object> params = Map.of("action", "get", "taskId", "../../secret.md");
        ToolTargetResolution res = resolver.resolve("ws-1", "sess-1", ToolCapability.AGENT_TASK.toolName(), params);

        assertEquals(ActionLockScope.SESSION_MUTATIONS, res.scope());
        assertNull(res.targetFingerprint());
    }

    @Test
    @DisplayName("Agent task non-markdown file falls back to SESSION_MUTATIONS")
    void testAgentTaskNonMdFileFallsBack() {
        Map<String, Object> params = Map.of("action", "get", "taskId", "2026-11-03/not-a-task.txt");
        ToolTargetResolution res = resolver.resolve("ws-1", "sess-1", ToolCapability.AGENT_TASK.toolName(), params);

        assertEquals(ActionLockScope.SESSION_MUTATIONS, res.scope());
        assertNull(res.targetFingerprint());
    }

    @Test
    @DisplayName("Agent task create action ignores taskId and falls back to SESSION_MUTATIONS")
    void testAgentTaskCreateFallsBackToSessionMutations() {
        Map<String, Object> params =
                Map.of("action", "create", "name", "New Task", "taskId", "TASK-42", "description", "details");
        ToolTargetResolution res = resolver.resolve("ws-1", "sess-1", ToolCapability.AGENT_TASK.toolName(), params);

        assertEquals(ActionLockScope.SESSION_MUTATIONS, res.scope());
        assertNull(res.targetFingerprint());
        assertNull(res.resourceType());
        assertNull(res.resourceIdentity());
    }

    @Test
    @DisplayName("Agent task list action falls back to SESSION_MUTATIONS")
    void testAgentTaskListFallsBackToSessionMutations() {
        Map<String, Object> params = Map.of("action", "list");
        ToolTargetResolution res = resolver.resolve("ws-1", "sess-1", ToolCapability.AGENT_TASK.toolName(), params);

        assertEquals(ActionLockScope.SESSION_MUTATIONS, res.scope());
        assertNull(res.targetFingerprint());
    }

    @Test
    @DisplayName("Agent task conflicting taskId and task_id aliases fall back to SESSION_MUTATIONS as ambiguous input")
    void testAgentTaskAmbiguousAliasesFallBack() {
        Path tasksRoot = Path.of("./data/workspaces/tasks").toAbsolutePath().normalize();
        String canonicalTaskId = tasksRoot.resolve("2026-11-03/task-a.md").toString();
        String aliasTaskId = tasksRoot.resolve("2026-11-03/task-b.md").toString();
        Map<String, Object> params = Map.of(
                "action", "transition", "taskId", canonicalTaskId, "task_id", aliasTaskId, "newStatus", "in_progress");
        ToolTargetResolution res = resolver.resolve("ws-1", "sess-1", ToolCapability.AGENT_TASK.toolName(), params);

        assertEquals(ActionLockScope.SESSION_MUTATIONS, res.scope());
        assertNull(res.targetFingerprint());
    }

    @Test
    @DisplayName("Agent task missing canonical taskId falls back to SESSION_MUTATIONS even if task_id alias is present")
    void testAgentTaskMissingCanonicalFieldFallsBack() {
        Map<String, Object> params = Map.of("action", "transition", "task_id", "TASK-1", "newStatus", "in_progress");
        ToolTargetResolution res = resolver.resolve("ws-1", "sess-1", ToolCapability.AGENT_TASK.toolName(), params);

        assertEquals(ActionLockScope.SESSION_MUTATIONS, res.scope());
        assertNull(res.targetFingerprint());
    }

    @Test
    @DisplayName("Agent task matching alias taskId and task_id resolves cleanly")
    void testAgentTaskMatchingAliasResolves() {
        Path tasksRoot = Path.of("./data/workspaces/tasks").toAbsolutePath().normalize();
        String taskId = tasksRoot.resolve("2026-11-03/task-a.md").toString();
        Map<String, Object> params = Map.of("action", "get", "taskId", taskId, "task_id", taskId);
        ToolTargetResolution res = resolver.resolve("ws-1", "sess-1", ToolCapability.AGENT_TASK.toolName(), params);

        assertEquals(ActionLockScope.TARGET_RESOURCE, res.scope());
        assertEquals("agent_task", res.resourceType());
        assertEquals(taskId, res.resourceIdentity());
        assertNotNull(res.targetFingerprint());
    }

    @Test
    @DisplayName("Agent task unknown action falls back to SESSION_MUTATIONS")
    void testAgentTaskUnknownActionFallsBack() {
        Map<String, Object> params = Map.of("action", "unknown_op", "taskId", "TASK-1");
        ToolTargetResolution res = resolver.resolve("ws-1", "sess-1", ToolCapability.AGENT_TASK.toolName(), params);

        assertEquals(ActionLockScope.SESSION_MUTATIONS, res.scope());
        assertNull(res.targetFingerprint());
    }

    @Test
    @DisplayName("Memory block keys preserve case sensitivity per filesystem store invariants")
    void testCaseSensitivityPreserved() {
        Map<String, Object> paramsLower = Map.of("block_key", "user_profile");
        Map<String, Object> paramsUpper = Map.of("block_key", "User_Profile");

        ToolTargetResolution resLower =
                resolver.resolve("ws-1", "sess-1", ToolCapability.CORE_MEMORY_APPEND.toolName(), paramsLower);
        ToolTargetResolution resUpper =
                resolver.resolve("ws-1", "sess-1", ToolCapability.CORE_MEMORY_APPEND.toolName(), paramsUpper);

        assertEquals("user_profile", resLower.resourceIdentity());
        assertEquals("User_Profile", resUpper.resourceIdentity());

        assertFalse(
                resLower.targetFingerprint().equals(resUpper.targetFingerprint()),
                "Distinct case block keys must produce distinct target fingerprints");
    }

    @Test
    @DisplayName("Schedule management tool resolves to schedule target resource on delete action")
    void testScheduleResolution() {
        Map<String, Object> params = Map.of("action", "delete", "cronId", "cron-uuid-99");
        ToolTargetResolution res =
                resolver.resolve("ws-1", "sess-1", ToolCapability.MANAGE_SCHEDULE.toolName(), params);

        assertEquals(ActionLockScope.TARGET_RESOURCE, res.scope());
        assertEquals("schedule", res.resourceType());
        assertEquals("cron-uuid-99", res.resourceIdentity());
        assertNotNull(res.targetFingerprint());
    }

    @Test
    @DisplayName("Schedule management list action ignores cronId and falls back to SESSION_MUTATIONS")
    void testScheduleListIgnoresCronIdAndFallsBackToSessionMutations() {
        Map<String, Object> params = Map.of("action", "list", "cronId", "cron-uuid-99");
        ToolTargetResolution res =
                resolver.resolve("ws-1", "sess-1", ToolCapability.MANAGE_SCHEDULE.toolName(), params);

        assertEquals(ActionLockScope.SESSION_MUTATIONS, res.scope());
        assertNull(res.targetFingerprint());
        assertNull(res.resourceType());
        assertNull(res.resourceIdentity());
    }

    @Test
    @DisplayName(
            "Schedule management delete with conflicting cron_id alias falls back to SESSION_MUTATIONS as ambiguous input")
    void testScheduleDeleteAmbiguousCronIdAliasesFallBack() {
        Map<String, Object> params = Map.of("action", "delete", "cronId", "cron-1", "cron_id", "cron-2");
        ToolTargetResolution res =
                resolver.resolve("ws-1", "sess-1", ToolCapability.MANAGE_SCHEDULE.toolName(), params);

        assertEquals(ActionLockScope.SESSION_MUTATIONS, res.scope());
        assertNull(res.targetFingerprint());
    }

    @Test
    @DisplayName(
            "Schedule management delete with conflicting taskId alias falls back to SESSION_MUTATIONS as ambiguous input")
    void testScheduleDeleteAmbiguousTaskIdAliasFallsBack() {
        Map<String, Object> params = Map.of("action", "delete", "cronId", "cron-1", "taskId", "task-3");
        ToolTargetResolution res =
                resolver.resolve("ws-1", "sess-1", ToolCapability.MANAGE_SCHEDULE.toolName(), params);

        assertEquals(ActionLockScope.SESSION_MUTATIONS, res.scope());
        assertNull(res.targetFingerprint());
    }

    @Test
    @DisplayName("Schedule management delete with matching alias resolves cleanly")
    void testScheduleDeleteMatchingAliasResolves() {
        Map<String, Object> params = Map.of("action", "delete", "cronId", "cron-1", "cron_id", "cron-1");
        ToolTargetResolution res =
                resolver.resolve("ws-1", "sess-1", ToolCapability.MANAGE_SCHEDULE.toolName(), params);

        assertEquals(ActionLockScope.TARGET_RESOURCE, res.scope());
        assertEquals("schedule", res.resourceType());
        assertEquals("cron-1", res.resourceIdentity());
        assertNotNull(res.targetFingerprint());
    }

    @Test
    @DisplayName(
            "Schedule management delete missing canonical cronId falls back to SESSION_MUTATIONS even if alias is present")
    void testScheduleDeleteMissingCanonicalCronIdFallsBack() {
        Map<String, Object> params = Map.of("action", "delete", "cron_id", "cron-1");
        ToolTargetResolution res =
                resolver.resolve("ws-1", "sess-1", ToolCapability.MANAGE_SCHEDULE.toolName(), params);

        assertEquals(ActionLockScope.SESSION_MUTATIONS, res.scope());
        assertNull(res.targetFingerprint());
    }

    @Test
    @DisplayName("Schedule management delete with malformed whitespace cronId falls back to SESSION_MUTATIONS")
    void testScheduleDeleteMalformedWhitespaceCronIdFallsBack() {
        Map<String, Object> params = Map.of("action", "delete", "cronId", "cron id with spaces");
        ToolTargetResolution res =
                resolver.resolve("ws-1", "sess-1", ToolCapability.MANAGE_SCHEDULE.toolName(), params);

        assertEquals(ActionLockScope.SESSION_MUTATIONS, res.scope());
        assertNull(res.targetFingerprint());
    }

    @Test
    @DisplayName("Schedule management delete with malformed newline cronId falls back to SESSION_MUTATIONS")
    void testScheduleDeleteMalformedNewlineCronIdFallsBack() {
        Map<String, Object> params = Map.of("action", "delete", "cronId", "cron-1\n");
        ToolTargetResolution res =
                resolver.resolve("ws-1", "sess-1", ToolCapability.MANAGE_SCHEDULE.toolName(), params);

        assertEquals(ActionLockScope.SESSION_MUTATIONS, res.scope());
        assertNull(res.targetFingerprint());
    }

    @Test
    @DisplayName("Schedule management delete with malformed path traversal cronId falls back to SESSION_MUTATIONS")
    void testScheduleDeleteMalformedTraversalCronIdFallsBack() {
        Map<String, Object> params = Map.of("action", "delete", "cronId", "../cron-1");
        ToolTargetResolution res =
                resolver.resolve("ws-1", "sess-1", ToolCapability.MANAGE_SCHEDULE.toolName(), params);

        assertEquals(ActionLockScope.SESSION_MUTATIONS, res.scope());
        assertNull(res.targetFingerprint());
    }

    @Test
    @DisplayName("Schedule management delete with non-textual cronId falls back to SESSION_MUTATIONS")
    void testScheduleDeleteNonTextualCronIdFallsBack() {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("action", "delete");
        node.put("cronId", 12345);

        ToolTargetResolution res = resolver.resolve("ws-1", "sess-1", ToolCapability.MANAGE_SCHEDULE.toolName(), node);

        assertEquals(ActionLockScope.SESSION_MUTATIONS, res.scope());
        assertNull(res.targetFingerprint());
    }

    @Test
    @DisplayName("Schedule management unknown action falls back to SESSION_MUTATIONS")
    void testScheduleUnknownActionFallsBack() {
        Map<String, Object> params = Map.of("action", "restart", "cronId", "cron-uuid-99");
        ToolTargetResolution res =
                resolver.resolve("ws-1", "sess-1", ToolCapability.MANAGE_SCHEDULE.toolName(), params);

        assertEquals(ActionLockScope.SESSION_MUTATIONS, res.scope());
        assertNull(res.targetFingerprint());
    }

    @Test
    @DisplayName("Schedule management missing action falls back to SESSION_MUTATIONS")
    void testScheduleMissingActionFallsBack() {
        Map<String, Object> params = Map.of("cronId", "cron-uuid-99");
        ToolTargetResolution res =
                resolver.resolve("ws-1", "sess-1", ToolCapability.MANAGE_SCHEDULE.toolName(), params);

        assertEquals(ActionLockScope.SESSION_MUTATIONS, res.scope());
        assertNull(res.targetFingerprint());
    }

    @Test
    @DisplayName("Checklist tool has no top-level resource ID and falls back to SESSION_MUTATIONS")
    void testChecklistFallsBackToSessionMutations() {
        Map<String, Object> params = Map.of("checkList", List.of(Map.of("content", "task 1", "status", "pending")));
        ToolTargetResolution res =
                resolver.resolve("ws-1", "sess-1", ToolCapability.MANAGE_CHECKLIST.toolName(), params);

        assertEquals(ActionLockScope.SESSION_MUTATIONS, res.scope());
        assertNull(res.targetFingerprint());
        assertNull(res.resourceType());
        assertNull(res.resourceIdentity());
        assertFalse(res.isTargetResource());
    }

    @Test
    @DisplayName("JsonNode and Map overloads yield identical target resolutions")
    void testMapAndJsonNodeParity() {
        Map<String, Object> params = Map.of("block_key", "profile");
        ObjectNode node = objectMapper.createObjectNode().put("block_key", "profile");

        ToolTargetResolution mapRes =
                resolver.resolve("ws-1", "sess-1", ToolCapability.CORE_MEMORY_APPEND.toolName(), params);
        ToolTargetResolution nodeRes =
                resolver.resolve("ws-1", "sess-1", ToolCapability.CORE_MEMORY_APPEND.toolName(), node);

        assertEquals(mapRes.scope(), nodeRes.scope());
        assertEquals(mapRes.targetFingerprint(), nodeRes.targetFingerprint());
        assertEquals(mapRes.resourceType(), nodeRes.resourceType());
        assertEquals(mapRes.resourceIdentity(), nodeRes.resourceIdentity());
    }

    @Test
    @DisplayName("Invalid or blank workspaceId, sessionId, or toolName throws IllegalArgumentException")
    void testInvalidIdentifiersThrow() {
        Map<String, Object> params = Map.of("block_key", "profile");

        assertThrows(IllegalArgumentException.class, () -> resolver.resolve(null, "s", "tool", params));
        assertThrows(IllegalArgumentException.class, () -> resolver.resolve("", "s", "tool", params));
        assertThrows(IllegalArgumentException.class, () -> resolver.resolve("   ", "s", "tool", params));

        assertThrows(IllegalArgumentException.class, () -> resolver.resolve("w", null, "tool", params));
        assertThrows(IllegalArgumentException.class, () -> resolver.resolve("w", "", "tool", params));
        assertThrows(IllegalArgumentException.class, () -> resolver.resolve("w", "   ", "tool", params));

        assertThrows(IllegalArgumentException.class, () -> resolver.resolve("w", "s", null, params));
        assertThrows(IllegalArgumentException.class, () -> resolver.resolve("w", "s", "", params));
        assertThrows(IllegalArgumentException.class, () -> resolver.resolve("w", "s", "   ", params));
    }
}
