package com.amsha.channel.api;

import java.util.Optional;

/**
 * SPI for parsing raw user text into {@link ChannelCommand} instances.
 * <p>
 * Follows the Chain of Responsibility pattern — parsers are discovered via CDI
 * and tried in priority order. The first parser that returns a non-empty result
 * wins. The built-in parser (priority = {@link Integer#MAX_VALUE}) runs last
 * as the fallback for standard commands.
 * <p>
 * Third-party channel plugins implement this interface to introduce custom
 * commands (e.g. {@code /slack-remind}) without modifying core code.
 *
 * @see CommandParserRegistry
 */
public interface ChannelCommandParser {

    /**
     * Attempts to parse the given text into a {@link ChannelCommand}.
     *
     * @param text        raw user message; may be null or blank
     * @param channelType the originating channel (may influence parsing rules)
     * @return the parsed command if this parser handles the text, or empty
     */
    Optional<ChannelCommand> parse(String text, ChannelType channelType);

    /**
     * Priority relative to other parsers. Lower values run first.
     * The built-in parser uses {@link Integer#MAX_VALUE} as the final fallback.
     */
    int priority();
}
