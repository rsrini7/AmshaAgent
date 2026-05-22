package com.arcclaw.core.pipeline.parsers;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

import com.arcclaw.channel.api.ChannelCommand;
import com.arcclaw.channel.api.ChannelCommandParser;
import com.arcclaw.channel.api.ChannelType;

/**
 * Registry of {@link ChannelCommandParser} instances discovered via CDI.
 * <p>
 * Follows Chain of Responsibility — parsers are tried in priority order
 * (lowest first). The first parser that returns a non-empty result wins.
 * If no parser matches, an {@link ChannelCommand.Unknown} fallback is returned.
 * <p>
 * Parser list is materialized lazily on first {@link #parse} call to avoid
 * lifecycle traps with non-{@code @ApplicationScoped} parser beans.
 */
@ApplicationScoped
public class CommandParserRegistry {

    private final Instance<ChannelCommandParser> parserInstances;
    private volatile List<ChannelCommandParser> parsers;

    @Inject
    public CommandParserRegistry(Instance<ChannelCommandParser> parserInstances) {
        this.parserInstances = parserInstances;
    }

    private List<ChannelCommandParser> parsers() {
        List<ChannelCommandParser> local = parsers;
        if (local == null) {
            synchronized (this) {
                local = parsers;
                if (local == null) {
                    local = parserInstances.stream()
                        .sorted(Comparator.comparingInt(ChannelCommandParser::priority))
                        .toList();
                    parsers = local;
                }
            }
        }
        return local;
    }

    /**
     * Parses raw user text into a {@link ChannelCommand} by delegating to
     * all registered parsers in priority order.
     *
     * @param text        raw user message; may be null or blank
     * @param channelType the originating channel
     * @return the first matching command, or {@link ChannelCommand.Unknown}
     */
    public ChannelCommand parse(String text, ChannelType channelType) {
        return parsers().stream()
            .map(p -> p.parse(text, channelType))
            .flatMap(Optional::stream)
            .findFirst()
            .orElseThrow(() -> new IllegalStateException(
                "No ChannelCommandParser available. BuiltInChannelCommandParser must be registered"));
    }
}
