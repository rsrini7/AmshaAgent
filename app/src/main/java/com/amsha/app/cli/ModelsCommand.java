package com.amsha.app.cli;

import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.stream.Collectors;

import com.amsha.app.config.LocalModelValidator;
import com.amsha.common.config.ConfigKeys;
import com.amsha.common.util.LogUtil;
import com.amsha.localml.onnx.LocalAudioTranscoder;

/**
 * Picocli command for managing and installing verified local ONNX model bundles.
 * Provides an offline, zero-dependency model setup mechanism directly in the Amsha binary.
 */
@Command(
        name = CliArguments.COMMAND_MODELS,
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
            String statusText = status.name();
            System.out.printf(
                    "  %-28s %-12s %-10s %s%n", entry.id(), statusText, entry.totalSize(), entry.description());
        }

        System.out.println("  " + "-".repeat(88));
        System.out.printf("  Storage directory: %s%n%n", modelRoot);
        System.out.println("  USAGE");
        String launcher = ModelInstaller.launcherCommand();
        System.out.printf(
                "    %s models install <model>    Install a model (whisper, mpnet, bge-reranker, all)%n", launcher);
        System.out.printf("    %s models verify <model>     Verify SHA-256 integrity of installed files%n", launcher);
        System.out.printf("    %s models list               Show this catalog%n", launcher);
        System.out.println();
    }

    static void printInstallFailure(
            ModelInstaller.ModelCatalogEntry entry, Path modelRoot, Exception failure, PrintStream errorOutput) {
        String retryAlias = entry.aliases().stream().findFirst().orElse(entry.id());
        String launcher = ModelInstaller.launcherCommand();
        errorOutput.printf("[Amsha Agent] Model bundle '%s' could not be installed.%n", entry.id());
        errorOutput.printf("Reason: %s%n", LogUtil.rootMessage(failure));
        errorOutput.printf("Model directory: %s%n", modelRoot.toAbsolutePath().normalize());
        errorOutput.println("Check internet access, available disk space, and write permission for this directory.");
        errorOutput.println("Verified files are retained; retry reuses them and downloads incomplete files again.");
        errorOutput.printf("Retry with: %s models install %s%n", launcher, retryAlias);

        switch (entry.id()) {
            case ModelInstaller.MPNET_BUNDLE_ID -> {
                errorOutput.println("This model is needed only when ONNX is selected for embeddings.");
                errorOutput.printf(
                        "If it cannot be downloaded, configure another embedding provider in %s "
                                + "(for example ollama, jina, or openai).%n",
                        ConfigKeys.AMSHA_INFINITE_LOOP_EMBEDDING_PROVIDERS);
            }
            case ModelInstaller.WHISPER_BUNDLE_ID ->
                errorOutput.printf(
                        "Local voice transcription is optional; set %s=false to disable it.%n",
                        LocalModelValidator.LOCAL_STT_ENABLED_ENV);
            case ModelInstaller.RERANKER_BUNDLE_ID ->
                errorOutput.printf(
                        "Reranking is optional; set %s=false to disable it and continue with hybrid search.%n",
                        LocalModelValidator.RERANKER_ENABLED_ENV);
            default -> errorOutput.println("This local model is optional unless its feature is enabled.");
        }
    }

    @Command(name = CliArguments.COMMAND_LIST, description = "List available model bundles and installation status")
    public static class ListCommand implements Runnable {
        @Override
        public void run() {
            printList();
        }
    }

    @Command(name = CliArguments.COMMAND_INSTALL, description = "Download, verify, and install a model bundle")
    public static class InstallCommand implements Callable<Integer> {

        @Parameters(
                index = "0",
                arity = "0..1",
                paramLabel = "<model>",
                description = "Model to install: whisper, mpnet, bge-reranker, or all")
        String modelName;

        @Override
        public Integer call() {
            if (modelName == null || modelName.isBlank()) {
                System.out.println("❌ Please specify a model to install: whisper, mpnet, bge-reranker, or all");
                printList();
                return 2;
            }

            Path modelRoot = ModelInstaller.resolveModelRoot();
            if (ModelInstaller.SELECTOR_ALL.equalsIgnoreCase(modelName.trim())) {
                int exitCode = 0;
                for (ModelInstaller.ModelCatalogEntry entry : ModelInstaller.allModels()) {
                    try {
                        ModelInstaller.installBundle(entry, modelRoot, System.out);
                    } catch (IOException | RuntimeException e) {
                        printInstallFailure(entry, modelRoot, e, System.err);
                        exitCode = 1;
                    }
                }
                checkFfmpegNotice();
                return exitCode;
            }

            Optional<ModelInstaller.ModelCatalogEntry> match = ModelInstaller.findModel(modelName);
            if (match.isEmpty()) {
                String known = ModelInstaller.allModels().stream()
                        .map(ModelInstaller.ModelCatalogEntry::id)
                        .collect(Collectors.joining(", "));
                System.err.printf("❌ Unknown model '%s'. Known models: %s, all%n", modelName, known);
                return 2;
            }

            try {
                ModelInstaller.installBundle(match.get(), modelRoot, System.out);
                if (ModelInstaller.WHISPER_BUNDLE_ID.equals(match.get().id())) {
                    checkFfmpegNotice();
                }
                return 0;
            } catch (IOException | RuntimeException e) {
                printInstallFailure(match.get(), modelRoot, e, System.err);
                return 1;
            }
        }

        private static void checkFfmpegNotice() {
            String configuredFfmpeg =
                    System.getProperty(ConfigKeys.STT_FFMPEG_PATH_PROPERTY, System.getenv(ConfigKeys.STT_FFMPEG_PATH));
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

    @Command(name = CliArguments.COMMAND_VERIFY, description = "Verify checksums of installed model bundles")
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
            if (modelName == null
                    || modelName.isBlank()
                    || ModelInstaller.SELECTOR_ALL.equalsIgnoreCase(modelName.trim())) {
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
