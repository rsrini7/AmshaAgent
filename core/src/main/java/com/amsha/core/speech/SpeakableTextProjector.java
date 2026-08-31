package com.amsha.core.speech;

import java.util.List;
import java.util.regex.Pattern;

/**
 * Pure projection of a user-visible formatted answer into speakable plain text.
 *
 * <p>Removes Markdown syntax, headings, tables, fenced-code markers, URLs, HTML tags and artifact
 * notices, and unsuitable emoji, then collapses whitespace so the result reads as prose.
 *
 * <p>Performs no I/O and holds no state. Only the final answer is ever projected here: reasoning
 * traces, tool output, raw transcripts, and internal metadata must never be passed to this class.
 */
public final class SpeakableTextProjector {

    private static final List<Step> STEPS = List.of(
            new Step(Pattern.compile("(?s)```.*?```"), " "),
            new Step(
                    Pattern.compile("(?i)(?:html\\s+artifact|artifact\\s+saved|open\\s+in\\s+browser)[^\\n.]{0,120}"),
                    " "),
            new Step(Pattern.compile("(?s)<[^>]{0,300}>"), " "),
            new Step(Pattern.compile("!\\[[^\\]]{0,200}\\]\\([^)]{0,500}\\)"), " "),
            new Step(Pattern.compile("\\[([^\\]]{0,200})\\]\\([^)]{0,500}\\)"), "$1"),
            new Step(Pattern.compile("(?i)https?://\\S+"), " "),
            new Step(Pattern.compile("(?i)\\bwww\\.\\S+"), " "),
            new Step(Pattern.compile("(?m)^\\s{0,3}#{1,6}\\s+"), ""),
            new Step(Pattern.compile("(?m)^\\s{0,3}>\\s?"), ""),
            new Step(Pattern.compile("(?m)^\\s*\\|?[\\s:|-]{3,}\\|?\\s*$"), " "),
            new Step(Pattern.compile("\\|"), " "),
            new Step(Pattern.compile("(?m)^\\s{0,3}[-*+]\\s+"), ""),
            new Step(Pattern.compile("(?m)^\\s{0,3}\\d+[.)]\\s+"), ""),
            new Step(Pattern.compile("(\\*\\*|__)(.{0,500}?)\\1"), "$2"),
            new Step(Pattern.compile("(?<!\\w)(\\*|_)(.{0,500}?)\\1(?!\\w)"), "$2"),
            new Step(Pattern.compile("~~(.{0,500}?)~~"), "$1"),
            new Step(
                    Pattern.compile("[\\x{1F000}-\\x{1FAFF}\\x{2600}-\\x{27BF}\\x{2B00}-\\x{2BFF}"
                            + "\\x{2190}-\\x{21FF}\\x{2300}-\\x{23FF}\\x{FE0F}\\x{200D}]"),
                    " "),
            new Step(Pattern.compile("\\s+"), " "));

    private SpeakableTextProjector() {}

    /** Projects a formatted answer to speakable prose. Returns "" for null or blank input. */
    public static String project(String formattedAnswer) {
        if (formattedAnswer == null || formattedAnswer.isBlank()) {
            return "";
        }
        String text = formattedAnswer;
        for (Step step : STEPS) {
            text = step.pattern().matcher(text).replaceAll(step.replacement());
        }
        return text.trim();
    }

    /**
     * Truncates projected text to a bound of Unicode code points, preferring the last sentence
     * terminator within the trailing portion so the spoken result does not end mid-sentence.
     *
     * <p>The bound is a code-point count, never a {@code char} count, so a multi-{@code char}
     * surrogate pair (emoji, astral symbols) is never split. The search for a sentence terminator
     * walks {@code char} indices, which is safe because the terminators ({@code . ! ?}) are
     * Basic Multilingual Plane characters and a low surrogate never matches them.
     */
    public static String truncate(String text, int maxChars) {
        if (text == null || text.isBlank()) {
            return "";
        }
        if (maxChars <= 0) {
            return "";
        }
        int codePointCount = text.codePointCount(0, text.length());
        if (codePointCount <= maxChars) {
            return text;
        }
        int cutEnd = text.offsetByCodePoints(0, maxChars);
        String cut = text.substring(0, cutEnd);
        // Mirror the code-point bound as a char offset for the terminator search window.
        int searchFromCodePoints = Math.max(0, maxChars - Math.min(maxChars / 2, 400));
        int searchFrom = text.offsetByCodePoints(0, searchFromCodePoints);
        for (int i = cut.length() - 1; i >= searchFrom; i--) {
            char candidate = cut.charAt(i);
            if (candidate == '.' || candidate == '!' || candidate == '?') {
                return cut.substring(0, i + 1).trim();
            }
        }
        return cut.trim();
    }

    private record Step(Pattern pattern, String replacement) {}
}
