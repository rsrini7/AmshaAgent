package com.amsha.channel.api;

import java.util.Set;

/**
 * Shared service for escaping markdown text across different channels.
 */
public class MarkdownEscapeService {

    /**
     * Escapes mandatory characters for the specified markdown profile.
     */
    public String escape(String text, MarkdownProfile profile) {
        if (text == null || text.isEmpty()) return "";
        Set<Character> escapeChars = profile.getEscapeChars();
        StringBuilder sb = new StringBuilder(text.length() + 16);
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);

            // Check if this is an existing escape sequence
            if (c == '\\' && i + 1 < text.length()) {
                char next = text.charAt(i + 1);
                if (escapeChars.contains(next) || next == '\\') {
                    sb.append('\\').append(next);
                    i++; // skip the escaped character
                    continue;
                }
            }

            // If it's a lone backslash, escape it
            if (c == '\\') {
                sb.append('\\').append('\\');
                continue;
            }

            if (escapeChars.contains(c)) sb.append('\\');
            sb.append(c);
        }
        return sb.toString();
    }
}
