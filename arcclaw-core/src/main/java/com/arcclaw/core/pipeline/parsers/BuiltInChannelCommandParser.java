package com.arcclaw.core.pipeline.parsers;

import jakarta.enterprise.context.ApplicationScoped;

import java.util.Optional;

import com.arcclaw.channel.api.ChannelCommand;
import com.arcclaw.channel.api.ChannelCommandParser;
import com.arcclaw.channel.api.ChannelType;

/**
 * Built-in command parser that wraps {@link ChannelCommand#parse(String)}.
 * Runs last (priority = {@link Integer#MAX_VALUE}) as the terminal fallback
 * after all third-party parsers have had their chance.
 */
@ApplicationScoped
public class BuiltInChannelCommandParser implements ChannelCommandParser {

    @Override
    public Optional<ChannelCommand> parse(String text, ChannelType channelType) {
        return Optional.of(ChannelCommand.parse(text));
    }

    @Override
    public int priority() {
        return Integer.MAX_VALUE;
    }
}
