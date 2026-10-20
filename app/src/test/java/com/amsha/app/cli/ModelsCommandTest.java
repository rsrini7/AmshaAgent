package com.amsha.app.cli;

import picocli.CommandLine;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Path;
import java.util.Optional;

import com.amsha.app.config.LocalModelValidator;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ModelsCommandTest {
    private static final String MODEL_ROOT_PROPERTY = "amsha.onnx.model-root";

    @TempDir
    Path tempModelRoot;

    @Test
    void testCatalogResolutionAndAliases() {
        Optional<ModelInstaller.ModelCatalogEntry> whisper = ModelInstaller.findModel("whisper");
        Assertions.assertTrue(whisper.isPresent());
        Assertions.assertEquals("whisper-tiny-en-v1", whisper.get().id());

        Optional<ModelInstaller.ModelCatalogEntry> mpnet = ModelInstaller.findModel("mpnet");
        Assertions.assertTrue(mpnet.isPresent());
        Assertions.assertEquals("all-mpnet-base-v2-e8c3b32", mpnet.get().id());

        Optional<ModelInstaller.ModelCatalogEntry> reranker = ModelInstaller.findModel("reranker");
        Assertions.assertTrue(reranker.isPresent());
        Assertions.assertEquals("bge-reranker-v1", reranker.get().id());

        Optional<ModelInstaller.ModelCatalogEntry> unknown = ModelInstaller.findModel("non-existent-model");
        Assertions.assertTrue(unknown.isEmpty());
    }

    @Test
    void testFormatBytes() {
        Assertions.assertEquals("500 B", ModelInstaller.formatBytes(500));
        Assertions.assertEquals("1.5 KB", ModelInstaller.formatBytes(1536));
        Assertions.assertEquals("10.0 MB", ModelInstaller.formatBytes(10485760));
        Assertions.assertEquals("1.00 GB", ModelInstaller.formatBytes(1073741824));
    }

    @Test
    void testListCommandPrintsCatalog() {
        ByteArrayOutputStream outContent = new ByteArrayOutputStream();
        PrintStream orig = System.out;
        try {
            System.setOut(new PrintStream(outContent));
            CommandLine cmd = new CommandLine(new ModelsCommand());
            int code = cmd.execute("list");
            Assertions.assertEquals(0, code);
            String output = outContent.toString();
            Assertions.assertTrue(output.contains("whisper-tiny-en-v1"));
            Assertions.assertTrue(output.contains("all-mpnet-base-v2-e8c3b32"));
            Assertions.assertTrue(output.contains("bge-reranker-v1"));
            Assertions.assertTrue(output.contains("USAGE"));
        } finally {
            System.setOut(orig);
        }
    }

    @Test
    void testUnknownModelInstallPrintsError() {
        ByteArrayOutputStream errContent = new ByteArrayOutputStream();
        PrintStream orig = System.err;
        try {
            System.setErr(new PrintStream(errContent));
            CommandLine cmd = new CommandLine(new ModelsCommand());
            cmd.execute("install", "invalid-model-name");
            String err = errContent.toString();
            Assertions.assertTrue(err.contains("Unknown model 'invalid-model-name'"));
            Assertions.assertTrue(err.contains("whisper-tiny-en-v1"));
        } finally {
            System.setErr(orig);
        }
    }

    @Test
    void testVerifyCommandReportsMissingBundleWithoutLocalModels() {
        ByteArrayOutputStream outContent = new ByteArrayOutputStream();
        PrintStream orig = System.out;
        String originalModelRoot = System.getProperty(MODEL_ROOT_PROPERTY);
        try {
            System.setProperty(MODEL_ROOT_PROPERTY, tempModelRoot.toString());
            System.setOut(new PrintStream(outContent));
            CommandLine cmd = new CommandLine(new ModelsCommand());
            int code = cmd.execute("verify", "whisper");
            Assertions.assertEquals(1, code);
            String output = outContent.toString();
            Assertions.assertTrue(output.contains("Bundle directory missing"));
        } finally {
            if (originalModelRoot == null) {
                System.clearProperty(MODEL_ROOT_PROPERTY);
            } else {
                System.setProperty(MODEL_ROOT_PROPERTY, originalModelRoot);
            }
            System.setOut(orig);
        }
    }

    @Test
    void testLocalModelValidatorBypassesModelsCommand() {
        // Should not throw or fail when running models subcommands
        LocalModelValidator.validateLocalModels(Path.of("."), new String[] {"models", "list"});
        LocalModelValidator.validateLocalModels(Path.of("."), new String[] {"--help"});
    }
}
