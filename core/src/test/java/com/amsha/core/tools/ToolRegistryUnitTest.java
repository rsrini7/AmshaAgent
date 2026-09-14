package com.amsha.core.tools;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import dev.langchain4j.model.chat.request.json.JsonObjectSchema;

import jakarta.enterprise.event.Event;
import jakarta.enterprise.inject.Instance;
import jakarta.enterprise.inject.spi.BeanManager;

import java.nio.file.Path;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;

import com.amsha.core.agent.role.AgentRoleEnum;
import com.amsha.core.audit.AuditService;
import com.amsha.core.audit.ComplianceViolationException;
import com.amsha.core.audit.SecurityEventType;
import com.amsha.core.scope.WorkspaceContext;
import com.amsha.core.skill.SkillRegistry;
import com.amsha.core.tool.DynamicToolInvoker;
import com.amsha.security.api.AutonomyLevel;
import com.amsha.security.api.ToolOperation;
import com.amsha.tool.api.AgentTool;
import com.amsha.tool.api.DynamicToolDescriptor;
import com.amsha.tool.api.ToolCapability;
import com.amsha.tool.api.ToolCategory;
import com.amsha.tool.api.ToolExecutionContext;
import com.amsha.tool.api.ToolResponse;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ToolRegistryUnitTest {

    @TempDir
    Path tempDir;

    private ToolRegistry registry;
    private ToolDiscoveryService discoveryService;
    private ToolGovernanceGuard governanceGuard;
    private ToolSecretExtractor secretExtractor;
    private AuditService auditService;
    private Event<ToolDiscoveryCompleted> toolDiscoveryCompleted;
    private SkillRegistry skillRegistry;
    private Instance<DynamicToolInvoker> dynamicToolInvokers;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setup() {
        registry = new ToolRegistry();
        discoveryService = mock(ToolDiscoveryService.class);
        governanceGuard = mock(ToolGovernanceGuard.class);
        secretExtractor = mock(ToolSecretExtractor.class);
        auditService = mock(AuditService.class);
        toolDiscoveryCompleted = mock(Event.class);
        skillRegistry = mock(SkillRegistry.class);
        dynamicToolInvokers = mock(Instance.class);

        registry.discoveryService = discoveryService;
        registry.governanceGuard = governanceGuard;
        registry.secretExtractor = secretExtractor;
        registry.auditService = auditService;
        registry.toolDiscoveryCompleted = toolDiscoveryCompleted;
        registry.skillRegistry = skillRegistry;
        registry.dynamicToolInvokers = dynamicToolInvokers;
        registry.objectMapper = new ObjectMapper();
        // Redirect the sandbox workspace off the repo tree: invokeSandboxed() creates directories.
        registry.workspaceRootDir = tempDir.toString();
        when(dynamicToolInvokers.isUnsatisfied()).thenReturn(true);
    }

    /**
     * Wires a single dynamic invoker into the CDI {@link Instance} mock.
     * <p>
     * BOTH access styles the registry uses must be stubbed, because they are compiled
     * differently:
     * <ul>
     *   <li>{@code listTools()} / {@code listToolDescriptors()} call {@code forEach(Consumer)};</li>
     *   <li>{@code invokeTool()} uses an enhanced for-loop, which javac compiles to
     *       {@code iterator()} + {@code hasNext()} + {@code next()}.</li>
     * </ul>
     * Stubbing only {@code forEach} is silently insufficient for the {@code invokeTool} path:
     * Mockito 5.7.0's {@code ReturnsEmptyValues} maps the {@code Iterable} return type to an empty
     * collection but has no case for {@code Iterator}, so an unstubbed {@code iterator()} returns
     * <b>null</b> and the for-loop dies with an NPE instead of falling through to the "unknown tool"
     * branch. A fresh iterator is supplied per call because the loop consumes it.
     */
    private void withDynamicInvoker(DynamicToolInvoker invoker) {
        when(dynamicToolInvokers.isUnsatisfied()).thenReturn(false);
        doAnswer(inv -> {
                    Consumer<DynamicToolInvoker> consumer = inv.getArgument(0);
                    consumer.accept(invoker);
                    return null;
                })
                .when(dynamicToolInvokers)
                .forEach(any());
        doAnswer(inv -> List.of(invoker).iterator()).when(dynamicToolInvokers).iterator();
    }

    private static DynamicToolDescriptor descriptor(String name, ToolOperation op) {
        return new DynamicToolDescriptor(name, name + " description", "{}", ToolCategory.INTERNAL, false, op);
    }

    /** Minimal direct-execution tool that records what the registry handed it. */
    private static final class CapturingTool implements AgentTool {
        private final String name;
        private ToolExecutionContext capturedContext;
        private Map<String, Object> capturedParams;

        CapturingTool(String name) {
            this.name = name;
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public String description() {
            return name + " description";
        }

        @Override
        public ToolOperation requiredOperation() {
            return ToolOperation.READ_FS;
        }

        @Override
        public boolean requiresSandbox() {
            return false;
        }

        @Override
        public JsonObjectSchema parametersSchema() {
            // null => the registry skips schema-based param stripping.
            return null;
        }

        @Override
        public ToolResponse execute(ToolExecutionContext ctx, Map<String, Object> params) {
            this.capturedContext = ctx;
            this.capturedParams = new HashMap<>(params);
            return ToolResponse.success("ok");
        }
    }

    private void register(AgentTool tool) {
        when(discoveryService.getToolIndex()).thenReturn(Map.of(tool.name(), tool));
    }

    @Test
    void testToSafeDirName() {
        assertEquals("session_123", ToolRegistry.toSafeDirName("session:123"));
        assertEquals("safe-session", ToolRegistry.toSafeDirName("safe-session"));
    }

    @Test
    void testListToolsAndFindTool() {
        AgentTool mockTool = mock(AgentTool.class);
        when(mockTool.name()).thenReturn("my_tool");
        when(discoveryService.getToolIndex()).thenReturn(Map.of("my_tool", mockTool));

        assertEquals(1, registry.listTools().size());
        assertEquals("my_tool", registry.listTools().get(0));
        assertEquals("my_tool", registry.registeredToolNames());
        assertTrue(registry.findTool("my_tool").isPresent());
        assertFalse(registry.findTool("unknown").isPresent());
    }

    @Test
    void testHeadroomToolCallIntercepted() {
        ToolResponse resp1 = registry.invokeTool("headroom_search", Map.of());
        assertNotNull(resp1);
        assertTrue(resp1.success());

        ToolResponse resp2 = registry.invokeTool("headroom", Map.of());
        assertNotNull(resp2);
        assertTrue(resp2.success());
    }

    @Test
    void testInvokeUnknownToolThrows() {
        when(discoveryService.getToolIndex()).thenReturn(Map.of());
        assertThrows(IllegalArgumentException.class, () -> registry.invokeTool("unknown_tool", Map.of()));
    }

    @Test
    void testInvokeKnownToolSuccess() {
        AgentTool mockTool = mock(AgentTool.class);
        when(mockTool.name()).thenReturn("simple_tool");
        when(mockTool.requiresSandbox()).thenReturn(false);
        when(mockTool.requiredOperation()).thenReturn(ToolOperation.READ_FS);
        when(mockTool.execute(any(), any())).thenReturn(ToolResponse.success("ok"));

        when(discoveryService.getToolIndex()).thenReturn(Map.of("simple_tool", mockTool));

        ToolResponse resp = registry.invokeTool("simple_tool", Map.of("p", "v"));
        assertNotNull(resp);
        assertTrue(resp.success());
        assertEquals("ok", resp.output());
    }

    @Test
    void testInitializeFiresEvent() {
        when(discoveryService.getToolIndex()).thenReturn(Map.of("t1", mock(AgentTool.class)));
        registry.initialize();
        verify(discoveryService).discover();
        verify(toolDiscoveryCompleted).fire(any());
    }

    @Test
    void testInvokeSandboxedSanitizedInfraFailure() {
        AgentTool mockTool = mock(AgentTool.class);
        when(mockTool.name()).thenReturn("sandboxed_tool");
        when(mockTool.requiresSandbox()).thenReturn(true);
        when(mockTool.requiredOperation()).thenReturn(ToolOperation.EXECUTE_SHELL);

        when(discoveryService.getToolIndex()).thenReturn(Map.of("sandboxed_tool", mockTool));
        when(secretExtractor.extractRequiredSecrets(any(), any())).thenReturn(Map.of());

        // Without sandbox port
        registry.beanManager = mock(BeanManager.class);
        when(registry.beanManager.getBeans(Object.class)).thenReturn(Collections.emptySet());

        ToolResponse resp = registry.invokeTool("sandboxed_tool", Map.of());
        assertNotNull(resp);
        assertFalse(resp.success());
        assertEquals("Sandbox execution environment is currently unavailable.", resp.output());
    }

    @Test
    void testInvokeToolGovernanceFailures() {
        AgentTool mockTool = mock(AgentTool.class);
        when(mockTool.name()).thenReturn("guarded_tool");
        when(mockTool.requiredOperation()).thenReturn(ToolOperation.EXECUTE_SHELL);
        when(discoveryService.getToolIndex()).thenReturn(Map.of("guarded_tool", mockTool));

        doThrow(new SecurityException("Role forbidden"))
                .when(governanceGuard)
                .verifyRoleCapability(any(), eq("guarded_tool"));

        ToolResponse resp1 = registry.invokeTool("guarded_tool", Map.of());
        assertFalse(resp1.success());
        assertTrue(resp1.output().contains("Role forbidden"));

        reset(governanceGuard);
        doThrow(new IllegalArgumentException("Autonomy policy denied"))
                .when(governanceGuard)
                .verifyAutonomy(any(), eq("guarded_tool"), any(), any());

        ToolResponse resp2 = registry.invokeTool("guarded_tool", Map.of());
        assertFalse(resp2.success());
        assertTrue(resp2.output().contains("Autonomy policy denied"));
    }

    @Test
    void testInvokeToolWithNullStrippingAndSchemaFiltering() {
        AgentTool mockTool = mock(AgentTool.class);
        when(mockTool.name()).thenReturn("schema_tool");
        when(mockTool.requiresSandbox()).thenReturn(false);
        when(mockTool.shouldStripNullParams()).thenReturn(true);
        when(mockTool.requiredOperation()).thenReturn(ToolOperation.READ_FS);

        JsonObjectSchema schema =
                JsonObjectSchema.builder().addStringProperty("allowed").build();
        when(mockTool.parametersSchema()).thenReturn(schema);

        when(mockTool.execute(any(), any())).thenAnswer(inv -> {
            Map<String, Object> map = inv.getArgument(1);
            return ToolResponse.success("keys=" + map.keySet());
        });

        when(discoveryService.getToolIndex()).thenReturn(Map.of("schema_tool", mockTool));

        Map<String, Object> input = new HashMap<>();
        input.put("allowed", "val");
        input.put("hallucinated", "extra");
        input.put("null_key", null);

        ToolResponse resp = registry.invokeTool("schema_tool", input);
        assertTrue(resp.success());
        assertEquals("keys=[allowed]", resp.output());
    }

    @Test
    void testInvokeDirectExecutionException() {
        AgentTool mockTool = mock(AgentTool.class);
        when(mockTool.name()).thenReturn("failing_tool");
        when(mockTool.requiresSandbox()).thenReturn(false);
        when(mockTool.requiredOperation()).thenReturn(ToolOperation.READ_FS);
        when(mockTool.execute(any(), any())).thenThrow(new RuntimeException("Execution crash"));

        when(discoveryService.getToolIndex()).thenReturn(Map.of("failing_tool", mockTool));

        ToolResponse resp = registry.invokeTool("failing_tool", Map.of());
        assertNotNull(resp);
        assertFalse(resp.success());
    }

    // ---------- dynamic (MCP/plugin) tool dispatch ----------

    @Test
    void testListToolsIncludesDynamicInvokerNames() {
        AgentTool builtin = mock(AgentTool.class);
        when(builtin.name()).thenReturn("builtin");
        when(discoveryService.getToolIndex()).thenReturn(Map.of("builtin", builtin));

        DynamicToolInvoker invoker = mock(DynamicToolInvoker.class);
        when(invoker.listTools()).thenReturn(List.of(descriptor("remote", ToolOperation.READ_FS)));
        withDynamicInvoker(invoker);

        assertEquals(List.of("builtin", "remote"), registry.listTools());
    }

    @Test
    void testListToolsDoesNotConsultDynamicInvokersWhenUnsatisfied() {
        when(discoveryService.getToolIndex()).thenReturn(Map.of());

        registry.listTools();

        verify(dynamicToolInvokers, never()).forEach(any());
    }

    @Test
    void testListToolDescriptorsMergesCacheAndDynamicInvokers() {
        when(discoveryService.getDescriptorCache())
                .thenReturn(Map.of("builtin", new ToolRegistry.ToolDescriptor("builtin", "d", "{}")));

        DynamicToolInvoker invoker = mock(DynamicToolInvoker.class);
        when(invoker.listTools()).thenReturn(List.of(descriptor("remote", ToolOperation.READ_FS)));
        withDynamicInvoker(invoker);

        List<ToolRegistry.ToolDescriptor> descriptors = registry.listToolDescriptors();

        assertEquals(2, descriptors.size());
        assertTrue(descriptors.stream().anyMatch(d -> d.name().equals("builtin")));
        assertTrue(descriptors.stream().anyMatch(d -> d.name().equals("remote")));
    }

    @Test
    void testInvokeToolDelegatesToDynamicInvoker() {
        when(discoveryService.getToolIndex()).thenReturn(Map.of());

        DynamicToolInvoker invoker = mock(DynamicToolInvoker.class);
        when(invoker.findTool("remote")).thenReturn(Optional.of(descriptor("remote", ToolOperation.READ_FS)));
        when(invoker.invoke(eq("remote"), any(), any())).thenReturn(ToolResponse.success("from remote"));
        withDynamicInvoker(invoker);

        ToolResponse resp = registry.invokeTool("remote", Map.of());

        assertEquals("from remote", resp.output());
        verify(governanceGuard).verifyAutonomy(any(), eq("remote"), eq(ToolOperation.READ_FS), any());
        verify(auditService).record(any(), any(), any(), eq(SecurityEventType.TOOL_INVOKED), anyString());
    }

    @Test
    void testInvokeToolDynamicPathReturns403WhenGovernanceDenies() {
        when(discoveryService.getToolIndex()).thenReturn(Map.of());

        DynamicToolInvoker invoker = mock(DynamicToolInvoker.class);
        when(invoker.findTool("remote")).thenReturn(Optional.of(descriptor("remote", ToolOperation.EXECUTE_SHELL)));
        withDynamicInvoker(invoker);
        doThrow(new SecurityException("shell not permitted"))
                .when(governanceGuard)
                .verifyAutonomy(any(), eq("remote"), any(), any());

        ToolResponse resp = registry.invokeTool("remote", Map.of());

        assertEquals(403, resp.exitCode());
        assertTrue(resp.output().contains("shell not permitted"));
        verify(invoker, never()).invoke(anyString(), any(), any());
    }

    @Test
    void testInvokeToolFallsThroughToUnknownWhenDynamicInvokerHasNoMatch() {
        when(discoveryService.getToolIndex()).thenReturn(Map.of());

        DynamicToolInvoker invoker = mock(DynamicToolInvoker.class);
        when(invoker.findTool("ghost")).thenReturn(Optional.empty());
        withDynamicInvoker(invoker);

        assertThrows(IllegalArgumentException.class, () -> registry.invokeTool("ghost", Map.of()));
    }

    // ---------- audit behaviour ----------

    @Test
    void testAuditNotRecordedWhenGovernanceDenies() {
        AgentTool mockTool = mock(AgentTool.class);
        when(mockTool.name()).thenReturn("guarded_tool");
        when(mockTool.requiredOperation()).thenReturn(ToolOperation.EXECUTE_SHELL);
        when(discoveryService.getToolIndex()).thenReturn(Map.of("guarded_tool", mockTool));
        doThrow(new SecurityException("denied")).when(governanceGuard).verifyRoleCapability(any(), eq("guarded_tool"));

        registry.invokeTool("guarded_tool", Map.of());

        // A denied invocation must not be audited as TOOL_INVOKED - it never ran.
        verify(auditService, never()).record(any(), any(), any(), any(), anyString());
    }

    @Test
    void testFailureAuditRecordedForToolFailureResponse() {
        AgentTool mockTool = mock(AgentTool.class);
        when(mockTool.name()).thenReturn("failing");
        when(mockTool.requiresSandbox()).thenReturn(false);
        when(mockTool.requiredOperation()).thenReturn(ToolOperation.READ_FS);
        when(mockTool.parametersSchema()).thenReturn(null);
        when(mockTool.execute(any(), any())).thenReturn(ToolResponse.failure(2, "bad input"));
        when(discoveryService.getToolIndex()).thenReturn(Map.of("failing", mockTool));

        ToolResponse resp = registry.invokeTool("failing", Map.of());

        assertEquals(2, resp.exitCode());
        verify(governanceGuard).recordToolFailureAudit(any(), eq("failing"), any());
    }

    // ---------- parameter sanitisation edge cases ----------

    @Test
    void testNullParamsPreservedWhenToolOptsOut() {
        AgentTool mockTool = mock(AgentTool.class);
        when(mockTool.name()).thenReturn("keep_nulls");
        when(mockTool.requiresSandbox()).thenReturn(false);
        when(mockTool.shouldStripNullParams()).thenReturn(false);
        when(mockTool.requiredOperation()).thenReturn(ToolOperation.READ_FS);
        when(mockTool.parametersSchema()).thenReturn(null);
        when(mockTool.execute(any(), any())).thenAnswer(inv -> {
            Map<String, Object> map = inv.getArgument(1);
            return ToolResponse.success("hasNull=" + map.containsKey("explicitNull"));
        });
        when(discoveryService.getToolIndex()).thenReturn(Map.of("keep_nulls", mockTool));

        Map<String, Object> input = new HashMap<>();
        input.put("explicitNull", null);

        ToolResponse resp = registry.invokeTool("keep_nulls", input);

        assertEquals("hasNull=true", resp.output());
    }

    @Test
    void testNoStrippingWhenSchemaHasNoProperties() {
        AgentTool mockTool = mock(AgentTool.class);
        when(mockTool.name()).thenReturn("free_form");
        when(mockTool.requiresSandbox()).thenReturn(false);
        when(mockTool.requiredOperation()).thenReturn(ToolOperation.READ_FS);
        when(mockTool.parametersSchema()).thenReturn(JsonObjectSchema.builder().build());
        when(mockTool.execute(any(), any())).thenAnswer(inv -> {
            Map<String, Object> map = inv.getArgument(1);
            return ToolResponse.success("keys=" + map.keySet());
        });
        when(discoveryService.getToolIndex()).thenReturn(Map.of("free_form", mockTool));

        ToolResponse resp = registry.invokeTool("free_form", Map.of("anything", "goes"));

        // An empty schema means "no declared constraint", so nothing is stripped.
        assertEquals("keys=[anything]", resp.output());
    }

    // ---------- headroom interception boundaries ----------

    @Test
    void testHeadroomInterceptionDoesNotMatchSubstrings() {
        when(discoveryService.getToolIndex()).thenReturn(Map.of());

        // Only the exact name or the prefix are intercepted; a name merely containing "headroom" is not.
        assertThrows(IllegalArgumentException.class, () -> registry.invokeTool("my_headroom", Map.of()));
    }

    // ---------- exception handling ----------

    /** Named to match ArC's proxy wrapper, which handleExecutionException unwraps by simple name. */
    static class InvocationException extends RuntimeException {
        InvocationException(Throwable cause) {
            super("proxy wrapper", cause);
        }
    }

    @Test
    void testArcInvocationExceptionIsUnwrappedToRootCause() {
        AgentTool mockTool = mock(AgentTool.class);
        when(mockTool.name()).thenReturn("wrapped_tool");
        when(mockTool.requiresSandbox()).thenReturn(false);
        when(mockTool.requiredOperation()).thenReturn(ToolOperation.READ_FS);
        when(mockTool.execute(any(), any()))
                .thenThrow(new InvocationException(new IllegalStateException("real root cause")));
        when(discoveryService.getToolIndex()).thenReturn(Map.of("wrapped_tool", mockTool));

        ToolResponse resp = registry.invokeTool("wrapped_tool", Map.of());

        assertFalse(resp.success());
        assertTrue(resp.output().contains("real root cause"), "root cause must surface, got: " + resp.output());
        assertFalse(resp.output().contains("proxy wrapper"), "the ArC wrapper message must be discarded");
    }

    @Test
    void testComplianceViolationIsRethrownNotSwallowed() {
        AgentTool mockTool = mock(AgentTool.class);
        when(mockTool.name()).thenReturn("violating_tool");
        when(mockTool.requiresSandbox()).thenReturn(false);
        when(mockTool.requiredOperation()).thenReturn(ToolOperation.READ_FS);
        when(mockTool.execute(any(), any()))
                .thenThrow(new ComplianceViolationException(
                        "s1", SecurityEventType.TOOL_INVOKED, new IllegalStateException("policy breach")));
        when(discoveryService.getToolIndex()).thenReturn(Map.of("violating_tool", mockTool));

        // Fail-closed: a compliance breach must abort the loop rather than degrade to a tool failure.
        assertThrows(ComplianceViolationException.class, () -> registry.invokeTool("violating_tool", Map.of()));
    }

    @Test
    void testMissingGhCliProducesFriendlyGithubMessage() {
        AgentTool mockTool = mock(AgentTool.class);
        when(mockTool.name()).thenReturn(ToolCapability.GITHUB.toolName());
        when(mockTool.requiresSandbox()).thenReturn(false);
        when(mockTool.requiredOperation()).thenReturn(ToolOperation.READ_FS);
        when(mockTool.execute(any(), any()))
                .thenThrow(new IllegalStateException("Cannot run program \"gh\": error=2, No such file"));
        when(discoveryService.getToolIndex()).thenReturn(Map.of(ToolCapability.GITHUB.toolName(), mockTool));

        ToolResponse resp = registry.invokeTool(ToolCapability.GITHUB.toolName(), Map.of());

        assertTrue(resp.output().contains("GitHub integration is not available"), "got: " + resp.output());
        assertFalse(resp.output().startsWith("EXECUTION_FAILED"));
    }

    @Test
    void testMissingGhCliOnNonGithubToolKeepsGenericMessage() {
        AgentTool mockTool = mock(AgentTool.class);
        when(mockTool.name()).thenReturn("some_other_tool");
        when(mockTool.requiresSandbox()).thenReturn(false);
        when(mockTool.requiredOperation()).thenReturn(ToolOperation.READ_FS);
        when(mockTool.execute(any(), any())).thenThrow(new IllegalStateException("Cannot run program \"gh\""));
        when(discoveryService.getToolIndex()).thenReturn(Map.of("some_other_tool", mockTool));

        ToolResponse resp = registry.invokeTool("some_other_tool", Map.of());

        assertTrue(resp.output().startsWith("EXECUTION_FAILED: "));
        assertEquals("TOOL_EXECUTION_FAILED", resp.errorCode());
    }

    // ---------- JSON overload ----------

    @Test
    void testInvokeToolJsonOverloadParsesParameters() {
        CapturingTool tool = new CapturingTool("json_tool");
        register(tool);

        registry.invokeTool("json_tool", "{\"key\": \"value\"}", "sess-1");

        assertEquals(Map.of("key", "value"), tool.capturedParams);
    }

    @Test
    void testInvokeToolJsonOverloadRejectsMalformedJson() {
        when(discoveryService.getToolIndex()).thenReturn(Map.of());

        IllegalArgumentException ex = assertThrows(
                IllegalArgumentException.class, () -> registry.invokeTool("json_tool", "{not json", "sess-1"));

        assertTrue(ex.getMessage().contains("Failed to deserialize tool parameters for json_tool"));
    }

    // ---------- context construction ----------

    @Test
    void testExecutionContextFallsBackToDefaultsWhenScopesUnbound() {
        CapturingTool tool = new CapturingTool("ctx_tool");
        register(tool);

        registry.invokeTool("ctx_tool", Map.of());

        assertEquals("system", tool.capturedContext.sessionId());
        assertEquals("system", tool.capturedContext.chatId());
        assertEquals(AgentRoleEnum.COORDINATOR.id(), tool.capturedContext.agentRole());
        assertEquals(AutonomyLevel.SUPERVISED, tool.capturedContext.autonomyLevel());
        assertNull(tool.capturedContext.skillHome());
        assertFalse(tool.capturedContext.browserPreview());
    }

    @Test
    void testExecutionContextPropagatesBoundScopedValues() {
        CapturingTool tool = new CapturingTool("ctx_tool");
        register(tool);

        ScopedValue.where(WorkspaceContext.SESSION_ID, "sess-42")
                .where(WorkspaceContext.CHAT_ID, "chat-9")
                .where(WorkspaceContext.AGENT_ROLE, AgentRoleEnum.COORDINATOR.id())
                .where(WorkspaceContext.AUTONOMY, AutonomyLevel.FULL)
                .where(WorkspaceContext.CHANNEL_TYPE, "telegram")
                .run(() -> registry.invokeTool("ctx_tool", Map.of()));

        assertEquals("sess-42", tool.capturedContext.sessionId());
        assertEquals("chat-9", tool.capturedContext.chatId());
        assertEquals(AutonomyLevel.FULL, tool.capturedContext.autonomyLevel());
        assertEquals("telegram", tool.capturedContext.channelType());
    }

    @Test
    void testSkillRoleResolvesSkillHomeAndBrowserPreview() {
        CapturingTool tool = new CapturingTool("ctx_tool");
        register(tool);

        ScopedValue.where(WorkspaceContext.AGENT_ROLE, AgentRoleEnum.SKILL_PREFIX + "reporting")
                .run(() -> registry.invokeTool("ctx_tool", Map.of()));

        // The mocked registry holds no such skill, so the lookup happens and yields null/false.
        // Called TWICE, not once: createExecutionContext() resolves skillHome and browserPreview
        // independently, and each resolver does its own findByName(skillName) lookup.
        verify(skillRegistry, times(2)).findByName("reporting");
        assertNull(tool.capturedContext.skillHome());
        assertFalse(tool.capturedContext.browserPreview());
    }

    @Test
    void testNonSkillRoleDoesNotConsultSkillRegistry() {
        CapturingTool tool = new CapturingTool("ctx_tool");
        register(tool);

        ScopedValue.where(WorkspaceContext.AGENT_ROLE, AgentRoleEnum.COORDINATOR.id())
                .run(() -> registry.invokeTool("ctx_tool", Map.of()));

        verify(skillRegistry, never()).findByName(anyString());
        assertNull(tool.capturedContext.skillHome());
    }

    // ---------- discovery lifecycle ----------

    @Test
    void testInitializeFiresEventWithDiscoveredCount() {
        AgentTool a = mock(AgentTool.class);
        when(a.name()).thenReturn("a");
        AgentTool b = mock(AgentTool.class);
        when(b.name()).thenReturn("b");
        when(discoveryService.getToolIndex()).thenReturn(Map.of("a", a, "b", b));

        registry.initialize();

        verify(toolDiscoveryCompleted).fire(new ToolDiscoveryCompleted(2));
    }

    @Test
    void testRegisteredToolNamesEmptyWhenNoTools() {
        when(discoveryService.getToolIndex()).thenReturn(Map.of());

        assertEquals("", registry.registeredToolNames());
    }

    @Test
    void testExtractRequiredSecretsDefaultsToEmptyMapForUnknownTool() {
        AgentTool tool = mock(AgentTool.class);
        // name() must be stubbed: the lookup is getOrDefault(tool.name(), ...) and the cache is an
        // immutable Map, so a null key makes MapN.get() throw NPE rather than returning the default.
        when(tool.name()).thenReturn("no_such_tool");
        when(discoveryService.getSecretsCache()).thenReturn(Map.of());

        assertTrue(registry.extractRequiredSecrets(tool).isEmpty());
    }

    @Test
    void testExtractRequiredSecretsReadsFromCache() {
        AgentTool tool = mock(AgentTool.class);
        when(tool.name()).thenReturn("alpha");
        when(discoveryService.getSecretsCache()).thenReturn(Map.of("alpha", Map.of("KEY", "vaultKey")));

        assertEquals(Map.of("KEY", "vaultKey"), registry.extractRequiredSecrets(tool));
    }
}
