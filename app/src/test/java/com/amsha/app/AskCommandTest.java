package com.amsha.app;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import picocli.CommandLine;

import org.junit.jupiter.api.Test;

class AskCommandTest {

    @Test
    void verboseIsDisabledByDefault() {
        AskCommand command = new AskCommand();

        new CommandLine(command).parseArgs("Hi");

        assertFalse(command.verbose);
    }

    @Test
    void verboseCanBeEnabled() {
        AskCommand command = new AskCommand();

        new CommandLine(command).parseArgs("Hi", "--verbose");

        assertTrue(command.verbose);
    }
}
