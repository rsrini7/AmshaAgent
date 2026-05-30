package com.arcclaw.core.tools;

import static org.junit.jupiter.api.Assertions.*;

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
}
