package com.arcclaw.channel.api;

import java.util.Set;

/**
 * Defines markdown escaping profiles for different channels.
 */
public enum MarkdownProfile {
    TELEGRAM_V2(Set.of('_', '*', '[', ']', '(', ')', '~', '`', '>', '#', '+', '-', '=', '|', '{', '}', '.', '!'));

    private final Set<Character> escapeChars;

    MarkdownProfile(Set<Character> escapeChars) {
        this.escapeChars = escapeChars;
    }

    public Set<Character> getEscapeChars() {
        return escapeChars;
    }
}
