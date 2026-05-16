package com.arcclaw.channel.api;

import java.util.List;

/**
 * Structured reply node tree for channel-agnostic reply composition.
 *
 * <p>Tier 2B {@link ReplyRenderer} implementations depend on exhaustive
 * switching over exactly these 10 permits. Do not add or remove permits.
 *
 * <p>Nested permits are declared as inner records to keep the sealed hierarchy
 * contained in a single compilation unit.
 */
public sealed interface ReplyNode
        permits ReplyNode.Text,
                ReplyNode.Heading,
                ReplyNode.CodeBlock,
                ReplyNode.InlineCode,
                ReplyNode.BulletList,
                ReplyNode.NumberedList,
                ReplyNode.KeyValue,
                ReplyNode.Separator,
                ReplyNode.ApprovalPrompt,
                ReplyNode.ErrorMessage {

    /**
     * Prefix used in {@link ApprovalPrompt#toolName()} to indicate a parallel
     * multi-tool step. Channel renderers should strip this prefix when
     * displaying tool names to the user.
     */
    String PARALLEL_SENTINEL_PIPE_PREFIX = "parallel|";

    // ------------------------------------------------------------------
    // Permit records - exactly 10, no more, no fewer
    // ------------------------------------------------------------------

    /** Plain text paragraph. */
    record Text(String content) implements ReplyNode {}

    /** Section heading at a given markdown depth level (1 = h1, 2 = h2, …). */
    record Heading(String text, int level) implements ReplyNode {}

    /** Fenced code block with optional language hint. */
    record CodeBlock(String code, String language) implements ReplyNode {}

    /** Inline monospace code snippet. */
    record InlineCode(String code) implements ReplyNode {}

    /** Unordered bullet list. */
    record BulletList(List<String> items) implements ReplyNode {}

    /** Ordered / numbered list. */
    record NumberedList(List<String> items) implements ReplyNode {}

    /** Key → value pair (rendered as bold key + value). */
    record KeyValue(String key, String value) implements ReplyNode {}

    /** Visual separator (horizontal rule or blank line, channel-dependent). */
    record Separator() implements ReplyNode {}

    /**
     * Structured approval prompt shown to the human operator before a
     * potentially side-effecting tool execution.
     *
     * @param toolName   the tool awaiting approval
     * @param paramsJson JSON representation of the tool parameters
     * @param sessionId  the session that will be resumed on approval
     */
    record ApprovalPrompt(String toolName, String paramsJson, String sessionId)
            implements ReplyNode {}

    /**
     * Sanitised error message suitable for display to the end-user.
     *
     * @param reason   human-readable description of what went wrong
     * @param category corresponding {@code FailureCategory} name (for client filtering)
     */
    record ErrorMessage(String reason, String category) implements ReplyNode {}
}
