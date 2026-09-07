package com.amsha.core.agent.reasoning;

import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.enterprise.inject.spi.BeanManager;
import jakarta.inject.Inject;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import com.amsha.channel.api.MediaAttachment;
import com.amsha.common.util.LogUtil;
import com.amsha.core.agent.IntentClassificationService;
import com.amsha.core.agent.loop.AgentExecutionContext;
import com.amsha.core.agent.loop.CodeGenerationPolicy;
import com.amsha.core.agent.loop.LoopStuckDetector;
import com.amsha.core.agent.reasoning.port.LlmProviderPort;
import com.amsha.core.agent.role.AgentRoleEnum;
import com.amsha.core.config.CoreSpiResolutionValidator;
import com.amsha.core.routing.ModelRoute;
import com.amsha.core.routing.TurnRequirements;
import com.amsha.core.skill.SkillAdaptationManager;
import com.amsha.core.skill.SkillRegistry;
import com.amsha.core.tool.DynamicToolInvoker;
import com.amsha.core.tools.DynamicToolSchemaParser;
import com.amsha.core.tools.ToolRegistry;
import com.amsha.core.tools.search.ToolSearchPort;
import com.amsha.core.util.TextPatternUtils;
import com.amsha.llm.api.MultimodalCapability;
import com.amsha.llm.api.audio.AudioTranscriptionPort;
import com.amsha.security.api.AutonomyLevel;
import com.amsha.security.api.MediaDownloadPort;
import com.amsha.tool.api.AgentSentinels;
import com.amsha.tool.api.AgentTool;
import com.amsha.tool.api.ToolCapability;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import io.micrometer.core.instrument.MeterRegistry;

/**
 * Encapsulates the logic for assembling the LLM message list, applying
 * write-intent overrides, and filtering tool history for simple chitchat.
 */
@ApplicationScoped
public class ReasoningMessageAssembler {

    private static final Logger log = Logger.getLogger(ReasoningMessageAssembler.class);

    private final ChatHistoryAssembler historyAssembler;
    private final ToolRegistry toolRegistry;
    private final LoopStuckDetector loopStuckDetector;
    private final IntentClassificationService intentClassifier;
    private final MultimodalHistoryProjector historyProjector;
    private final LoopStuckTracer loopStuckTracer;

    @Inject
    BeanManager beanManager;

    @Inject
    SkillRegistry skillRegistry;

    @Inject
    SkillAdaptationManager adaptationManager;

    @Inject
    Instance<DynamicToolInvoker> dynamicToolInvokers;

    @Inject
    ObjectMapper objectMapper;

    @Inject
    TextPatternUtils textPatternUtils;

    @Inject
    CodeGenerationPolicy codePolicy;

    @Inject
    Instance<LlmProviderPort> llmProviderPortInstance;

    @Inject
    Instance<MediaDownloadPort> mediaDownloadPortInstance;

    @Inject
    Instance<AudioTranscriptionPort> audioTranscriptionPortInstance;

    @Inject
    @ConfigProperty(name = "amsha.stt.allow-fallback-on-failure", defaultValue = "false")
    boolean allowFallbackOnFailure;

    @Inject
    Instance<MeterRegistry> meterRegistryInstance;

    @Inject
    public ReasoningMessageAssembler(
            ChatHistoryAssembler historyAssembler,
            ToolRegistry toolRegistry,
            LoopStuckDetector loopStuckDetector,
            IntentClassificationService intentClassifier,
            MultimodalHistoryProjector historyProjector,
            LoopStuckTracer loopStuckTracer) {
        this.historyAssembler = historyAssembler;
        this.toolRegistry = toolRegistry;
        this.loopStuckDetector = loopStuckDetector;
        this.intentClassifier = intentClassifier;
        this.historyProjector = historyProjector != null ? historyProjector : new MultimodalHistoryProjector();
        this.loopStuckTracer = loopStuckTracer;
    }

    public ReasoningMessageAssembler(
            ChatHistoryAssembler historyAssembler,
            ToolRegistry toolRegistry,
            LoopStuckDetector loopStuckDetector,
            IntentClassificationService intentClassifier,
            MultimodalHistoryProjector historyProjector) {
        this(historyAssembler, toolRegistry, loopStuckDetector, intentClassifier, historyProjector, null);
    }

    public ReasoningMessageAssembler(
            ChatHistoryAssembler historyAssembler,
            ToolRegistry toolRegistry,
            LoopStuckDetector loopStuckDetector,
            IntentClassificationService intentClassifier) {
        this(historyAssembler, toolRegistry, loopStuckDetector, intentClassifier, new MultimodalHistoryProjector());
    }

    public ReasoningMessageAssembler(
            ChatHistoryAssembler historyAssembler, ToolRegistry toolRegistry, LoopStuckDetector loopStuckDetector) {
        this(historyAssembler, toolRegistry, loopStuckDetector, new IntentClassificationService());
    }

