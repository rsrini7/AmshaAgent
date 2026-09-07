package com.amsha.core.agent.trace;

import jakarta.enterprise.context.ApplicationScoped;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Server-side redaction and payload bounding for {@link AgentTraceEvent}.
 *
 * <p>Redaction runs <strong>on the server, before publication</strong>. The browser never
 * receives a raw prompt, completion, secret, authorization header, arbitrary tool parameter or
 * audit key, so the UI cannot accidentally render one and a client-side bypass is impossible.
 *
 * <p>The transformation is deterministic: the same input always produces the same output, which
 * keeps trace de-duplication and tests stable. It is also pure — the input event is never
 * mutated, a redacted copy is returned.
 */
@ApplicationScoped
public class TraceRedactor {

    /** Replacement marker emitted in place of any removed value. */
    public static final String REDACTED = "[REDACTED]";

    /** Marker appended when a value exceeded its byte budget. */
    public static final String TRUNCATED = "[TRUNCATED]";

    /** Maximum serialized size of the free-text {@code detail} field, in UTF-8 bytes. */
    public static final int MAX_DETAIL_BYTES = 512;

    /** Maximum serialized size of {@code status}, in UTF-8 bytes. */
    public static final int MAX_STATUS_BYTES = 64;

    /** Maximum serialized size of {@code riskLevel}, in UTF-8 bytes. */
    public static final int MAX_RISK_LEVEL_BYTES = 32;

    private static final int MAX_UTF8_SEQUENCE_BYTES = 4;

    /**
     * Any run of identifier characters around a sensitive token. Deliberately has no leading
     * {@code \b}: a boundary there would miss compound keys such as {@code system_prompt},
     * because {@code _} is a word character.
     */
    private static final String KEY = "[\\w.-]*";

    /**
     * A quoted value, or the remainder of the line. Unquoted values extend to end-of-line so an
     * unquoted dump can never survive partial matching.
     */
    private static final String VALUE = "(?:\"[^\"]*\"|'[^']*'|[^\\r\\n]*)";

    /**
     * Ordered, fail-closed rules. They are applied in declaration order; a broader rule never
     * runs before the narrower rule that must win. Every rule keeps the key visible and replaces
     * only the value, so an operator can still see which field was withheld.
     */
    private static final List<RedactionRule> RULES = List.of(
            new RedactionRule(Pattern.compile("(?i)(authorization\\s*[:=]\\s*)" + VALUE), "$1" + REDACTED),
            new RedactionRule(Pattern.compile("(?i)\\bBearer\\s+[A-Za-z0-9\\-._~+/]+=*"), "Bearer " + REDACTED),
            new RedactionRule(Pattern.compile("(?i)(x-amsha-api-key\\s*[:=]\\s*)" + VALUE), "$1" + REDACTED),
            new RedactionRule(
                    Pattern.compile("(?i)(" + KEY
                            + "(?:audit[_-]?key|hmac[_-]?key|signing[_-]?key)" + KEY
                            + "\\s*[:=]\\s*)" + VALUE),
                    "$1" + REDACTED),
            new RedactionRule(
                    Pattern.compile("(?i)(" + KEY
                            + "(?:api[_-]?key|access[_-]?token|refresh[_-]?token|client[_-]?secret|secret"
                            + "|password|passwd|pwd|private[_-]?key|credential)" + KEY
                            + "\\s*[:=]\\s*)" + VALUE),
                    "$1" + REDACTED),
            // Provider key literals embedded in prose, where no key: value structure exists.
            // The body charset includes '-' and '_' because real provider keys contain them
            // (for example sk-proj-...); a plain [A-Za-z0-9] body silently missed those.
            new RedactionRule(
                    Pattern.compile("\\b(?:sk|ghp|xoxb|xoxp|xoxs|github_pat)[-_][A-Za-z0-9_-]{12,}\\b"
                            + "|\\bAKIA[0-9A-Z]{12,}\\b"
                            + "|\\bAIza[0-9A-Za-z_-]{20,}\\b"),
                    REDACTED),
            new RedactionRule(
                    Pattern.compile("(?i)(" + KEY + "(?:param|argument|input)" + KEY + "\\s*[:=]\\s*)" + VALUE),
                    "$1" + REDACTED),
            new RedactionRule(
                    Pattern.compile("(?i)(" + KEY
                            + "(?:prompt|completion|(?:system|user|assistant)[_-]message)" + KEY
                            + "\\s*[:=]\\s*)" + VALUE),
                    "$1" + REDACTED));

    /**
     * Returns a redacted, bounded copy of {@code event}. The argument is never modified.
     *
     * @param event the event to sanitize, or {@code null}
     * @return the sanitized copy, or {@code null} when {@code event} is {@code null}
     */
    public AgentTraceEvent redact(AgentTraceEvent event) {
        if (event == null) {
            return null;
        }
        return event.withDetail(redactText(event.detail(), MAX_DETAIL_BYTES))
                .withStatus(redactText(event.status(), MAX_STATUS_BYTES))
                .withRiskLevel(redactText(event.riskLevel(), MAX_RISK_LEVEL_BYTES));
    }

    /**
     * Applies every redaction rule and then bounds the result to {@code maxBytes} UTF-8 bytes.
     *
     * @param value    the text to sanitize, or {@code null}
     * @param maxBytes the maximum UTF-8 size of the returned string
     * @return the sanitized, bounded text, or {@code null} when {@code value} is {@code null}
     */
    public String redactText(String value, int maxBytes) {
        if (value == null) {
            return null;
        }
        String redacted = value;
        for (RedactionRule rule : RULES) {
            redacted = rule.apply(redacted);
        }
        return capToBytes(redacted, maxBytes);
    }

    private static String capToBytes(String value, int maxBytes) {
        if (maxBytes <= 0) {
            return "";
        }
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        if (bytes.length <= maxBytes) {
            return value;
        }
        int budget = Math.max(0, maxBytes - TRUNCATED.getBytes(StandardCharsets.UTF_8).length);
        return decodePrefix(bytes, budget) + TRUNCATED;
    }

    /**
     * Decodes at most {@code length} bytes, backing off by up to a full UTF-8 sequence so a
     * multi-byte character is never split.
     */
    private static String decodePrefix(byte[] bytes, int length) {
        int floor = Math.max(0, length - MAX_UTF8_SEQUENCE_BYTES);
        for (int candidate = Math.min(length, bytes.length); candidate >= floor; candidate--) {
            CharsetDecoder decoder = StandardCharsets.UTF_8
                    .newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT);
            try {
                return decoder.decode(ByteBuffer.wrap(bytes, 0, candidate)).toString();
            } catch (CharacterCodingException _) {
                // Split a multi-byte sequence; retry with one byte less.
            }
        }
        return "";
    }

    /** A single deterministic pattern-and-replacement pair. */
    private record RedactionRule(Pattern pattern, String replacement) {
        String apply(String value) {
            return pattern.matcher(value).replaceAll(replacement);
        }
    }
}
