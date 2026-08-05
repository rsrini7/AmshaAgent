package com.amsha.app;

import static org.junit.jupiter.api.Assertions.*;

import picocli.CommandLine;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class AmshaCliTest {

    @Mock
    CommandLine.IFactory factory;

    private AmshaCli cli;

    @BeforeEach
    void setUp() {
        cli = new AmshaCli();
        cli.factory = factory;
    }

    @Test
    void testRunMethod() {
        assertDoesNotThrow(() -> cli.run());
    }
}
