package com.arcclaw.channel.api;

/**
 * Interactive control node. Channels that do not support a given interaction
 * type fall back to {@link #fallbackText()}.
 * <p>
 * Non-sealed — third-party channel plugins can define custom interaction types.
 *
 * @see ApprovalPrompt
 */
public non-sealed interface InteractionNode extends ReplyNode {

    /**
     * Prefix used in {@link ApprovalPrompt#toolName()} to indicate a parallel
     * multi-tool step. Channel renderers should strip this prefix when
     * displaying tool names to the user.
     */
    String PARALLEL_SENTINEL_PIPE_PREFIX = "parallel|";

    /**
     * Plain-text fallback for channels that cannot render this interaction
     * type natively (e.g. CLI instead of inline keyboards).
     */
    default String fallbackText() {
        return "[Unsupported interaction]";
    }
}
