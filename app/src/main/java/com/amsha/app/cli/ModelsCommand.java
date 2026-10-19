package com.amsha.app.cli;

import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.stream.Collectors;

import com.amsha.common.config.ConfigKeys;
import com.amsha.localml.onnx.LocalAudioTranscoder;

/**
 * Picocli command for managing and installing verified local ONNX model bundles.
 * Provides an offline, zero-dependency model setup mechanism directly in the Amsha binary.
 */
@Command(
        name = "models",
        description = "Manage and install local ONNX model bundles",
        mixinStandardHelpOptions = true,
        subcommands = {
            ModelsCommand.ListCommand.class,
            ModelsCommand.InstallCommand.class,
            ModelsCommand.VerifyCommand.class
        })
@SuppressWarnings("java:S106")
public class ModelsCommand implements Runnable {

    @Override
    public void run() {
        printList();
    }

    private static void printList() {
        Path modelRoot = ModelInstaller.resolveModelRoot();
        List<ModelInstaller.ModelCatalogEntry> models = ModelInstaller.allModels();

        System.out.println();
        System.out.println("  Local ONNX Model Catalog");
        System.out.println("  " + "=".repeat(88));
        System.out.printf("  %-28s %-12s %-10s %s%n", "Model ID", "Status", "Size", "Description");
        System.out.println("  " + "-".repeat(88));

        for (ModelInstaller.ModelCatalogEntry entry : models) {
            ModelInstaller.ModelStatus status = ModelInstaller.checkStatus(entry, modelRoot);
            String statusText =
                    switch (status) {
                        case INSTALLED -> "INSTALLED";
                        case INCOMPLETE -> "INCOMPLETE";
                        case MISSING -> "MISSING";
                    };
            System.out.printf(
                    "  %-28s %-12s %-10s %s%n", entry.id(), statusText, entry.totalSize(), entry.description());
        }

        System.out.println("  " + "-".repeat(88));
        System.out.printf("  Storage directory: %s%n%n", modelRoot);
        System.out.println("  USAGE");
        System.out.println(
                "    ./amsha models install <model>    Install a model (e.g., whisper, mpnet, bge-reranker, all)");
        System.out.println("    ./amsha models verify <model>     Verify SHA-256 integrity of installed files");
        System.out.println("    ./amsha models list               Show this catalog");
        System.out.println();
    }

    @Command(name = "list", description = "List available model bundles and installation status")
    public static class ListCommand implements Runnable {
        @Override
        public void run() {
            printList();
        }
    }

    @Command(name = "install", description = "Download, verify, and install a model bundle")
    public static class InstallCommand implements Runnable {

        @Parameters(
                index = "0",
                arity = "0..1",
                paramLabel = "<model>",
                description = "Model to install: whisper, mpnet, bge-reranker, or all")
        String modelName;

        @Override
        public void run() {
            if (modelName == null || modelName.isBlank()) {
                System.out.println("❌ Please specify a model to install: whisper, mpnet, bge-reranker, or all");
                printList();
                return;
            }

            Path modelRoot = ModelInstaller.resolveModelRoot();
            if ("all".equalsIgnoreCase(modelName.trim())) {
                for (ModelInstaller.ModelCatalogEntry entry : ModelInstaller.allModels()) {
                    try {
                        ModelInstaller.installBundle(entry, modelRoot, System.out);
                    } catch (IOException e) {
                        System.err.printf("❌ Installation failed for %s: %s%n", entry.id(), e.getMessage());
                    }
                }
                checkFfmpegNotice();
                return;
            }

            Optional<ModelInstaller.ModelCatalogEntry> match = ModelInstaller.findModel(modelName);
            if (match.isEmpty()) {
                String known = ModelInstaller.allModels().stream()
                        .map(ModelInstaller.ModelCatalogEntry::id)
                        .collect(Collectors.joining(", "));
                System.err.printf("❌ Unknown model '%s'. Known models: %s, all%n", modelName, known);
                return;
            }

            try {
                ModelInstaller.installBundle(match.get(), modelRoot, System.out);
                if (match.get().id().contains("whisper")) {
                    checkFfmpegNotice();
                }
            } catch (IOException e) {
                System.err.printf("❌ Installation failed: %s%n", e.getMessage());
            }
        }

        private static void checkFfmpegNotice() {
            String configuredFfmpeg =
                    System.getProperty("amsha.stt.ffmpeg-path", System.getenv(ConfigKeys.STT_FFMPEG_PATH));
            if (!LocalAudioTranscoder.isFfmpegAvailable(configuredFfmpeg)) {
                System.out.println();
                System.out.println("  ⚠️  Notice: ffmpeg was not detected on your system.");
                System.out.println(
                        "     ffmpeg is required to decode voice notes and compressed audio (OGG/Opus, MP3).");
                System.out.println(
                        "     " + LocalAudioTranscoder.getOsInstallGuidance().replace("\n", "\n     "));
                System.out.println();
            }
        }
    }

    @Command(name = "verify", description = "Verify checksums of installed model bundles")
    public static class VerifyCommand implements Callable<Integer> {

        @Parameters(
                index = "0",
                arity = "0..1",
                paramLabel = "<model>",
                description = "Model to verify (or omit to verify all installed models)")
        String modelName;

        @Override
        public Integer call() {
            Path modelRoot = ModelInstaller.resolveModelRoot();
            if (modelName == null || modelName.isBlank() || "all".equalsIgnoreCase(modelName.trim())) {
                boolean allOk = true;
                for (ModelInstaller.ModelCatalogEntry entry : ModelInstaller.allModels()) {
                    System.out.printf("Verifying '%s'...%n", entry.id());
                    if (!ModelInstaller.verifyBundle(entry, modelRoot, System.out)) {
                        allOk = false;
                    }
                }
                if (allOk) {
                    System.out.println("✅ All installed model bundles verified successfully.");
                } else {
                    System.err.println("❌ Verification failed for one or more models.");
                }
                return allOk ? 0 : 1;
            }

            Optional<ModelInstaller.ModelCatalogEntry> match = ModelInstaller.findModel(modelName);
            if (match.isEmpty()) {
                System.err.printf("❌ Unknown model '%s'.%n", modelName);
                return 1;
            }

            System.out.printf("Verifying '%s'...%n", match.get().id());
            if (ModelInstaller.verifyBundle(match.get(), modelRoot, System.out)) {
                System.out.printf(
                        "✅ Model bundle '%s' verified successfully.%n",
                        match.get().id());
                return 0;
            } else {
                System.err.printf(
                        "❌ Model bundle '%s' verification failed.%n",
                        match.get().id());
                return 1;
            }
        }
    }
}
