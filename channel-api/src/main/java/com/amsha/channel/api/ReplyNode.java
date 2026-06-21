package com.amsha.channel.api;

/**
 * A reply node is either a {@link ContentNode} (pure content) or an
 * {@link InteractionNode} (interactive controls).
 *
 * <p>Third-party channels implement {@link ReplyRenderer#renderContent}
 * for content types and override {@link ReplyRenderer#renderInteraction}
 * for interactive types they support.
 *
 * @see ContentNode
 * @see InteractionNode
 */
public sealed interface ReplyNode permits ContentNode, InteractionNode {}
