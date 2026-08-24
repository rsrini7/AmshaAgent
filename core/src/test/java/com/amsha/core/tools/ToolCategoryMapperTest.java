package com.amsha.core.tools;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import jakarta.enterprise.inject.Instance;

import java.util.Map;
import java.util.Optional;

import com.amsha.core.tool.DynamicToolCategoryLookup;
import com.amsha.security.api.ToolOperation;
import com.amsha.tool.api.AgentTool;
import com.amsha.tool.api.ToolCategory;
import com.amsha.tool.api.ToolExecutionContext;
import com.amsha.tool.api.ToolMetadata;
import com.amsha.tool.api.ToolResponse;

import org.junit.jupiter.api.Test;

class ToolCategoryMapperTest {

    private final ToolCategoryMapper mapper = new ToolCategoryMapper();

    @Test
    void categoryFor_network() {
        assertEquals(ToolCategory.NETWORK, mapper.categoryFor("browser_navigate"));
        assertEquals(ToolCategory.NETWORK, mapper.categoryFor("brave_web_search"));
        assertEquals(ToolCategory.NETWORK, mapper.categoryFor("serper_web_search"));
        assertEquals(ToolCategory.NETWORK, mapper.categoryFor("web_fetch"));
    }

    @Test
    void categoryFor_compute() {
        assertEquals(ToolCategory.COMPUTE, mapper.categoryFor("sandbox_exec"));
    }

    @Test
    void categoryFor_database() {
        assertEquals(ToolCategory.DATABASE, mapper.categoryFor("recall_memory_search"));
        assertEquals(ToolCategory.DATABASE, mapper.categoryFor("ingest_document"));
        assertEquals(ToolCategory.DATABASE, mapper.categoryFor("manage_checklist"));
        assertEquals(ToolCategory.DATABASE, mapper.categoryFor("agent_task"));
    }

    @Test
    void categoryFor_internal() {
        assertEquals(ToolCategory.INTERNAL, mapper.categoryFor("github"));
        assertEquals(ToolCategory.INTERNAL, mapper.categoryFor("schedule_task"));
        assertEquals(ToolCategory.INTERNAL, mapper.categoryFor("manage_schedule"));
        assertEquals(ToolCategory.INTERNAL, mapper.categoryFor("garuda"));
        assertEquals(ToolCategory.INTERNAL, mapper.categoryFor("register_mcp_server"));
    }

    @Test
    void categoryFor_nullReturnsInternal() {
        assertEquals(ToolCategory.INTERNAL, mapper.categoryFor(null));
    }

    @Test
    void categoryFor_unknownReturnsInternal() {
        assertEquals(ToolCategory.INTERNAL, mapper.categoryFor("nonexistent_tool"));
    }

    @Test
    void categoryFor_stripsPrefix() {
        assertEquals(ToolCategory.NETWORK, mapper.categoryFor("mcp:browser_navigate"));
        assertEquals(ToolCategory.INTERNAL, mapper.categoryFor("mcp:github"));
    }

    @Test
    void testAgentToolCategoryOverride() {
        ToolRegistry registry = mock(ToolRegistry.class);
        AgentTool tool = mock(AgentTool.class);
        when(tool.category()).thenReturn(ToolCategory.DATABASE);
        when(registry.findTool("test_tool")).thenReturn(Optional.of(tool));

        ToolCategoryMapper customMapper = new ToolCategoryMapper();
        customMapper.toolRegistry = registry;

        assertEquals(ToolCategory.DATABASE, customMapper.categoryFor("test_tool"));
    }

    @Test
    void testToolMetadataCategoryOverride() {
        ToolRegistry registry = mock(ToolRegistry.class);
        AgentTool tool = new AnnotatedTool();
        when(registry.findTool("annotated_tool")).thenReturn(Optional.of(tool));

        ToolCategoryMapper customMapper = new ToolCategoryMapper();
        customMapper.toolRegistry = registry;

        assertEquals(ToolCategory.COMPUTE, customMapper.categoryFor("annotated_tool"));
    }

    @Test
    void testDynamicCategoryLookup() {
        Instance<DynamicToolCategoryLookup> lookups = mock(Instance.class);
        DynamicToolCategoryLookup lookup = mock(DynamicToolCategoryLookup.class);
        when(lookups.isUnsatisfied()).thenReturn(false);
        when(lookups.get()).thenReturn(lookup);
        when(lookup.categoryFor("dynamic_tool")).thenReturn(Optional.of(ToolCategory.DATABASE));

        ToolCategoryMapper customMapper = new ToolCategoryMapper();
        customMapper.dynamicCategoryLookups = lookups;

        assertEquals(ToolCategory.DATABASE, customMapper.categoryFor("dynamic_tool"));
    }

    @Test
    void testKnownToolResolvesToInternalSilently() {
        ToolRegistry registry = mock(ToolRegistry.class);
        AgentTool tool = mock(AgentTool.class);
        when(tool.category()).thenReturn(ToolCategory.INTERNAL);
        when(registry.findTool("known_internal")).thenReturn(Optional.of(tool));

        ToolCategoryMapper customMapper = new ToolCategoryMapper();
        customMapper.toolRegistry = registry;

        assertEquals(ToolCategory.INTERNAL, customMapper.categoryFor("known_internal"));
        verify(registry, atLeastOnce()).findTool("known_internal");
    }

    @Test
    void testDynamicToolInternalReturnsInternal() {
        Instance<DynamicToolCategoryLookup> lookups = mock(Instance.class);
        DynamicToolCategoryLookup lookup = mock(DynamicToolCategoryLookup.class);
        when(lookups.isUnsatisfied()).thenReturn(false);
        when(lookups.get()).thenReturn(lookup);
        when(lookup.categoryFor("dyn_internal")).thenReturn(Optional.of(ToolCategory.INTERNAL));

        ToolCategoryMapper customMapper = new ToolCategoryMapper();
        customMapper.dynamicCategoryLookups = lookups;

        assertEquals(ToolCategory.INTERNAL, customMapper.categoryFor("dyn_internal"));
    }

    @ToolMetadata(capabilityId = "annotated_tool", description = "Test tool", category = ToolCategory.COMPUTE)
    static class AnnotatedTool implements AgentTool {
        @Override
        public String name() {
            return "annotated_tool";
        }

        @Override
        public String description() {
            return "Test tool description";
        }

        @Override
        public ToolOperation requiredOperation() {
            return ToolOperation.READ_FS;
        }

        @Override
        public ToolResponse execute(ToolExecutionContext ctx, Map<String, Object> params) {
            return ToolResponse.success("ok");
        }
    }
}
