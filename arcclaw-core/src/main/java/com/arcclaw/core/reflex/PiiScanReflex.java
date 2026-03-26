package com.arcclaw.core.reflex;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import com.arcclaw.core.pipeline.MessageContext;
import com.arcclaw.core.security.InputSanitizer;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import jakarta.enterprise.inject.Default;
import org.jboss.logging.Logger;

@ApplicationScoped
@Default
@InputSanitizer.PII
public class PiiScanReflex implements AgentReflex, InputSanitizer {

    public enum PiiMode {
        REDACT_AND_FORWARD,
        BLOCK_AND_NOTIFY
    }

    @ConfigProperty(name = "arcclaw.pii.mode", defaultValue = "REDACT_AND_FORWARD")
    PiiMode piiMode;

    @Inject
    Logger log;

    private static final Pattern EMAIL          = Pattern.compile("[a-zA-Z0-9._%+\\-]+@[a-zA-Z0-9.\\-]+\\.[a-zA-Z]{2,}");
    private static final Pattern PHONE_INDIA    = Pattern.compile("(?<![\\w])(?:\\+91[\\-\\s]?)?[6-9]\\d{9}(?![\\w])");
    private static final Pattern PHONE_INTL     = Pattern.compile("(?<![\\w@.])\\+[1-9]\\d{6,14}(?![\\w])");
    private static final Pattern SSN            = Pattern.compile("\\d{3}-\\d{2}-\\d{4}");
    private static final Pattern AADHAAR_SPACED = Pattern.compile("[2-9]{1}[0-9]{3}\\s[0-9]{4}\\s[0-9]{4}");
    private static final Pattern AADHAAR_COMPACT= Pattern.compile("(?<![+\\-\\d])[2-9][0-9]{11}(?!\\d)");
    private static final Pattern PAN            = Pattern.compile("[A-Z]{5}[0-9]{4}[A-Z]{1}");
    private static final Pattern CREDIT_CARD    = Pattern.compile("\\b(?:\\d{4}[\\s\\-]?){3}\\d{4}\\b");
    private static final Pattern IPV4           = Pattern.compile("\\b(?:\\d{1,3}\\.){3}\\d{1,3}\\b");
    private static final Pattern IPV6           = Pattern.compile("(?i)\\b(?:[a-f0-9]{1,4}:){7}[a-f0-9]{1,4}\\b");
    private static final Pattern JWT            = Pattern.compile("eyJ[a-zA-Z0-9_\\-]+\\.eyJ[a-zA-Z0-9_\\-]+\\.[a-zA-Z0-9_\\-]+");
    private static final Pattern DATE_OF_BIRTH  = Pattern.compile("(?i)(?:DOB|Date\\s*of\\s*Birth|Birthdate)[\\s:=-]*(?:\\d{4}-\\d{2}-\\d{2}|\\d{2}-\\d{2}-\\d{4})");
    private static final Pattern PASSPORT_IN    = Pattern.compile("(?<![\\w])[A-Z][0-9]{7}(?![\\w])");
    private static final Pattern GSTIN          = Pattern.compile("(?<![\\w])[0-9]{2}[A-Z]{5}[0-9]{4}[A-Z]{1}[1-9A-Z]{1}Z[0-9A-Z]{1}(?![\\w])");
    private static final Pattern UPI_VPA        = Pattern.compile(
        "\\b[a-zA-Z0-9.\\-_]{2,256}@(?![^@]*\\.(com|in|org|net|io|co\\.in)\\b)[a-zA-Z]{2,64}\\b"
    );

    // Matches markdown links: [display](href) or [display](mailto:href)
    private static final Pattern MARKDOWN_LINK  = Pattern.compile(
        "\\[([^\\]]+)]\\((?:mailto:)?([^)]+)\\)"
    );

    // Matches full UUIDs — used to protect them from card/phone pattern false positives
    private static final Pattern UUID_PATTERN   = Pattern.compile(
        "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}"
    );

    private record PiiRule(Pattern pattern, String type, String replacement) {}