    protected ReasoningMessageAssembler() {
        this(null, null, null, null, null);
    }

    public record AssembledMessages(List<ChatMessage> messages, List<ToolSpecification> tools) {}

    public AssembledMessages assemble(
            AgentExecutionContext context,
            String agentRole,
            String goal,
            List<ReasoningStep> history,
            String systemPrompt,
            Set<String> allowedTools,
            boolean isWriteIntent,
            boolean isSimpleChitchat,
            boolean isHistoryQuery) {
        TurnRequirements reqs = TurnRequirements.fromContext(context, goal);
        ModelRoute route = null;
        try {
            if (llmProviderPortInstance != null && !llmProviderPortInstance.isUnsatisfied()) {
                route = llmProviderPortInstance.get().resolveRoute(agentRole, reqs);
            }
        } catch (RuntimeException e) {
            log.warnf(
                    "[ReasoningAssembler] Route resolution failed; using text-only fallback: %s",
                    LogUtil.rootMessage(e));
            route = ModelRoute.textOnlyFallback();
        }
        return assemble(
                context,
                agentRole,
                goal,
                history,
                systemPrompt,
                allowedTools,
                isWriteIntent,
                isSimpleChitchat,
                isHistoryQuery,
                route);
    }

    public AssembledMessages assemble(
            AgentExecutionContext context,
            String agentRole,
            String goal,
            List<ReasoningStep> history,
            String systemPrompt,
            Set<String> allowedTools,
            boolean isWriteIntent,
            boolean isSimpleChitchat,
            boolean isHistoryQuery,
            ModelRoute route) {

        String sessionId = context != null ? context.sessionId() : null;
        boolean isForceFinishRetry = context != null && context.isForceFinishRetry();
        AutonomyLevel autonomyLevel = context != null ? context.autonomyLevel() : null;
        List<MediaAttachment> attachments = context != null ? context.mediaAttachments() : null;

        log.infof(
                "[ReasoningAssembler] Assembling messages for session %s (role=%s, steps=%d, isChitchat=%b)",
                sessionId, agentRole, history.size(), isSimpleChitchat);

        List<ChatMessage> assembled = historyAssembler.assemble(sessionId, goal, history);

        List<ChatMessage> filteredAssembled = assembled.stream()
                .filter(m -> !(m instanceof SystemMessage sm
                        && sm.text().contains(AgentSentinels.COMPACTED_SUMMARY_SENTINEL)))
                .toList();

        Optional<String> stuckHint = loopStuckDetector.detectStuckHint(history, agentRole);
        String finalSystemPrompt =
                ReasoningMessageHelpers.resolveSystemPrompt(assembled, systemPrompt, isWriteIntent, isForceFinishRetry);
        finalSystemPrompt = applyLoopStuckHint(finalSystemPrompt, sessionId, agentRole, stuckHint);

        List<ChatMessage> messages = new ArrayList<>();
        messages.add(SystemMessage.systemMessage(finalSystemPrompt));

        if (autonomyLevel == AutonomyLevel.BACKGROUND) {
            messages.add(
                    SystemMessage.systemMessage("CAPABILITY CONSTRAINT: You do NOT have access to `schedule_task` or "
                            + "`manage_schedule` in this context. Do not attempt to call them. "
                            + "Use only the tools explicitly listed in your tool schema."));
        }

        List<ChatMessage> historyToInclude =
                ReasoningMessageHelpers.filterHistory(filteredAssembled, isSimpleChitchat, isForceFinishRetry);

        MultimodalCapability cap =
                route != null && route.capability() != null ? route.capability() : resolveLegacyCapability(agentRole);
        String targetModelName = route != null && route.modelName() != null ? route.modelName() : agentRole;

        MultimodalHistoryProjector projector =
                historyProjector != null ? historyProjector : new MultimodalHistoryProjector();
        List<ChatMessage> projectedHistory = projector.projectHistory(historyToInclude, cap, targetModelName);

        if (isSimpleChitchat && !isHistoryQuery) {
            messages.addAll(ReasoningMessageHelpers.recentConversationContext(
                    projectedHistory, goal, intentClassifier, textPatternUtils, codePolicy));
            if (attachments != null && !attachments.isEmpty()) {
                messages.add(mapMediaMessage(context, goal, cap, targetModelName));
            } else {
                messages.add(UserMessage.userMessage(goal));
            }
        } else {
            UserMessage mediaMessage = (attachments != null && !attachments.isEmpty())
                    ? mapMediaMessage(context, goal, cap, targetModelName)
                    : null;
            ReasoningMessageHelpers.appendProjectedHistory(messages, projectedHistory, mediaMessage, goal);
            if (isForceFinishRetry) {
                messages.add(UserMessage.userMessage("Your previous response contained raw JSON or tool-call syntax. "
                        + "Answer in plain prose only. All tools have already returned their results "
                        + "- do NOT call any more tools. Summarize the findings and call FINISH."));
            }
        }

        // Invariant guarantee: chat templates require at least one UserMessage (e.g. on resumed turns
        // where history projection contains only tool calls/results). If missing, inject a goal-oriented
        // UserMessage (preserving multimodal attachments via the target model's capability) right after
        // any system messages and before assistant/tool messages.
        boolean hasUserMessage = messages.stream().anyMatch(m -> m instanceof UserMessage);
        if (!hasUserMessage) {
            String userQuery = (goal != null && !goal.isBlank()) ? goal : "Please proceed.";
            UserMessage fallbackUserMsg = (attachments != null && !attachments.isEmpty())
                    ? mapMediaMessage(context, userQuery, cap, targetModelName)
                    : UserMessage.userMessage(userQuery);
            int insertIdx = 0;
            while (insertIdx < messages.size() && messages.get(insertIdx) instanceof SystemMessage) {
                insertIdx++;
            }
            messages.add(insertIdx, fallbackUserMsg);
        }

        boolean isBackgroundSession = autonomyLevel == AutonomyLevel.BACKGROUND
                || (sessionId != null && sessionId.startsWith(AgentSentinels.CRON_SESSION_PREFIX));

        List<ToolSpecification> toolSpecifications = resolveTools(
                allowedTools, isSimpleChitchat, isForceFinishRetry, isBackgroundSession, goal, agentRole, sessionId);

        log.debugf(
                "[ReasoningAssembler] Assembled %d messages and %d tools for session %s",
                messages.size(), toolSpecifications.size(), sessionId);

        return new AssembledMessages(messages, toolSpecifications);
    }

