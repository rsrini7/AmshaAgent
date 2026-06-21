package com.amsha.core.agent.persona;

import static org.junit.jupiter.api.Assertions.*;

import com.amsha.core.structured.StructuredOutputEngine;
import com.amsha.core.tools.ToolRegistry;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import io.quarkus.qute.Template;

@ExtendWith(MockitoExtension.class)
class AgentProducerTest {

    @Mock
    Template coordinatorTemplate;

    @Mock
    Template coderTemplate;

    @Mock
    Template debuggerTemplate;

    @Mock
    Template researcherTemplate;

    @Mock
    Template architectTemplate;

    @Mock
    Template synthesizerTemplate;

    @Mock
    StructuredOutputEngine structuredOutputEngine;

    @Mock
    ToolRegistry toolRegistry;

    ObjectMapper objectMapper = new ObjectMapper();

    AgentProducer producer;

    @BeforeEach
    void setUp() throws Exception {
        producer = new AgentProducer();
        setField(producer, "defaultTimezone", "Asia/Kolkata");
        setField(producer, "coordinatorTemplate", coordinatorTemplate);
        setField(producer, "coderTemplate", coderTemplate);
        setField(producer, "debuggerTemplate", debuggerTemplate);
        setField(producer, "researcherTemplate", researcherTemplate);
        setField(producer, "architectTemplate", architectTemplate);
        setField(producer, "synthesizerTemplate", synthesizerTemplate);
        setField(producer, "structuredOutputEngine", structuredOutputEngine);
        setField(producer, "objectMapper", objectMapper);
        setField(producer, "toolRegistry", toolRegistry);
    }

    private static void setField(Object target, String name, Object value) throws Exception {
        var field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    @Test
    void coordinator_ReturnsAgentWithVishnuPersona() {
        Agent agent = producer.coordinator();
        assertNotNull(agent);
        assertEquals("coordinator", agent.role());
    }

    @Test
    void coder_ReturnsAgentWithBrahmaPersona() {
        Agent agent = producer.coder();
        assertNotNull(agent);
        assertEquals("coder", agent.role());
    }

    @Test
    void debugger_ReturnsAgentWithShivaPersona() {
        Agent agent = producer.debugger();
        assertNotNull(agent);
        assertEquals("debugger", agent.role());
    }

    @Test
    void researcher_ReturnsAgentWithSaraswatiPersona() {
        Agent agent = producer.researcher();
        assertNotNull(agent);
        assertEquals("researcher", agent.role());
    }

    @Test
    void architect_ReturnsAgentWithBrihaspatiPersona() {
        Agent agent = producer.architect();
        assertNotNull(agent);
        assertEquals("architect", agent.role());
    }

    @Test
    void synthesizer_ReturnsAgentWithDattatreyaPersona() {
        Agent agent = producer.synthesizer();
        assertNotNull(agent);
        assertEquals("synthesizer", agent.role());
    }

    @Test
    void allProducers_ReturnDistinctInstances() {
        var coordinator = producer.coordinator();
        var coder = producer.coder();
        assertNotSame(coordinator, coder);
        assertNotSame(coordinator, producer.debugger());
        assertNotSame(coordinator, producer.researcher());
        assertNotSame(coordinator, producer.architect());
        assertNotSame(coordinator, producer.synthesizer());
    }
}
