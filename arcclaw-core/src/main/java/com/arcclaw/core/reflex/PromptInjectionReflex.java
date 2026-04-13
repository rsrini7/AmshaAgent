package com.arcclaw.core.reflex;

import java.util.List;
import java.util.regex.Pattern;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Default;
import jakarta.inject.Inject;

import com.arcclaw.core.audit.SecurityEventType;
import com.arcclaw.core.message.UserMessages;
import com.arcclaw.core.pipeline.MessageContext;
import com.arcclaw.core.security.InputSanitizer;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

@ApplicationScoped
@Default
@InputSanitizer.Injection
@ReflexPriority(10)
public class PromptInjectionReflex implements AgentReflex, InputSanitizer {

    @ConfigProperty(name = "arcclaw.security.prompt-injection.enabled", defaultValue = "true")
    boolean enabled;

    @Inject
    Logger log;

    private static final Pattern HUMAN_APPROVED = Pattern.compile("(?i)human[\\s\\-]?approv(ed|al)");
    private static final Pattern AUTO_APPROVE = Pattern.compile("(?i)auto[\\s\\-]?approv(e|ed|al)");
    private static final Pattern SKIP_APPROVAL = Pattern
            .compile("(?i)(skip|bypass|ignore)\\s+(approval|confirmation|review|governance|security)");
    private static final Pattern THIS_IS_APPROVED = Pattern
            .compile("(?i)(this\\s+(has\\s+been|is)\\s+approved|already\\s+approved|pre[\\s\\-]?approved)");

    private static final Pattern REVEAL_PROMPT = Pattern.compile(
            "(?i)(reveal|print|show|output|display|repeat)\\s+(your\\s+|the\\s+)?(system\\s+prompt|instructions?|training|context|rules?)");
    private static final Pattern WHAT_ARE_INSTRUCTIONS = Pattern
            .compile("(?i)what\\s+(are|were)\\s+(your\\s+)?(instructions?|system\\s+prompt|rules?|training)");
    private static final Pattern REPEAT_EVERYTHING = Pattern
            .compile("(?i)repeat\\s+(everything|all|your\\s+(system|instructions?|prompt))");

    private static final Pattern YOU_ARE_NOW = Pattern
            .compile("(?i)you\\s+are\\s+now\\s+(a|an|DAN|unrestricted|jailbroken|free)");
    private static final Pattern YOU_ARE_NO_LONGER = Pattern
            .compile("(?i)you\\s+are\\s+no\\s+longer\\s+(an?\\s+)?(AI|assistant|restricted|limited)");
    private static final Pattern PRETEND = Pattern
            .compile("(?i)pretend\\s+(you\\s+are|to\\s+be|you're)\\s+(a|an|not|unrestricted)");
    private static final Pattern ACT_AS = Pattern.compile("(?i)\\bact\\s+as\\s+(if\\s+you\\s+(are|were)|a|an)\\b");
    private static final Pattern DEVELOPER_MODE = Pattern.compile(
            "(?i)(switch|enable|activate|enter)\\s+(to\\s+)?(developer|jailbreak|DAN|unrestricted|god)\\s+mode");
    private static final Pattern FROM_NOW_ON = Pattern
            .compile("(?i)from\\s+now\\s+on\\s+(you|respond|act|behave|ignore)");

    private static final Pattern IGNORE_INSTRUCTIONS = Pattern
            .compile("(?i)ignore\\s+(all\\s+)?(previous\\s+|prior\\s+|above\\s+|your\\s+|the\\s+)*instructions?");
    private static final Pattern DISREGARD = Pattern
            .compile("(?i)disregard\\s+(all\\s+)?(previous\\s+|prior\\s+|your\\s+|the\\s+)*instructions?");
    private static final Pattern FORGET_TRAINING = Pattern
            .compile("(?i)forget\\s+(everything|all|your\\s+training|your\\s+instructions)");
    private static final Pattern NEW_INSTRUCTIONS = Pattern
            .compile("(?i)(your\\s+new|new\\s+system)\\s+instructions?\\s+(are|is|follow)");
    private static final Pattern OVERRIDE_PROMPT = Pattern
            .compile("(?i)override\\s+(system|previous|all|the)\\s+(prompt|instructions?|context|rules?)");