    private UserMessage mapMediaMessage(
            AgentExecutionContext context, String goal, MultimodalCapability capability, String targetModelName) {
        MediaDownloadPort downloadPort = resolveMediaDownloadPort();
        MeterRegistry meterRegistry = resolveMeterRegistry();
        // Autonomy is not an operator approval receipt. Keep media downloads behind the governed
        // HITL policy until an explicit receipt can be carried through AgentExecutionContext.
        return MultimodalMessageMapper.mapToUserMessage(
                goal,
                context != null ? context.mediaAttachments() : null,
                capability,
                targetModelName,
                downloadPort,
                context != null ? context.userId() : null,
                TelegramMediaOriginValidator.hasTrustedTelegramMediaOrigin(context),
                resolveAudioTranscriptionPort(),
                meterRegistry);
    }

    private MultimodalCapability resolveLegacyCapability(String agentRole) {
        if (llmProviderPortInstance == null || llmProviderPortInstance.isUnsatisfied()) {
            return MultimodalCapability.TEXT_ONLY;
        }
        try {
            MultimodalCapability capability = llmProviderPortInstance.get().multimodalCapability(agentRole);
            return capability != null ? capability : MultimodalCapability.TEXT_ONLY;
        } catch (RuntimeException e) {
            log.warnf(
                    "[ReasoningAssembler] Legacy capability resolution failed; using text-only fallback: %s",
                    LogUtil.rootMessage(e));
            return MultimodalCapability.TEXT_ONLY;
        }
    }

    private MeterRegistry resolveMeterRegistry() {
        if (meterRegistryInstance == null || !meterRegistryInstance.isResolvable()) return null;
        try {
            return meterRegistryInstance.get();
        } catch (Exception _) {
            return null;
        }
    }

    private MediaDownloadPort resolveMediaDownloadPort() {
        if (mediaDownloadPortInstance == null || !mediaDownloadPortInstance.isResolvable()) return null;
        try {
            return mediaDownloadPortInstance.get();
        } catch (Exception _) {
            return null;
        }
    }

    private AudioTranscriptionPort resolveAudioTranscriptionPort() {
        if (audioTranscriptionPortInstance == null || audioTranscriptionPortInstance.isUnsatisfied()) {
            return null;
        }
        try {
            List<AudioTranscriptionPort> ports =
                    audioTranscriptionPortInstance.stream().toList();
            if (ports.isEmpty()) {
                return null;
            }
            if (ports.size() == 1) {
                return ports.getFirst();
            }
            return new CompositeAudioTranscriptionPort(ports, allowFallbackOnFailure);
        } catch (Exception e) {
            log.debugf("Failed to resolve AudioTranscriptionPort: %s", LogUtil.rootMessage(e));
            return null;
        }
    }

