package com.arcclaw.channel.api;

import java.util.List;

/**
 * Pure content node that every channel must render.
 * <p>
 * Non-sealed — third-party plugins can define their own content node types
 * without modifying the core API. The sealed parent {@link ReplyNode} ensures
 * that all nodes are either content or interaction.
 *
 * @see Text
 * @see Heading
 * @see CodeBlock
 * @see InlineCode
 * @see BulletList
 * @see NumberedList
 * @see KeyValue
 * @see Separator
 * @see ErrorMessage
 */
public non-sealed interface ContentNode extends ReplyNode {

    /** Plain text paragraph. */
    record Text(String content) implements ContentNode {}

    /** Section heading at a given markdown depth level (1 = h1, 2 = h2, …). */
    record Heading(String text, int level) implements ContentNode {}

    /** Fenced code block with optional language hint. */
    record CodeBlock(String code, String language) implements ContentNode {}

    /** Inline monospace code snippet. */
    record InlineCode(String code) implements ContentNode {}

    /** Unordered bullet list. */
    record BulletList(List<String> items) implements ContentNode {}

    /** Ordered / numbered list. */
    record NumberedList(List<String> items) implements ContentNode {}

    /** Key → value pair (rendered as bold key + value). */
    record KeyValue(String key, String value) implements ContentNode {}

    /** Visual separator (horizontal rule or blank line, channel-dependent). */
    record Separator() implements ContentNode {}

    /**
     * Sanitised error message suitable for display to the end-user.
     *
     * @param reason   human-readable description of what went wrong
     * @param category corresponding {@code FailureCategory} name (for client filtering)
     */
    record ErrorMessage(String reason, String category) implements ContentNode {}
}
