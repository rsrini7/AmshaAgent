package com.amsha.core.pipeline.parsers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import jakarta.enterprise.inject.Instance;

import java.util.Optional;
import java.util.stream.Stream;

import com.amsha.channel.api.ChannelCommand;
import com.amsha.channel.api.ChannelCommandParser;
import com.amsha.channel.api.ChannelType;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class CommandParserRegistryTest {

    private Instance<ChannelCommandParser> parserInstances;
    private CommandParserRegistry registry;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setup() {
        parserInstances = mock(Instance.class);
        registry = new CommandParserRegistry(parserInstances);
    }

    @Test
    void testParsersExecutedInPriorityOrder() {
        ChannelCommandParser highPriorityParser = mock(ChannelCommandParser.class);
        when(highPriorityParser.priority()).thenReturn(10);
        when(highPriorityParser.parse("test", ChannelType.TELEGRAM)).thenReturn(Optional.empty());

        ChannelCommandParser lowPriorityParser = mock(ChannelCommandParser.class);
        when(lowPriorityParser.priority()).thenReturn(5);
        ChannelCommand expectedCommand = mock(ChannelCommand.class);
        when(lowPriorityParser.parse("test", ChannelType.TELEGRAM)).thenReturn(Optional.of(expectedCommand));

        when(parserInstances.stream()).thenReturn(Stream.of(highPriorityParser, lowPriorityParser));

        ChannelCommand actual = registry.parse("test", ChannelType.TELEGRAM);
        assertEquals(expectedCommand, actual);
    }

    @Test
    void testThrowsExceptionWhenNoParserMatches() {
        when(parserInstances.stream()).thenReturn(Stream.empty());

        assertThrows(IllegalStateException.class, () -> {
            registry.parse("test", ChannelType.TELEGRAM);
        });
    }
}