    private static final List<PiiRule> RULES = List.of(
        new PiiRule(JWT,             "JWT",     "[REDACTED-JWT]"),
        new PiiRule(EMAIL,           "EMAIL",   "[REDACTED-EMAIL]"),
        new PiiRule(CREDIT_CARD,     "CARD",    "[REDACTED-CARD]"),
        new PiiRule(AADHAAR_SPACED,  "AADHAAR", "[REDACTED-AADHAAR]"),
        new PiiRule(SSN,             "SSN",     "[REDACTED-SSN]"),
        // GSTIN before PAN — PAN is a subset of GSTIN
        new PiiRule(GSTIN,           "GSTIN",   "[REDACTED-GSTIN]"),
        new PiiRule(PAN,             "PAN",     "[REDACTED-PAN]"),
        // Phone before Aadhaar — +91 numbers can look like 12-digit Aadhaar
        new PiiRule(PHONE_INDIA,     "PHONE",   "[REDACTED-PHONE]"),
        new PiiRule(PHONE_INTL,      "PHONE",   "[REDACTED-PHONE]"),
        new PiiRule(AADHAAR_COMPACT, "AADHAAR", "[REDACTED-AADHAAR]"),
        new PiiRule(
            Pattern.compile("(?<!\\d)(?<!-)(?<![./])\\d{10}(?!\\d)(?!-)"), "PHONE",
                        "[REDACTED-PHONE]"
        ),
        new PiiRule(PASSPORT_IN,     "PASSPORT","[REDACTED-PASSPORT]"),
        new PiiRule(DATE_OF_BIRTH,   "DOB",     "[REDACTED-DOB]"),
        new PiiRule(IPV4,            "IP",      "[REDACTED-IP]"),
        new PiiRule(IPV6,            "IP",      "[REDACTED-IP]"),
        new PiiRule(UPI_VPA,         "UPI",     "[REDACTED-UPI]")
    );

    /** Triggers block mode only — sanitize() always runs in Pass 2 regardless. */
    @Override
    public boolean matches(String input) {
        return piiMode == PiiMode.BLOCK_AND_NOTIFY && containsPii(input);
    }

    @Override
    public boolean emitsSanitizeAudit() {
        return true;
    }

    @Override
    public String auditDetail(String sessionId, String input) {
        return "mode=" + piiMode.name() + " inputLength=" + input.length();
    }

    @Override
    public ReflexResult execute(String sessionId, String userId, String chatId,
            String input, MessageContext context) {
        return ReflexResult.respond("⚠️ PII detected. Message has been sanitized and blocked.", context);
    }

    /** Sanitizes input: strips markdown links, masks UUIDs, applies PII rules, restores UUIDs. */
    @Override
    public String sanitize(String sessionId, String userId, String input) {
        if (input == null || input.isBlank()) return input;

        // Step 1 — Flatten markdown links: [display](mailto:href) → display
        // This prevents email in display text surviving redaction
        String result = MARKDOWN_LINK.matcher(input).replaceAll(mr -> mr.group(1));

        // Step 2 — Mask UUIDs temporarily so card/phone patterns don't match inside them
        List<String> uuidPlaceholders = new ArrayList<>();
        Matcher uuidMatcher = UUID_PATTERN.matcher(result);
        StringBuffer uuidProtected = new StringBuffer();
        while (uuidMatcher.find()) {
            String placeholder = "UUID_PLACEHOLDER_" + uuidPlaceholders.size() + "_";
            uuidPlaceholders.add(uuidMatcher.group());
            uuidMatcher.appendReplacement(uuidProtected,
                    Matcher.quoteReplacement(placeholder));
        }
        uuidMatcher.appendTail(uuidProtected);
        result = uuidProtected.toString();

        // Step 3 — Apply PII redaction rules
        List<String> detectedTypes = new ArrayList<>();
        boolean redacted = false;
        for (PiiRule rule : RULES) {
            Matcher m = rule.pattern().matcher(result);
            if (m.find()) {
                result = m.replaceAll(rule.replacement());
                if (!detectedTypes.contains(rule.type())) detectedTypes.add(rule.type());
                redacted = true;
            }
        }

        // Step 4 — Restore UUIDs
        for (int i = 0; i < uuidPlaceholders.size(); i++) {
            result = result.replace("UUID_PLACEHOLDER_" + i + "_", uuidPlaceholders.get(i));
        }

        if (redacted) {
            log.warnf("[PII-REDACTED] session=%s user=%s mode=%s types=%s " +
                      "original_length=%d sanitized_length=%d",
                    sessionId, userId, piiMode,
                    String.join(",", detectedTypes),
                    input.length(), result.length());
        }

        return result;
    }

    /** Public detection check — mode independent. */
    public boolean detectsPii(String input) {
        return containsPii(input);
    }

    private boolean containsPii(String input) {
        if (input == null || input.isBlank()) return false;
        for (PiiRule rule : RULES) {
            if (rule.pattern().matcher(input).find()) return true;
        }
        return false;
    }

    /**
     * Pure PII redaction — always redacts regardless of piiMode.
     * Used by ToolObservationSanitizer and ChatMemoryWriter to scrub
     * tool outputs and LLM answers before they enter agent history.
     */
    public String redactPii(String input) {
        if (input == null || input.isBlank()) return input;
        // Flatten markdown links first
        String result = MARKDOWN_LINK.matcher(input).replaceAll(mr -> mr.group(1));
        for (PiiRule rule : RULES) {
            result = rule.pattern().matcher(result).replaceAll(rule.replacement());
        }
        return result;
    }

    @Override
    public String sanitize(String input) {
        return redactPii(input);
    }
}