    // In PromptInjectionReflex, add to the role-override / context-wipe category:
    private static final Pattern CONTEXT_WIPE = Pattern.compile(
            "(?i)(forget\\s+(all|everything|what|previous|prior)|"
                    + "ignore\\s+(all|previous|prior|above)|"
                    + "disregard\\s+(all|previous|everything)|"
                    + "clear\\s+(your\\s+)?(memory|context|history))",
            Pattern.CASE_INSENSITIVE);

    private record InjectionRule(Pattern pattern, String category) {
    }

    private static final List<InjectionRule> RULES = List.of(
            // Category 3 — ArcClaw Governance Bypass (project-specific)
            new InjectionRule(HUMAN_APPROVED, "GOVERNANCE_BYPASS"),
            new InjectionRule(AUTO_APPROVE, "GOVERNANCE_BYPASS"),
            new InjectionRule(SKIP_APPROVAL, "GOVERNANCE_BYPASS"),
            new InjectionRule(THIS_IS_APPROVED, "GOVERNANCE_BYPASS"),

            // Category 4 — Exfiltration Probes
            new InjectionRule(REVEAL_PROMPT, "EXFILTRATION"),
            new InjectionRule(WHAT_ARE_INSTRUCTIONS, "EXFILTRATION"),
            new InjectionRule(REPEAT_EVERYTHING, "EXFILTRATION"),

            // Category 2 — Identity/Role Hijacking
            new InjectionRule(YOU_ARE_NOW, "IDENTITY_HIJACKING"),
            new InjectionRule(YOU_ARE_NO_LONGER, "IDENTITY_HIJACKING"),
            new InjectionRule(PRETEND, "IDENTITY_HIJACKING"),
            new InjectionRule(ACT_AS, "IDENTITY_HIJACKING"),
            new InjectionRule(DEVELOPER_MODE, "IDENTITY_HIJACKING"),
            new InjectionRule(FROM_NOW_ON, "IDENTITY_HIJACKING"),

            // Category 1 — Direct Override Attempts
            new InjectionRule(IGNORE_INSTRUCTIONS, "DIRECT_OVERRIDE"),
            new InjectionRule(DISREGARD, "DIRECT_OVERRIDE"),
            new InjectionRule(FORGET_TRAINING, "DIRECT_OVERRIDE"),
            new InjectionRule(NEW_INSTRUCTIONS, "DIRECT_OVERRIDE"),
            new InjectionRule(OVERRIDE_PROMPT, "DIRECT_OVERRIDE"),

            // Category 5 — Context Wipe Attempts ← ADD THIS
            new InjectionRule(CONTEXT_WIPE, "CONTEXT_WIPE_ATTEMPT"));

    @Override
    public boolean matches(String input) {
        if (!enabled)
            return false;
        if (input == null || input.isBlank())
            return false;
        for (InjectionRule rule : RULES) {
            if (rule.pattern().matcher(input).find()) {
                return true;
            }
        }
        return false;
    }

    @Override
    public int priority() {
        return 10;
    }

    @Override
    public SecurityEventType auditEventType() {
        return SecurityEventType.PROMPT_INJECTION_BLOCKED;
    }

    @Override
    public String auditDetail(String sessionId, String input) {
        // First 200 chars only — do not log full injection attempt
        String truncated = input.length() > 200 ? input.substring(0, 200) + "…" : input;
        return "inputPreview=" + truncated;
    }

    @Override
    public ReflexResult execute(String sessionId, String userId, String chatId, String input, MessageContext context) {
        String matchedCategory = "UNKNOWN";
        if (input != null && !input.isBlank()) {
            matchedCategory = RULES.stream()
                    .filter(r -> r.pattern().matcher(input).find())
                    .map(InjectionRule::category)
                    .findFirst()
                    .orElse("UNKNOWN");
        }

        int inputLength = input != null ? input.length() : 0;
        log.warnf("[INJECTION-BLOCKED] session=%s user=%s category=%s input_length=%d",
                sessionId, userId, matchedCategory, inputLength);

        String output = UserMessages.PROMPT_INJECTION_DETECTED;
        return ReflexResult.respond(output, context);
    }

    @Override
    public String sanitize(String sessionId, String userId, String input) {
        // No-op — PromptInjectionReflex only operates in Pass 1
        return input;
    }

    public boolean containsInjection(String input) {
        if (input == null || input.isBlank())
            return false;
        for (InjectionRule rule : RULES) {
            if (rule.pattern().matcher(input).find())
                return true;
        }
        return false;
    }

    @Override
    public String sanitize(String input) {
        return input;
    }
}
