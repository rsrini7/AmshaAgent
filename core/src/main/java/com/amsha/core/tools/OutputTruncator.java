package com.amsha.core.tools;

public class OutputTruncator {

    private final int maxOutputChars;
    private static final String TRUNCATION_SUFFIX = "[output truncated at max output chars limit]";
    private static final String NO_OUTPUT_SENTINEL = "[Execution completed successfully - no output produced]";

    public OutputTruncator(int maxOutputChars) {
        this.maxOutputChars = maxOutputChars;
    }

    public String truncate(String output) {
        if (output == null || output.isBlank()) return NO_OUTPUT_SENTINEL;
        if (output.length() <= maxOutputChars) return output;
        int endIndex =
                output.offsetByCodePoints(0, Math.min(output.codePointCount(0, output.length()), maxOutputChars));
        return output.substring(0, endIndex) + TRUNCATION_SUFFIX;
    }
}
