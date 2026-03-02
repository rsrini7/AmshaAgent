package com.arcclaw.channel.api;

import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Channel-agnostic command parsed from a user's text message.
 *
 * <p>Commands always begin with {@code /}. The static {@link #parse(String)}
 * factory maps raw text to one of the sealed permits. Non-command messages
 * map to {@link Unknown}.
 *
 * <p>Tier 2B renderers depend on exhaustive switching over exactly these 7 types.
 * Do not add or remove permit types.
 */
public sealed interface ChannelCommand
        permits ChannelCommand.Approve,
                ChannelCommand.Deny,
                ChannelCommand.Status,
                ChannelCommand.Halt,
                ChannelCommand.Reset,
                ChannelCommand.ListTasks,
                ChannelCommand.Unknown {

    // Single compiled Pattern — avoids repeated compilation per message.
    // The inner group (\\S+) matches the first non-whitespace token after the command.
    Pattern CMD_PATTERN = Pattern.compile(
            "^/(approve|deny|status|halt|reset|list(?:tasks?)?)(?:\\s+(\\S+))?(?:\\s+.*)?",
            Pattern.CASE_INSENSITIVE
    );

    /**
     * Returns {@code true} for commands that alter agent state or authorise tool
     * execution (Approve, Deny, Halt, Reset). Returns {@code false} for read-only
     * or informational commands (Status, ListTasks, Unknown).
     */
    default boolean isControlCommand() {
        return this instanceof Approve
                || this instanceof Deny
                || this instanceof Halt
                || this instanceof Reset;
    }

    /**
     * Parses {@code text} into the most specific {@link ChannelCommand}.
     * Never throws; falls back to {@link Unknown}.
     *
     * @param text raw user message; may be null
     * @return a non-null ChannelCommand
     */
    static ChannelCommand parse(String text) {
        if (text == null || text.isBlank()) {
            return new Unknown(text == null ? "" : text);
        }
        var matcher = CMD_PATTERN.matcher(text.trim());
        if (!matcher.matches()) {
            return new Unknown(text);
        }
        String cmd = matcher.group(1).toLowerCase();
        String arg = matcher.group(2); // may be null
        Optional<String> optArg = Optional.ofNullable(arg);

        return switch (cmd) {
            case "approve" -> new Approve(optArg);
            case "deny"    -> new Deny(optArg);
            case "status"  -> new Status();
            case "halt"    -> new Halt();
            case "reset"   -> new Reset();
            case "list", "listtask", "listtasks" -> new ListTasks();
            default -> new Unknown(text);
        };
    }

    // ------------------------------------------------------------------
    // Permit records
    // ------------------------------------------------------------------

    /**
     * Approves a pending tool execution.
     *
     * @param cronId if present, targets a specific cron-session approval;
     *               if empty, targets the active interactive session
     */
    record Approve(Optional<String> cronId) implements ChannelCommand {}

    /**
     * Denies a pending tool execution.
     *
     * @param cronId if present, targets a specific cron-session;
     *               if empty, targets the active interactive session
     */
    record Deny(Optional<String> cronId) implements ChannelCommand {}

    /** Queries the current status of the agent's active session. */
    record Status() implements ChannelCommand {}

    /** Immediately terminates the agent's current reasoning loop. */
    record Halt() implements ChannelCommand {}

    /** Wipes the active session checkpoint, starting fresh on next message. */
    record Reset() implements ChannelCommand {}

    /** Lists scheduled cron tasks for the current user. */
    record ListTasks() implements ChannelCommand {}

    /**
     * Fallback for any text that does not match a known command.
     *
     * @param raw the original raw input
     */
    record Unknown(String raw) implements ChannelCommand {}
}
