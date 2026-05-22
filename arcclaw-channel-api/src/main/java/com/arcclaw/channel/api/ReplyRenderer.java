package com.arcclaw.channel.api;

import java.util.ArrayList;
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
     * <p>
     * The default implementation delegates content nodes to the subclass and
     * uses {@link #renderInteraction} for any {@link InteractionNode} elements.
     *
     * @param nodes ordered reply nodes; must not be null
     * @return the rendered string, never null
     */
    default String render(List<ReplyNode> nodes) {
        if (nodes == null || nodes.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        List<ContentNode> batch = new ArrayList<>();

        for (ReplyNode node : nodes) {
            if (node instanceof ContentNode cn) {
                batch.add(cn);
            } else if (node instanceof InteractionNode in) {
                if (!batch.isEmpty()) {
                    sb.append(renderContent(batch)).append("\n\n");
                    batch.clear();
                }
                sb.append(renderInteraction(in)).append("\n\n");
            } else {
                // ReplyNode is sealed to ContentNode|InteractionNode for built-in types,
                // but third-party plugins can define subtypes that miss both branches.
                // Concrete renderers handle this in their renderContentNode() default arm.
            }
        }
        if (!batch.isEmpty()) {
            sb.append(renderContent(batch));
        }
        return sb.toString().trim();
    }

    /**
     * Renders a list of {@link ContentNode content nodes} into text.
     * Every channel must implement this.
     *
     * @param nodes ordered content nodes; must not be null
     * @return the rendered string, never null
     */
    String renderContent(List<ContentNode> nodes);

    /**
     * Renders a single {@link InteractionNode interaction node}.
     * <p>
     * Channels that cannot render the interaction type natively should
     * return {@link InteractionNode#fallbackText()} for
     * unsupported types. The default implementation does exactly that.
     *
     * @param node the interaction node
     * @return the rendered string, never null
     */
    default String renderInteraction(InteractionNode node) {
        return node.fallbackText();
    }
}
