package com.arcclaw.core.reflex;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@ApplicationScoped
public class PiiScanReflex implements AgentReflex {

    @Inject
    Logger log;

    // Compiled Patterns
    private static final Pattern EMAIL = Pattern.compile("[a-zA-Z0-9._%+\\-]+@[a-zA-Z0-9.\\-]+\\.[a-zA-Z]{2,}");
    private static final Pattern PHONE_INDIA = Pattern.compile("(\\+91[\\-\\s]?)?[6-9]\\d{9}");
    private static final Pattern PHONE_INTL = Pattern.compile("\\+?[1-9]\\d{1,14}");
    private static final Pattern SSN = Pattern.compile("\\d{3}-\\d{2}-\\d{4}");
    private static final Pattern AADHAAR_SPACED = Pattern.compile("[2-9]{1}[0-9]{3}\\s[0-9]{4}\\s[0-9]{4}");
    private static final Pattern AADHAAR_COMPACT = Pattern.compile("(?<![+\\-\\d])[2-9][0-9]{11}(?!\\d)");
    private static final Pattern PAN = Pattern.compile("[A-Z]{5}[0-9]{4}[A-Z]{1}");
    private static final Pattern CREDIT_CARD = Pattern.compile("\\b(?:\\d{4}[\\s\\-]?){3}\\d{4}\\b");
    private static final Pattern IPV4 = Pattern.compile("\\b(?:\\d{1,3}\\.){3}\\d{1,3}\\b");
    private static final Pattern JWT = Pattern.compile("eyJ[a-zA-Z0-9_\\-]+\\.eyJ[a-zA-Z0-9_\\-]+\\.[a-zA-Z0-9_\\-]+");

    private record PiiRule(Pattern pattern, String type, String replacement) {}

    private static final List<PiiRule> RULES = List.of(
        new PiiRule(JWT, "JWT", "[REDACTED-JWT]"),
        new PiiRule(EMAIL, "EMAIL", "[REDACTED-EMAIL]"),
        new PiiRule(CREDIT_CARD, "CARD", "[REDACTED-CARD]"),
        new PiiRule(AADHAAR_SPACED, "AADHAAR", "[REDACTED-AADHAAR]"),
        new PiiRule(AADHAAR_COMPACT, "AADHAAR", "[REDACTED-AADHAAR]"),
        new PiiRule(SSN, "SSN", "[REDACTED-SSN]"),
        new PiiRule(PAN, "PAN", "[REDACTED-PAN]"),
        new PiiRule(IPV4, "IP", "[REDACTED-IP]"),
        new PiiRule(PHONE_INDIA, "PHONE", "[REDACTED-PHONE]"),
        new PiiRule(PHONE_INTL, "PHONE", "[REDACTED-PHONE]")
    );

    @Override
    public boolean matches(String input) {
        if (input == null || input.isBlank()) {
            return false;
        }
        for (PiiRule rule : RULES) {
            if (rule.pattern().matcher(input).find()) {
                return true;
            }
        }
        return false;
    }

    @Override
    public String execute(String sessionId, String userId) {
        return "⚠️ PII detected. Message has been sanitized.";
    }

    @Override
    public String sanitize(String sessionId, String userId, String input) {
        if (input == null || input.isBlank()) {
            return input;
        }

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
            log.warnf("[PII-REDACTED] session=%s user=%s types=%s original_length=%d sanitized_length=%d",
                    sessionId, userId, String.join(",", detectedTypes), input.length(), result.length());
        }

        return result;
    }
}
