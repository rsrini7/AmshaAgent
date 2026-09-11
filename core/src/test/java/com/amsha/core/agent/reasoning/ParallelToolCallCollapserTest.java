package com.amsha.core.agent.reasoning;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;

import java.util.List;

import com.amsha.core.util.TextPatternUtils;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Regression coverage for {@link ParallelToolCallCollapser} de-duplication.
 *
 * <p>Root cause context: the live log (session cli-27a5d1dd…:delegate:saraswati) showed the LLM
 * emitting {@code serper_web_search, serper_web_search} and {@code web_fetch, web_fetch, web_fetch}
 * in a single turn. The previous exact (name, params) signature dedup did not catch these because
 * the arguments differed, so the same tool was invoked repeatedly. These tests lock in the
 * tool-aware identity-key collapse: identical target (query/URL) → one call; distinct targets →
 * preserved as a parallel batch.
 */
class ParallelToolCallCollapserTest {

    ObjectMapper objectMapper;
    ParallelToolCallCollapser collapser;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        TextPatternUtils textPatternUtils = new TextPatternUtils();
        ToolCallParameterParser parameterParser = new ToolCallParameterParser(objectMapper, textPatternUtils);
        collapser = new ParallelToolCallCollapser(objectMapper, parameterParser);
    }

    @Test
    void dropsIdenticalSearchQueries() {
        ToolExecutionRequest r1 = ToolExecutionRequest.builder()
                .id("c1")
                .name("serper_web_search")
                .arguments("{\"query\":\"garuda airlines\"}")
                .build();
        ToolExecutionRequest r2 = ToolExecutionRequest.builder()
                .id("c2")
                .name("serper_web_search")
                .arguments("{\"query\":\"garuda airlines\"}")
                .build();

        ReasoningStep step = collapser.collapse(
                "s1", "researcher", AiMessage.aiMessage("think", List.of(r1, r2)), List.of(r1, r2), 10, 5);

        assertFalse(step.isParallelBatch(), "identical queries should collapse to a single call");
        assertEquals("serper_web_search", step.action());
    }

    @Test
    void preservesDistinctSearchQueries() {
        ToolExecutionRequest r1 = ToolExecutionRequest.builder()
                .id("c1")
                .name("serper_web_search")
                .arguments("{\"query\":\"garuda airlines\"}")
                .build();
        ToolExecutionRequest r2 = ToolExecutionRequest.builder()
                .id("c2")
                .name("serper_web_search")
                .arguments("{\"query\":\"garuda flight status\"}")
                .build();

        ReasoningStep step = collapser.collapse(
                "s1", "researcher", AiMessage.aiMessage("think", List.of(r1, r2)), List.of(r1, r2), 10, 5);

        assertTrue(step.isParallelBatch(), "distinct queries must remain a parallel batch");
        assertEquals(2, countCalls(step));
    }

    @Test
    void dropsIdenticalFetchUrls() {
        ToolExecutionRequest r1 = ToolExecutionRequest.builder()
                .id("c1")
                .name("web_fetch")
                .arguments("{\"url\":\"https://example.com/a\"}")
                .build();
        ToolExecutionRequest r2 = ToolExecutionRequest.builder()
                .id("c2")
                .name("web_fetch")
                .arguments("{\"url\":\"https://example.com/a\"}")
                .build();
        ToolExecutionRequest r3 = ToolExecutionRequest.builder()
                .id("c3")
                .name("web_fetch")
                .arguments("{\"url\":\"https://example.com/a\"}")
                .build();

        ReasoningStep step = collapser.collapse(
                "s1", "researcher", AiMessage.aiMessage("think", List.of(r1, r2, r3)), List.of(r1, r2, r3), 10, 5);

        assertFalse(step.isParallelBatch(), "three identical URLs should collapse to one fetch");
        assertEquals("web_fetch", step.action());
    }

    @Test
    void preservesDistinctFetchUrls() {
        ToolExecutionRequest r1 = ToolExecutionRequest.builder()
                .id("c1")
                .name("web_fetch")
                .arguments("{\"url\":\"https://example.com/a\"}")
                .build();
        ToolExecutionRequest r2 = ToolExecutionRequest.builder()
                .id("c2")
                .name("web_fetch")
                .arguments("{\"url\":\"https://example.com/b\"}")
                .build();

        ReasoningStep step = collapser.collapse(
                "s1", "researcher", AiMessage.aiMessage("think", List.of(r1, r2)), List.of(r1, r2), 10, 5);

        assertTrue(step.isParallelBatch(), "distinct URLs must remain a parallel batch");
        assertEquals(2, countCalls(step));
    }

    @Test
    void dropsIdenticalBrowserNavigateUrls() {
        ToolExecutionRequest r1 = ToolExecutionRequest.builder()
                .id("c1")
                .name("browser_navigate")
                .arguments("{\"url\":\"https://spring.io\"}")
                .build();
        ToolExecutionRequest r2 = ToolExecutionRequest.builder()
                .id("c2")
                .name("browser_navigate")
                .arguments("{\"url\":\"https://spring.io\"}")
                .build();

        ReasoningStep step = collapser.collapse(
                "s1", "researcher", AiMessage.aiMessage("think", List.of(r1, r2)), List.of(r1, r2), 10, 5);

        assertFalse(step.isParallelBatch(), "identical browser URLs should collapse to one navigate");
        assertEquals("browser_navigate", step.action());
    }

    @Test
    void dropsIdenticalBraveWebSearchQueries() {
        ToolExecutionRequest r1 = ToolExecutionRequest.builder()
                .id("c1")
                .name("brave_web_search")
                .arguments("{\"query\":\"quarkus lts version\"}")
                .build();
        ToolExecutionRequest r2 = ToolExecutionRequest.builder()
                .id("c2")
                .name("brave_web_search")
                .arguments("{\"query\":\"quarkus lts version\"}")
                .build();

        ReasoningStep step = collapser.collapse(
                "s1", "researcher", AiMessage.aiMessage("think", List.of(r1, r2)), List.of(r1, r2), 10, 5);

        assertFalse(step.isParallelBatch(), "identical search queries should collapse to one query");
        assertEquals("brave_web_search", step.action());
    }

    private int countCalls(ReasoningStep step) {
        if (!(step instanceof ReasoningStep.ParallelStep ps)) {
            return 1;
        }
        String batchJson = (String) ps.actionInput().get("batch");
        try {
            return objectMapper
                    .readValue(batchJson, new TypeReference<List<ParallelToolCall>>() {})
                    .size();
        } catch (Exception e) {
            throw new AssertionError("could not parse parallel batch payload", e);
        }
    }
}
