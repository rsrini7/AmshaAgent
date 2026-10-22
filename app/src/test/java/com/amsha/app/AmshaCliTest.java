package com.amsha.app;

import static org.junit.jupiter.api.Assertions.*;

import picocli.CommandLine;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

import com.amsha.app.cli.CliStartupFailureReporter;

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

        String oldEnabled = System.getProperty("quarkus.log.file.enabled");
        String oldPath = System.getProperty("quarkus.log.file.path");
        try {
            System.setProperty("quarkus.log.file.enabled", "true");
            System.setProperty("quarkus.log.file.path", "logs/amsha-test.log");
            ByteArrayOutputStream error = new ByteArrayOutputStream();
            CliStartupFailureReporter.report(
                    "Quarkus runtime startup",
                    new IllegalStateException("controlled startup failure"),
                    new PrintStream(error, true, StandardCharsets.UTF_8));
            String output = error.toString(StandardCharsets.UTF_8);
            assertTrue(output.contains("controlled startup failure"), output);
            assertTrue(output.contains("amsha-test.log"), output);

            ByteArrayOutputStream notice = new ByteArrayOutputStream();
            CliStartupFailureReporter.reportNotice(
                    "Vector search is unavailable; keyword retrieval remains enabled.",
                    new PrintStream(notice, true, StandardCharsets.UTF_8));
            String noticeOutput = notice.toString(StandardCharsets.UTF_8);
            assertTrue(noticeOutput.contains("keyword retrieval remains enabled"), noticeOutput);
            assertTrue(noticeOutput.contains("amsha-test.log"), noticeOutput);

            ByteArrayOutputStream startupError = new ByteArrayOutputStream();
            AmshaCli.handleQuarkusExit(
                    1,
                    new IllegalStateException("controlled Quarkus initialization failure"),
                    "Quarkus runtime startup",
                    new PrintStream(startupError, true, StandardCharsets.UTF_8));
            String startupOutput = startupError.toString(StandardCharsets.UTF_8);
            assertTrue(startupOutput.contains("controlled Quarkus initialization failure"), startupOutput);
            assertTrue(startupOutput.contains("amsha-test.log"), startupOutput);
        } finally {
            restoreSystemProperty("quarkus.log.file.enabled", oldEnabled);
            restoreSystemProperty("quarkus.log.file.path", oldPath);
        }
    }

    private static void restoreSystemProperty(String name, String value) {
        if (value == null) {
            System.clearProperty(name);
        } else {
            System.setProperty(name, value);
        }
    }

    @Test
    void runningWithoutArgumentsPrintsAllCommandsAndOptions() {
        String output = captureUsage();

        assertTrue(output.contains("amsha-sqlite serve"), output);
        assertTrue(output.contains("amsha-sqlite chat [options]"), output);
        assertTrue(output.contains("amsha-sqlite ask <goal> [options]"), output);
        assertTrue(output.contains("amsha-sqlite attach <channel>"), output);
        assertTrue(output.contains("--last-session"), output);
        assertTrue(output.contains("--new-session"), output);
        assertTrue(output.contains("--verbose"), output);
        assertTrue(output.contains("models install <model>"), output);
        assertTrue(output.contains("models verify [model|all]"), output);
        assertTrue(output.contains("~436 MB"), output);
    }

    @Test
    void rootHelpPrintsUsageWithoutStartingTheApplication() {
        String output = captureUsage("--help");

        assertTrue(output.contains("Amsha Agent :: commands and options"), output);
        assertTrue(output.contains("models install <model>"), output);
    }

    @Test
    void versionPrintsWithoutStartingTheApplication() {
        String output = captureUsage("--version");

        assertTrue(output.startsWith("Amsha Agent "), output);
    }

    private static String captureUsage(String... args) {
        PrintStream originalOut = System.out;
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        try {
            System.setOut(new PrintStream(captured, true, StandardCharsets.UTF_8));
            AmshaCli.main(args);
        } finally {
            System.setOut(originalOut);
        }
        return captured.toString(StandardCharsets.UTF_8);
    }
}