    /**
     * Appends the loop-stuck hint to the system prompt when the detector reports one, and emits a
     * {@code loop_stuck} trace via the dedicated tracer. Best-effort: a missing tracer (e.g.
     * lightweight unit test) is skipped.
     */
    private String applyLoopStuckHint(
            String systemPrompt, String sessionId, String agentRole, Optional<String> stuckHint) {
        if (stuckHint.isEmpty()) {
            return systemPrompt;
        }
        log.infof("[ReasoningAssembler] Injecting loop-stuck hint for session %s (role=%s)", sessionId, agentRole);
        if (loopStuckTracer != null) {
            loopStuckTracer.emit(sessionId, agentRole);
        }
        return systemPrompt + "\n\n" + stuckHint.get();
    }

    private List<ToolSpecification> resolveTools(
            Set<String> allowedTools,
            boolean isSimpleChitchat,
            boolean isForceFinishRetry,
            boolean isBackgroundSession,
            String goal,
            String agentRole,
            String sessionId) {
        if (isSimpleChitchat || isForceFinishRetry) {
            return List.of();
        }

        // Get skill home path for dynamic tool description injection
        final String skillHome;
        if (agentRole != null && AgentRoleEnum.isSkillRole(agentRole)) {
            String skillName = AgentRoleEnum.skillName(agentRole);
            var skillEntry = skillRegistry.currentSnapshot().stream()
                    .filter(e -> e.definition().name().equals(skillName))
                    .findFirst();
            skillHome = skillEntry.map(e -> e.definition().skillHome()).orElse(null);
            log.infof("[ToolDescInjection] agentRole=%s skillName=%s skillHome=%s", agentRole, skillName, skillHome);
        } else {
            skillHome = null;
        }

        List<String> ranked = new ArrayList<>();
        if (goal != null && !goal.isBlank()) {
            Optional<ToolSearchPort> searchPortOpt =
                    CoreSpiResolutionValidator.resolveOptional(beanManager, ToolSearchPort.class);
            if (searchPortOpt.isPresent()) {
                ToolSearchPort searchPort = searchPortOpt.get();
                if (searchPort.isReady()) {
                    List<String> searchResults = searchPort.search(goal, allowedTools.size());
                    for (String name : searchResults) {
                        if (allowedTools.contains(name)) {
                            ranked.add(name);
                        }
                    }
                }
            }
        }
        for (String name : allowedTools) {
            if (!ranked.contains(name)) {
                ranked.add(name);
            }
        }
        List<ToolSpecification> specifications = ranked.stream()
                .filter(name -> !isBackgroundSession
                        || (!name.equals(ToolCapability.SCHEDULE_TASK.toolName())
                                && !name.equals(ToolCapability.MANAGE_SCHEDULE.toolName())))
                .filter(name -> toolRegistry.findTool(name).isPresent())
                .map(name -> toolRegistry.findTool(name).orElseThrow())
                .filter(AgentTool::isEnabled)
                .map(tool -> {
                    String description = tool.description();
                    // Inject skill's basePath into host_exec description for skill roles
                    if (skillHome != null && ToolCapability.HOST_EXEC.toolName().equals(tool.name())) {
                        String scriptsDir = skillHome + "/scripts";
                        boolean hasScripts = Files.isDirectory(Path.of(scriptsDir));
                        log.infof(
                                "[ToolDescInjection] tool=%s scriptsDir=%s hasScripts=%s",
                                tool.name(), scriptsDir, hasScripts);
                        if (hasScripts) {
                            description += "\n\nSkill scripts are located at: " + scriptsDir
                                    + "\nIMPORTANT: Use host_exec (not sandbox_exec) to run skill scripts — they need host filesystem access."
                                    + "\nCall scripts directly with full path. Do NOT run prerequisite commands."
                                    + "\nFor non-interactive tasks, use scripts that accept input via flags or stdin."
                                    + "\nChain commands with && (Linux/macOS) or ; (PowerShell) if needed.";

                            // Add adaptation instructions if adaptive mode is enabled
                            String adaptInstructions = adaptationManager.getAdaptationInstructions(skillHome);
                            if (!adaptInstructions.isEmpty()) {
                                description += adaptInstructions;
                            }
                        }
                    }
                    return ToolSpecification.builder()
                            .name(tool.name())
                            .description(description)
                            .parameters(tool.parametersSchema())
                            .build();
                })
                .collect(Collectors.toList());
        if (dynamicToolInvokers != null && !dynamicToolInvokers.isUnsatisfied() && objectMapper != null) {
            dynamicToolInvokers.stream()
                    .flatMap(catalog -> catalog.listTools().stream())
                    .filter(tool -> specifications.stream()
                            .noneMatch(spec -> spec.name().equals(tool.name())))
                    .map(tool -> ToolSpecification.builder()
                            .name(tool.name())
                            .description(tool.description())
                            .parameters(DynamicToolSchemaParser.parse(objectMapper, tool.inputSchema()))
                            .build())
                    .forEach(specifications::add);
        }
        return specifications;
    }
}
