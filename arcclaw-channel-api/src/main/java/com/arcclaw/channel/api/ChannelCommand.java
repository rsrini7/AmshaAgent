package com.arcclaw.channel.api;

import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Channel-agnostic command parsed from a user's text message.
 *
 * <p>Commands always begin with {@code /}. The static {@link #parse(String)}
 * factory maps raw text to one of the built-in permits. Non-command messages
 * map to {@link Unknown}.
 *
 * <p>Third-party channel plugins can add their own command types by implementing
 * this interface. The built-in command set remains stable for renderers that
 * use exhaustive switching.
 */
public interface ChannelCommand {

    /**
     * Sealed sub-hierarchy for the built-in command set.
     * Switch expressions over {@code BuiltInChannelCommand} are exhaustive at
     * compile time — adding a new built-in command and forgetting to handle it
     * is a compiler error, not a silent {@code default} fall-through.
     * <p>
     * Third-party channel plugins that define their own command types implement
     * {@link ChannelCommand} directly, not this interface, preserving the
     * compile-time safety of the built-in set.
     */
    sealed interface BuiltInChannelCommand extends ChannelCommand
        permits ChannelCommand.Approve, ChannelCommand.Deny, ChannelCommand.Status,
                ChannelCommand.Halt, ChannelCommand.Reset, ChannelCommand.History,
                ChannelCommand.CronTasks, ChannelCommand.DeleteCron, ChannelCommand.Start,
                ChannelCommand.Unknown {}

    // Single compiled Pattern - avoids repeated compilation per message.
    // Group 1: command
    // Group 2: first token (id or toolName)
    // Group 3: rest of the line (optional reason)
    Pattern CMD_PATTERN = Pattern.compile(
            "^/(start|approve|deny|status|halt|reset|history|cron|crons|deletecron|list(?:tasks?)?)(?:\\s+(\\S+))?(?:\\s+(.*))?",
            Pattern.CASE_INSENSITIVE
    );

    Pattern WHITESPACE_PATTERN = Pattern.compile("\\s+");

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
     * Parses {@code text} into the most specific {@link BuiltInChannelCommand}.
     * Never throws; falls back to {@link Unknown}.
     * <p>
     * The return type is {@link BuiltInChannelCommand} because {@code parse()}
     * exclusively produces commands from the built-in set. Callers can switch
     * exhaustively over the result without an outer {@code instanceof} check.
     * Third-party channel commands arrive through other paths (their own
     * handlers), not through {@code parse()}.
     *
     * @param text raw user message; may be null
     * @return a non-null BuiltInChannelCommand
     */
    static BuiltInChannelCommand parse(String text) {
        if (text == null || text.isBlank()) {
            return new Unknown(text == null ? "" : text);
        }

        // Strip surrounding backticks - Telegram MarkdownV2 inline code formatting
        // causes copy-pasted commands to arrive as `/approve <id>` with backticks
        String trimmed = text.trim();
        if (trimmed.startsWith("`") && trimmed.endsWith("`") && trimmed.length() > 2) {
            trimmed = trimmed.substring(1, trimmed.length() - 1).trim();
        }

        // Normalize double slash prefix (e.g. //approve -> /approve)
        // §Hardening: Only collapse exactly two slashes. Triple slashes (///) 
        // are treated as literal text/unknown per test contract.
        String input = (trimmed.startsWith("//") && !trimmed.startsWith("///")) 
                ? trimmed.substring(1) 
                : trimmed;

        var matcher = CMD_PATTERN.matcher(input);
        if (!matcher.matches()) {
            return new Unknown(input);
        }
        String cmd = matcher.group(1).toLowerCase();
        String arg = matcher.group(2); // may be null
        String reason = matcher.group(3); // may be null
        Optional<String> optArg = Optional.ofNullable(arg);
        Optional<String> optReason = Optional.ofNullable(reason != null && !reason.isBlank() ? reason.trim() : null);

        return switch (cmd) {
            case "approve" -> new Approve(
                parseTargetId(optArg),
                parseToolName(optArg),
                optReason);
            case "deny"    -> new Deny(
                parseTargetId(optArg),
                parseToolName(optArg),
                optReason);
            case "status"  -> new Status();
            case "halt"    -> new Halt();
            case "reset"   -> new Reset();
            case "history" -> new History();
            case "crons"   -> new CronTasks();
            case "start"   -> new Start(optArg);
            case "cron" -> parseCronCommand(arg, input);
            case "deletecron" -> new DeleteCron(optArg);
            case "listtask", "listtasks", "crontask", "crontasks" -> new CronTasks();
            default -> new Unknown(input);
        };
    }

    private static BuiltInChannelCommand parseCronCommand(String arg, String input) {
        String[] parts = WHITESPACE_PATTERN.split(input.trim());
        if ("delete".equalsIgnoreCase(arg)) {
            String cronId = parts.length > 2 ? parts[2].strip() : null;
            return new DeleteCron(
                Optional.ofNullable(cronId == null || cronId.isBlank() ? null : cronId));
        }
        return new CronTasks();
    }

    static final Pattern UUID_PATTERN = Pattern.compile(
        "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$"
    );

    private static Optional<String> parseTargetId(Optional<String> argOpt) {
        // Explicit targets can be a hyphenated ID, a numeric option, or an alphanumeric prefix of at least 4 chars.
        return argOpt.filter(arg -> arg.contains("-") || arg.matches("\\d+") || (arg.length() >= 4 && arg.matches("[0-9a-fA-F]+")));
    }

    private static Optional<String> parseToolName(Optional<String> argOpt) {
        return argOpt.filter(arg -> !arg.contains("-") && !arg.matches("\\d+") && !(arg.length() >= 4 && arg.matches("[0-9a-fA-F]+")) && !arg.isBlank());
    }

    // ------------------------------------------------------------------
    // Permit records
    // ------------------------------------------------------------------

    /**
     * Approves a pending tool execution.
     *
     * @param targetId if present, targets a specific session or cron task ID;
     * @param toolName if present, targets a specific tool in a parallel batch
     * @param reason   optional human-provided reason for approval
     */
    record Approve(
        Optional<String> targetId,
        Optional<String> toolName,
        Optional<String> reason
    ) implements BuiltInChannelCommand {}

    /**
     * Denies a pending tool execution.
     *
     * @param targetId if present, targets a specific session or cron task ID;
     * @param toolName if present, targets a specific tool in a parallel batch
     * @param reason   optional human-provided reason for denial
     */
    record Deny(
        Optional<String> targetId,
        Optional<String> toolName,
        Optional<String> reason
    ) implements BuiltInChannelCommand {}

     /**
     * Telegram lifecycle command - sent automatically when a user opens a fresh
     * chat or clicks the "Start" button. {@code payload} carries any deep-link
     * parameter Telegram appends after /start (may be empty).
     */
    record Start(Optional<String> payload) implements BuiltInChannelCommand {}

    /** Queries the current status of the agent's active session. */
    record Status() implements BuiltInChannelCommand {}

    /** Immediately terminates the agent's current reasoning loop. */
    record Halt() implements BuiltInChannelCommand {}

    /** Wipes the active session checkpoint, starting fresh on next message. */
    record Reset() implements BuiltInChannelCommand {}

    /** Lists scheduled cron tasks for the current user. */
    record CronTasks() implements BuiltInChannelCommand {}

    /** Shows conversation history for the current session. */
    record History() implements BuiltInChannelCommand {}

    /**
     * Deletes a scheduled cron task.
     *
     * @param cronId the ID of the cron task to delete
     */
    record DeleteCron(Optional<String> cronId) implements BuiltInChannelCommand {}

    /**
     * Fallback for any text that does not match a known command.
     *
     * @param raw the original raw input
     */
    record Unknown(String raw) implements BuiltInChannelCommand {}
}
