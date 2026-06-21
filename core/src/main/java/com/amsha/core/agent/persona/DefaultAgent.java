package com.amsha.core.agent.persona;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import com.amsha.channel.api.structured.SynthesisResult;
import com.amsha.common.util.LogUtil;
import com.amsha.core.agent.loop.AgentExecutionContext;
import com.amsha.core.agent.reasoning.ReasoningStep;
import com.amsha.core.agent.result.AgentResult;
import com.amsha.core.agent.role.AgentPersona;
import com.amsha.core.agent.role.AgentRoleEnum;
import com.amsha.core.agent.role.ToolCapability;
import com.amsha.core.structured.StructuredOutputEngine;
import com.amsha.core.tools.ToolRegistry;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.jboss.logging.Logger;

import io.quarkus.qute.Template;
import io.quarkus.qute.TemplateInstance;

/**
 * Data-driven implementation of the {@link Agent} interface.
 * System prompts are rendered via Qute templates from src/main/resources/templates/personas/{role}.txt
 */
public class DefaultAgent implements Agent {

    private static final Logger log = Logger.getLogger(DefaultAgent.class);

    private final AgentPersona persona;
    private final Supplier<Template> templateSupplier;
    private final String defaultTimezone;
    private final StructuredOutputEngine structuredOutputEngine;
    private final ObjectMapper objectMapper;
    private final ToolRegistry toolRegistry;

    public DefaultAgent(
            AgentPersona persona,
            Supplier<Template> templateSupplier,
            String defaultTimezone,
            StructuredOutputEngine structuredOutputEngine,
            ObjectMapper objectMapper,
            ToolRegistry toolRegistry) {
        this.persona = persona;
        this.templateSupplier = templateSupplier;
        this.defaultTimezone = defaultTimezone;
        this.structuredOutputEngine = structuredOutputEngine;
        this.objectMapper = objectMapper;
        this.toolRegistry = toolRegistry;
    }

    @Override
    public String role() {
        return persona.roleId();
    }

    @Override
    public String systemPrompt(AgentExecutionContext context) {
        TemplateInstance instance = templateSupplier.get().instance();
        AgentRoleEnum roleEnum = new AgentRoleEnum(persona.roleId());

        List<String> enabledToolDescriptions = roleEnum.definition().toolNames().stream()
                .map(name -> toolRegistry.findTool(name).orElse(null))
                .filter(tool -> tool != null && tool.isEnabled())
                .sorted((t1, t2) -> {
                    if (ToolCapability.BROWSER_NAVIGATE.toolName().equals(t1.name())) return -1;
                    if (ToolCapability.BROWSER_NAVIGATE.toolName().equals(t2.name())) return 1;
                    return t1.name().compareTo(t2.name());
                })
                .map(tool -> "- `" + tool.name() + "`: " + tool.description())
                .toList();

        instance.data("tools", "\n" + String.join("\n", enabledToolDescriptions));

        // Injection §7.38: hydrated at render-time for deterministic relative scheduling.
        instance.data("CURRENT_UTC_ISO", Instant.now().toString());

        ZoneId zoneId = ZoneId.of(defaultTimezone);
        ZonedDateTime localNow = ZonedDateTime.now(zoneId);

        instance.data(
                "USER_TZ_OFFSET", zoneId.getRules().getOffset(Instant.now()).toString() + " " + zoneId.getId());
        instance.data("CURRENT_LOCAL_TIME", localNow.format(DateTimeFormatter.ofPattern("HH:mm z")));

        // Seed scheduling keys as null - Qute {#if} treats null as false
        // CommandDispatchHandler overwrites these if real values are passed in context.data()
        instance.data("SUGGESTED_CRON", null);
        instance.data("TARGET_UTC_HOUR", null);
        instance.data("TARGET_UTC_MINUTE", null);

        // pass all dynamic metadata from the pipeline context
        if (context != null && context.data() != null) {
            context.data().forEach(instance::data);
        }

        return instance.render();
    }

    @Override
    public List<String> allowedTools() {
        return List.copyOf(new AgentRoleEnum(persona.roleId()).definition().toolNames());
    }

    @Override
    public AgentResult synthesize(String sessionId, String combinedContext, List<ReasoningStep> history) {
        if (persona != AgentPersona.DATTATREYA) {
            return Agent.super.synthesize(sessionId, combinedContext, history);
        }

        // Logic moved from DattatreyaAgent
        SynthesisResult result = structuredOutputEngine.invoke(
                sessionId, role(), combinedContext, "SynthesisFunction", SynthesisResult.class);

        log.infof(
                "[%s] Synthesis complete: %d contributions integrated.",
                persona.roleId(),
                result.contributions() != null ? result.contributions().size() : 0);

        try {
            String json = objectMapper.writeValueAsString(result);
            history.add(ReasoningStep.of(
                    "I have synthesized the specialist findings into a final resolution.",
                    ReasoningStep.FINISH,
                    Map.of("answer", result.finalResolution(), "structured", json),
                    "Structured synthesis complete.",
                    0,
                    0));
        } catch (Exception e) {
            log.warnf("Failed to serialize synthesis result for session %s: %s", sessionId, LogUtil.rootMessage(e));
        }

        return AgentResult.structuredFinished(result.finalResolution(), result, List.copyOf(history));
    }
}
