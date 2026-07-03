package com.amsha.selfevolve.policy;

import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.ChatRequestParameters;
import dev.langchain4j.model.chat.response.ChatResponse;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.ArrayList;
import java.util.List;

import com.amsha.core.config.SelfEvolveConfig;
import com.amsha.selfevolve.api.DirectiveBundle;
import com.amsha.selfevolve.api.DirectiveState;
import com.amsha.selfevolve.api.ExtractedMemory;
import com.amsha.selfevolve.api.MemoryRecordType;
import com.amsha.selfevolve.api.SynthesizedDirective;
import com.amsha.selfevolve.store.DirectiveStorePort;
import com.amsha.selfevolve.store.ExtractedMemoryStorePort;

import org.jboss.logging.Logger;

/**
 * Synthesizes behavioral directives from extracted memory patterns.
 * Uses an LLM to distill patterns into concise, actionable directives.
 * <p>
 * Triggered on-demand via {@code self_evolve_reflect} tool or daily cron — never mid-conversation.
 */
@ApplicationScoped
public class InteractionPolicySynthesizer {

    private static final Logger log = Logger.getLogger(InteractionPolicySynthesizer.class);

    private static final String SYNTHESIS_SYSTEM_PROMPT = """
            You are a behavioral policy synthesizer. Given a set of extracted memory patterns from agent interactions,
            synthesize concise, actionable behavioral directives that will improve future agent behavior.

            RULES:
            1. Each directive must be a single, clear instruction (max 200 words).
            2. Directives must be generalizable — not tied to a specific session or conversation.
            3. Prefer directives that prevent known failures over those that optimize successes.
            4. Output ONLY a JSON array of directive objects. No other text.
            5. Each object: {"text": "...", "confidence": 0.0-1.0, "reasoning": "..."}
            6. Confidence reflects how strongly the evidence supports this directive.
            7. Maximum 5 directives per synthesis run.

            DIRECTIVE TYPES TO CONSIDER:
            - FAILURE_PATTERN → "When X fails, try Y instead"
            - TOOL_HINT → "Always use X tool for Y tasks"
            - USER_PREFERENCE → "User prefers X over Y"
            - RESOLUTION_PATTERN → "X is the standard fix for Y"
            - WORKAROUND → "For Z issue, use workaround W"
            """;

    @Inject
    SelfEvolveConfig config;

    @Inject
    ExtractedMemoryStorePort extractedStore;

    @Inject
    DirectiveStorePort directiveStore;

    @Inject
    ShadowLifecycleManager lifecycleManager;

    @Inject
    ChatModel chatModel;

    /**
     * Runs synthesis for a user. Extracts patterns, calls LLM, creates directives in CANDIDATE state,
     * then immediately transitions to SHADOW for evaluation.
     *
     * @return list of newly created directives (in SHADOW state)
     */
    public List<SynthesizedDirective> synthesize(String userId) {
        if (!config.enabled() || !config.directivesEnabled()) {
            log.debug("[PolicySynthesizer] Self-evolve or directives disabled — skipping synthesis");
            return List.of();
        }

        // Gather recent extracted memories
        List<ExtractedMemory> records = extractedStore.getByUser(userId, 50);
        if (records.isEmpty()) {
            log.debug("[PolicySynthesizer] No extracted memories for synthesis");
            return List.of();
        }

        // Filter to actionable types
        List<ExtractedMemory> actionable =
                records.stream().filter(r -> isActionableType(r.recordType())).toList();
        if (actionable.isEmpty()) {
            log.debug("[PolicySynthesizer] No actionable memories for synthesis");
            return List.of();
        }

        // Build synthesis prompt
        String prompt = buildSynthesisPrompt(actionable);

        // Call LLM
        List<SynthesizedDirective> newDirectives;
        try {
            newDirectives = callLlmForSynthesis(userId, prompt);
        } catch (Exception e) {
            log.errorf("[PolicySynthesizer] LLM synthesis failed: %s", e.getMessage());
            return List.of();
        }

        // Persist and activate shadow lifecycle
        List<SynthesizedDirective> activated = new ArrayList<>();
        for (SynthesizedDirective d : newDirectives) {
            try {
                int inserted = directiveStore.insert(d);
                if (inserted > 0) {
                    SynthesizedDirective shadow = lifecycleManager.activateShadow(d);
                    activated.add(shadow);
                } else {
                    log.debugf(
                            "[PolicySynthesizer] Directive already exists (dedup): %s",
                            d.text().substring(0, Math.min(50, d.text().length())));
                }
            } catch (Exception e) {
                log.warnf("[PolicySynthesizer] Failed to persist directive: %s", e.getMessage());
            }
        }

        log.infof(
                "[PolicySynthesizer] Synthesized %d directives for user %s (%d actionable records)",
                activated.size(), userId, actionable.size());
        return activated;
    }

