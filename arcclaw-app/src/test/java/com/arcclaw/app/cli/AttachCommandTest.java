package com.arcclaw.app.cli;

import picocli.CommandLine;

import jakarta.inject.Inject;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import io.quarkus.test.junit.QuarkusTest;

@QuarkusTest
class AttachCommandTest {

    @Inject
    AttachCommand attachCommand;

    @Test
    void testUnknownChannelPrintsAvailableOptions() {
        ByteArrayOutputStream outContent = new ByteArrayOutputStream();
        System.setOut(new PrintStream(outContent));

        CommandLine cmd = new CommandLine(attachCommand);
        cmd.execute("whatsapp");

        String output = outContent.toString();
        Assertions.assertTrue(
                output.contains("Unknown channel 'whatsapp'"), "Output should contain unknown channel error");
        Assertions.assertTrue(output.contains("telegram"), "Output should list available channels like telegram");
    }
}
