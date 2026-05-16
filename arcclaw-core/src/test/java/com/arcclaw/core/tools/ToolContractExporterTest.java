package com.arcclaw.core.tools;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.arcclaw.core.security.ToolOperation;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ToolContractExporterTest {

    @TempDir
    Path tempDir;

    @Test
    void exportsToolContractsToJson() throws Exception {
        // Build a mock registry with one fake tool
        var tool = new FakeTool();
        var registry = new FakeToolRegistry(tool);
        var discovery = new FakeDiscoveryService(tool);

        ToolContractExporter exporter = new ToolContractExporter();
        exporter.toolRegistry = registry;
        exporter.discoveryService = discovery;
        exporter.dataDir = tempDir.toString();

        exporter.onStart(null);

        Path out = tempDir.resolve("tool-contracts.json");
        assertTrue(Files.exists(out), "tool-contracts.json should exist");

        Map<?, ?> root = new ObjectMapper().readValue(out.toFile(), Map.class);
        assertEquals(1, root.get("version"));
        assertEquals(1, root.get("toolCount"));

        List<?> tools = (List<?>) root.get("tools");
        assertEquals(1, tools.size());

        Map<?, ?> tc = (Map<?, ?>) tools.get(0);
        assertEquals("fake_tool", tc.get("name"));
        assertEquals("A fake tool", tc.get("description"));
        assertEquals("EXECUTE_SHELL", tc.get("requiredOperation"));
        assertEquals("CRITICAL", tc.get("riskLevel"));
        assertEquals("NON_IDEMPOTENT", tc.get("idempotencyMode"));
    }

    // Minimal fakes for testing without CDI

    @jakarta.enterprise.inject.Vetoed
    static class FakeTool implements AgentTool {
        @Override public String name() { return "fake_tool"; }
        @Override public String description() { return "A fake tool"; }
        @Override public ToolOperation requiredOperation() { return ToolOperation.EXECUTE_SHELL; }
        @Override
        public ToolResponse execute(ToolExecutionContext ctx, Map<String, Object> params) {
            return ToolResponse.success("ok");
        }
    }

    @jakarta.enterprise.inject.Vetoed
    static class FakeToolRegistry extends ToolRegistry {
        private final Map<String, AgentTool> tools = new LinkedHashMap<>();
        FakeToolRegistry(AgentTool... toolList) {
            for (AgentTool t : toolList) tools.put(t.name(), t);
        }
        @Override public java.util.Optional<AgentTool> findTool(String name) {
            return java.util.Optional.ofNullable(tools.get(name));
        }
        @Override public List<String> listTools() {
            return List.copyOf(tools.keySet());
        }
    }

    @jakarta.enterprise.inject.Vetoed
    static class FakeDiscoveryService extends ToolDiscoveryService {
        private final Map<String, ToolRegistry.ToolDescriptor> descriptors = new LinkedHashMap<>();
        private final Map<String, Map<String, String>> secrets = new LinkedHashMap<>();
        FakeDiscoveryService(AgentTool... toolList) {
            for (AgentTool t : toolList) {
                descriptors.put(t.name(), new ToolRegistry.ToolDescriptor(t.name(), t.description(), "{}"));
                secrets.put(t.name(), Map.of());
            }
        }
        @Override public Map<String, ToolRegistry.ToolDescriptor> getDescriptorCache() { return descriptors; }
        @Override public Map<String, Map<String, String>> getSecretsCache() { return secrets; }
    }
}