    /**
     * Builds the directive bundle for prompt injection.
     * Limits to max directives, separates ACTIVE from SHADOW.
     */
    public DirectiveBundle buildDirectives(String userId) {
        if (!config.enabled() || !config.directivesEnabled()) {
            return new DirectiveBundle(List.of(), List.of(), 0);
        }

        int maxDirectives = 5; // from config constraint
        List<SynthesizedDirective> injectable = directiveStore.getInjectable(userId, maxDirectives * 2);

        List<SynthesizedDirective> active = injectable.stream()
                .filter(d -> d.state() == DirectiveState.ACTIVE && d.isInjectable())
                .limit(maxDirectives)
                .toList();

        int remainingSlots = maxDirectives - active.size();
        List<SynthesizedDirective> shadow = injectable.stream()
                .filter(d -> d.state() == DirectiveState.SHADOW && d.isInjectable())
                .limit(remainingSlots)
                .toList();

        return new DirectiveBundle(active, shadow, injectable.size());
    }

    private List<SynthesizedDirective> callLlmForSynthesis(String userId, String prompt) {
        ChatMessage systemMsg = new SystemMessage(SYNTHESIS_SYSTEM_PROMPT);
        ChatMessage userMsg = new UserMessage(prompt);

        ChatRequest request = ChatRequest.builder()
                .messages(systemMsg, userMsg)
                .parameters(ChatRequestParameters.builder()
                        .temperature(0.3)
                        .maxOutputTokens(2000)
                        .build())
                .build();

        ChatResponse response = chatModel.chat(request);
        String output = response.aiMessage().text();

        return parseDirectives(userId, output);
    }

    private List<SynthesizedDirective> parseDirectives(String userId, String llmOutput) {
        List<SynthesizedDirective> directives = new ArrayList<>();
        // Parse JSON array from LLM output
        String json = llmOutput.trim();
        if (!json.startsWith("[")) {
            int start = json.indexOf('[');
            int end = json.lastIndexOf(']');
            if (start >= 0 && end > start) {
                json = json.substring(start, end + 1);
            } else {
                log.warnf(
                        "[PolicySynthesizer] Could not parse LLM output as JSON array: %s",
                        llmOutput.substring(0, Math.min(200, llmOutput.length())));
                return directives;
            }
        }

        // Simple JSON parsing — extract text/confidence pairs
        String[] items = json.split("\\}\\s*,\\s*\\{");
        for (String item : items) {
            try {
                String clean = item.replaceAll("[\\[\\]{}]", "").trim();
                String text = extractJsonValue(clean, "text");
                String confStr = extractJsonValue(clean, "confidence");
                double confidence = confStr != null ? Double.parseDouble(confStr) : 0.7;

                if (text != null && !text.isBlank() && text.length() <= 600) {
                    SynthesizedDirective d = SynthesizedDirective.create(userId, text, confidence, "", "");
                    directives.add(d);
                }
            } catch (Exception e) {
                log.debugf("[PolicySynthesizer] Skipped malformed directive item: %s", e.getMessage());
            }
        }

        return directives;
    }

    private String extractJsonValue(String json, String key) {
        int start = json.indexOf("\"" + key + "\"");
        if (start < 0) return null;
        start = json.indexOf(":", start);
        if (start < 0) return null;
        start++;
        // Skip whitespace
        while (start < json.length() && json.charAt(start) == ' ') start++;
        if (start >= json.length()) return null;
        if (json.charAt(start) == '"') {
            // String value
            start++;
            int end = json.indexOf("\"", start);
            if (end < 0) return null;
            return json.substring(start, end);
        } else {
            // Numeric value
            int end = start;
            while (end < json.length() && (Character.isDigit(json.charAt(end)) || json.charAt(end) == '.')) end++;
            return json.substring(start, end);
        }
    }

    private String buildSynthesisPrompt(List<ExtractedMemory> records) {
        StringBuilder sb = new StringBuilder();
        sb.append("Synthesize behavioral directives from these extracted memory patterns:\n\n");
        for (ExtractedMemory r : records) {
            sb.append("- [")
                    .append(r.recordType())
                    .append("] ")
                    .append(r.content())
                    .append(" (confidence: ")
                    .append(String.format("%.1f", r.confidence()))
                    .append(")\n");
        }
        sb.append("\nExisting directives (avoid duplicates):\n");
        List<SynthesizedDirective> existing =
                directiveStore.getAll(records.getFirst().userId(), 20);
        for (SynthesizedDirective d : existing) {
            sb.append("- [").append(d.state()).append("] ").append(d.text()).append("\n");
        }
        return sb.toString();
    }

    private boolean isActionableType(MemoryRecordType type) {
        return switch (type) {
            case FAILURE_PATTERN,
                    TOOL_HINT,
                    USER_PREFERENCE,
                    RESOLUTION_PATTERN,
                    WORKAROUND,
                    TOOL_MISUSE_PATTERN,
                    ERROR_CONTEXT -> true;
            case ACTIVE_TASK, USER_PROFILE, PROJECT_CONTEXT, DIRECTIVE, SUMMARY, FACT -> false;
        };
    }
}
