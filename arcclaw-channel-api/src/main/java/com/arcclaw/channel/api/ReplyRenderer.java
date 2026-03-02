package com.arcclaw.channel.api;

import java.util.List;

/**
 * Converts a structured {@link ReplyNode} tree into a channel-specific string.
 * Implementations handle Telegram Markdown, Discord markup, plain text, etc.
 */
public interface ReplyRenderer {

    /** Returns the channel this renderer produces output for. */
    ChannelType supports();

    /**
     * Renders a list of {@link ReplyNode}s into a single deliverable string.
     *
     * @param nodes ordered reply nodes; must not be null
     * @return the rendered string, never null
     */
    String render(List<ReplyNode> nodes);
}
