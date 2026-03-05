package com.arcclaw.core.reflex;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@ApplicationScoped
public class PiiScanReflex implements AgentReflex {

    public enum PiiMode {
        REDACT_AND_FORWARD,  // Option B — sanitize silently, pass to LLM
        BLOCK_AND_NOTIFY     // Option A — block message, notify user
    }

    @ConfigProperty(name = "arcclaw.pii.mode", defaultValue = "REDACT_AND_FORWARD")
    PiiMode piiMode;

    @Inject
    Logger log;

    // Compiled Patterns
    private static final Pattern EMAIL = Pattern.compile("[a-zA-Z0-9._%+\\-]+@[a-zA-Z0-9.\\-]+\\.[a-zA-Z]{2,}");
    private static final Pattern PHONE_INDIA = Pattern.compile("(?<![\\w])(?:\\+91[\\-\\s]?)?[6-9]\\d{9}(?![\\w])");
    private static final Pattern PHONE_INTL  = Pattern.compile("(?<![\\w@.])\\+[1-9]\\d{6,14}(?![\\w])");
    private static final Pattern SSN = Pattern.compile("\\d{3}-\\d{2}-\\d{4}");
    private static final Pattern AADHAAR_SPACED = Pattern.compile("[2-9]{1}[0-9]{3}\\s[0-9]{4}\\s[0-9]{4}");
    private static final Pattern AADHAAR_COMPACT = Pattern.compile("(?<![+\\-\\d])[2-9][0-9]{11}(?!\\d)");
    private static final Pattern PAN = Pattern.compile("[A-Z]{5}[0-9]{4}[A-Z]{1}");
    private static final Pattern CREDIT_CARD = Pattern.compile("\\b(?:\\d{4}[\\s\\-]?){3}\\d{4}\\b");
    private static final Pattern IPV4 = Pattern.compile("\\b(?:\\d{1,3}\\.){3}\\d{1,3}\\b");
    private static final Pattern IPV6 = Pattern.compile("(?i)\\b(?:[a-f0-9]{1,4}:){7}[a-f0-9]{1,4}\\b");
    private static final Pattern JWT = Pattern.compile("eyJ[a-zA-Z0-9_\\-]+\\.eyJ[a-zA-Z0-9_\\-]+\\.[a-zA-Z0-9_\\-]+");
    private static final Pattern DATE_OF_BIRTH = Pattern.compile("(?i)(?:DOB|Date\\s*of\\s*Birth|Birthdate)[\\s:=-]*(?:\\d{4}-\\d{2}-\\d{2}|\\d{2}-\\d{2}-\\d{4})");
    private static final Pattern PASSPORT_IN = Pattern.compile("(?<![\\w])[A-Z][0-9]{7}(?![\\w])");
    private static final Pattern GSTIN = Pattern.compile("(?<![\\w])[0-9]{2}[A-Z]{5}[0-9]{4}[A-Z]{1}[1-9A-Z]{1}Z[0-9A-Z]{1}(?![\\w])");

    // UPI VPA — runs AFTER EMAIL to avoid double-redaction.
    // Negative lookahead excludes common email TLDs.
    private static final Pattern UPI_VPA = Pattern.compile(
        "\\b[a-zA-Z0-9.\\-_]{2,256}@(?![^@]*\\.(com|in|org|net|io|co\\.in)\\b)[a-zA-Z]{2,64}\\b"
    );

    private record PiiRule(Pattern pattern, String type, String replacement) {}

    private static final List<PiiRule> RULES = List.of(
        new PiiRule(JWT, "JWT", "[REDACTED-JWT]"),
        new PiiRule(EMAIL, "EMAIL", "[REDACTED-EMAIL]"),
        new PiiRule(CREDIT_CARD, "CARD", "[REDACTED-CARD]"),
        new PiiRule(AADHAAR_SPACED, "AADHAAR", "[REDACTED-AADHAAR]"),
        new PiiRule(SSN, "SSN", "[REDACTED-SSN]"),
        // Order Fix Phase 41: GSTIN must be before PAN because PAN is a subset of GSTIN
        new PiiRule(GSTIN, "GSTIN", "[REDACTED-GSTIN]"),
        new PiiRule(PAN, "PAN", "[REDACTED-PAN]"),
        // Order Fix Phase 41: Phone must be before Aadhaar because Indian mobile numbers with +91 can look like 12-digit Aadhaar
        new PiiRule(PHONE_INDIA, "PHONE", "[REDACTED-PHONE]"),
        new PiiRule(PHONE_INTL, "PHONE", "[REDACTED-PHONE]"),
        new PiiRule(AADHAAR_COMPACT, "AADHAAR", "[REDACTED-AADHAAR]"),
        new PiiRule(PASSPORT_IN, "PASSPORT", "[REDACTED-PASSPORT]"),
        new PiiRule(DATE_OF_BIRTH, "DOB", "[REDACTED-DOB]"),
        new PiiRule(IPV4, "IP", "[REDACTED-IP]"),
        new PiiRule(IPV6, "IP", "[REDACTED-IP]"),
        new PiiRule(UPI_VPA, "UPI", "[REDACTED-UPI]")
    );

    // Private detection — mode-independent
    @Override
    public boolean matches(String input) {
        return piiMode == PiiMode.BLOCK_AND_NOTIFY && containsPii(input);
    }

    @Override
    public String execute(String sessionId, String userId, String chatId, String input) {
        // Only reached in BLOCK_AND_NOTIFY mode
        return "⚠️ PII detected. Message has been sanitized and blocked.";
    }

    @Override
    public String sanitize(String sessionId, String userId, String input) {
        // Always runs in Pass 2 regardless of mode
        // In BLOCK_AND_NOTIFY mode this is a no-op since Pass 1 already blocked
        if (input == null || input.isBlank()) return input;

        String result = input;
        List<String> detectedTypes = new ArrayList<>();
        boolean redacted = false;

        for (PiiRule rule : RULES) {
            Matcher m = rule.pattern().matcher(result);
            if (m.find()) {
                result = m.replaceAll(rule.replacement());
                if (!detectedTypes.contains(rule.type())) {
                    detectedTypes.add(rule.type());
                }
                redacted = true;
            }
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

}
