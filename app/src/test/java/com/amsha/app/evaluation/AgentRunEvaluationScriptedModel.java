package com.amsha.app.evaluation;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.when;

import dev.langchain4j.exception.HttpException;

import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import com.amsha.core.agent.loop.AgentExecutionContext;
import com.amsha.core.agent.reasoning.ReasoningEngine;
import com.amsha.core.agent.reasoning.ReasoningStep;
import com.amsha.core.sandbox.SandboxExecutionPort;
import com.amsha.core.sandbox.SandboxResult;
import com.amsha.core.tools.ToolRegistry;
import com.amsha.tool.api.ToolCapability;

/** Configures deterministic whole-run model and tool boundary fixtures. */
final class AgentRunEvaluationScriptedModel {
    static final String FAILURE_SENTINEL = "synthetic-tool-output-must-not-enter-report";

    private static final String UNKNOWN_TOOL = "web_search";

    private Map<String, AtomicInteger> roleSteps = new ConcurrentHashMap<>();

    private final AtomicInteger researcherCalls = new AtomicInteger();

    private final AtomicInteger githubExecutions = new AtomicInteger();

    private final AtomicInteger toolExecutions = new AtomicInteger();

    private final AtomicInteger modelCalls = new AtomicInteger();

    private final AtomicBoolean resumingPendingTool = new AtomicBoolean();

    void resetCounts() {
        roleSteps = new ConcurrentHashMap<>();
        researcherCalls.set(0);
        githubExecutions.set(0);
        toolExecutions.set(0);
        modelCalls.set(0);
        resumingPendingTool.set(false);
    }

    void prepare(
            ReasoningEngine reasoningEngine,
            SandboxExecutionPort sandboxExecutionPort,
            ToolRegistry toolRegistry,
            String script,
            String expectedAnswer) {
        reset(reasoningEngine);
        reset(sandboxExecutionPort);
        reset(toolRegistry);
        resetCounts();
        doAnswer(invocation -> {
                    if (!resumingPendingTool.get()) {
                        toolExecutions.incrementAndGet();
                    }
                    return invocation.callRealMethod();
                })
                .when(toolRegistry)
                .invokeTool(anyString(), anyMap());
        doAnswer(invocation -> {
                    toolExecutions.incrementAndGet();
                    resumingPendingTool.set(true);
                    try {
                        return invocation.callRealMethod();
                    } finally {
                        resumingPendingTool.set(false);
                    }
                })
                .when(toolRegistry)
                .invokeTool(anyString(), anyString(), anyString());
        doAnswer(invocation -> {
                    githubExecutions.incrementAndGet();
                    return "eligible-retry".equals(script)
                            ? SandboxResult.success("Synthetic repository metadata.")
                            : SandboxResult.failure(FAILURE_SENTINEL);
                })
                .when(sandboxExecutionPort)
                .executeSandboxed(anyString(), any(Path.class), anyList(), anyMap());
        when(reasoningEngine.nextStep(any(AgentExecutionContext.class), anyString(), anyString(), anyList()))
                .thenAnswer(invocation -> {
                    modelCalls.incrementAndGet();
                    return scriptedStep(script, invocation.getArgument(1, String.class), expectedAnswer);
                });
    }

    int researcherCalls() {
        return researcherCalls.get();
    }

    int githubExecutions() {
        return githubExecutions.get();
    }

    int toolExecutions() {
        return toolExecutions.get();
    }

    int modelCalls() {
        return modelCalls.get();
    }

    private ReasoningStep scriptedStep(String script, String role, String expectedAnswer) {
        int step = roleSteps.computeIfAbsent(role, _ -> new AtomicInteger()).getAndIncrement();
        if (role.toLowerCase(Locale.ROOT).contains("researcher")) {
            researcherCalls.incrementAndGet();
            return finishStep("Synthetic delegated finding.");
        }
        if ("eligible-retry".equals(script)) {
            if (step == 0) {
                return toolStep(ToolCapability.CORE_MEMORY_READ.toolName(), Map.of("block_key", "active_task"));
            }
            throw new HttpException(429, "Rate limit reached, try again in 1s");
        }
        if (("unknown-tool".equals(script)
                        || "deny-approval".equals(script)
                        || "tool-failure".equals(script)
                        || "durable-pause".equals(script))
                && step == 0) {
            String toolName = "unknown-tool".equals(script) ? UNKNOWN_TOOL : ToolCapability.GITHUB.toolName();
            return toolStep(toolName, Map.of("action", "repo", "command", "view", "target", "amsha/example"));
        }
        if ("delegate".equals(script) && step == 0) {
            return toolStep(
                    ToolCapability.DELEGATE_TO_AGENT.toolName(),
                    Map.of("agent", "researcher", "goal", "Return one synthetic repository finding."));
        }
        return finishStep(expectedAnswer);
    }

    private ReasoningStep toolStep(String toolName, Map<String, Object> parameters) {
        return ReasoningStep.pending("Synthetic evaluation action.", toolName, parameters, 0, 2);
    }

    private ReasoningStep finishStep(String answer) {
        return ReasoningStep.pending(
                "Complete synthetic evaluation case.", ReasoningStep.FINISH, Map.of("answer", answer), 1, 2);
    }
}
