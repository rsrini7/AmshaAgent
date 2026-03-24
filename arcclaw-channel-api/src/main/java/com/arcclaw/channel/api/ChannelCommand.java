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
                ChannelCommand.History,
                ChannelCommand.CronTasks,
                ChannelCommand.DeleteCron,
                ChannelCommand.Unknown {

    // Single compiled Pattern — avoids repeated compilation per message.
    // The inner group (\\S+) matches the first non-whitespace token after the command.
    Pattern CMD_PATTERN = Pattern.compile(
            "^/(approve|deny|status|halt|reset|history|cron|crons|deletecron|list(?:tasks?)?)(?:\\s+(\\S+))?(?:\\s+.*)?",
            Pattern.CASE_INSENSITIVE
    );

    /**
     * Returns {@code true} for commands that alter agent state or authorise tool
     * execution (Approve, Deny, Halt, Reset). Returns {@code false} for read-only
     * or informational commands (Status, CronTasks, Unknown).
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
        
        // Strip surrounding backticks — Telegram MarkdownV2 inline code formatting
        // causes copy-pasted commands to arrive as `/approve <id>` with backticks
        String trimmed = text.trim();
        if (trimmed.startsWith("`") && trimmed.endsWith("`") && trimmed.length() > 2) {
            trimmed = trimmed.substring(1, trimmed.length() - 1).trim();
        }

        var matcher = CMD_PATTERN.matcher(trimmed);
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
            case "history" -> new History();
            case "crons"    -> new CronTasks();
            case "cron" -> {
                String[] parts = text.trim().split("\\s+");
                if ("delete".equalsIgnoreCase(arg)) {
                    String cronId = parts.length > 2 ? parts[2].strip() : null;
                    yield new DeleteCron(
                        Optional.ofNullable(cronId == null || cronId.isBlank()
                            ? null : cronId));
                }
                yield new CronTasks();
            }
            case "deletecron" -> new DeleteCron(optArg);
            case "listtask", "listtasks", "crontask", "crontasks" -> new CronTasks();
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
    record CronTasks() implements ChannelCommand {}

    /** Shows conversation history for the current session. */
    record History() implements ChannelCommand {}

    /**
     * Deletes a scheduled cron task.
     *
     * @param cronId the ID of the cron task to delete
     */
    record DeleteCron(Optional<String> cronId) implements ChannelCommand {}

    /**
     * Fallback for any text that does not match a known command.
     *
     * @param raw the original raw input
     */
    record Unknown(String raw) implements ChannelCommand {}
}
