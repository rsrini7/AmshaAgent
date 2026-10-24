package com.amsha.app.cli;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import com.amsha.localml.onnx.OnnxModelInstallGuidance;

/** Builds actionable model-management commands for JVM and native launches. */
final class ModelLauncherCommands {

    private static final String EXECUTABLE_AMSHA_PREFIX = "amsha";
    private static final List<String> NATIVE_BINARY_NAMES = OnnxModelInstallGuidance.nativeBinaryNames();

    private ModelLauncherCommands() {}

    static List<String> launcherCommands() {
        boolean windows = OnnxModelInstallGuidance.isWindows();
        Path binaryDirectory = nativeExecutablePath().map(Path::getParent).orElse(null);
        return NATIVE_BINARY_NAMES.stream()
                .map(binaryName -> windows ? binaryName + ".exe" : binaryName)
                .map(binaryName -> binaryDirectory == null ? Path.of(binaryName) : binaryDirectory.resolve(binaryName))
                .map(path -> formatLauncherPath(path, windows))
                .toList();
    }

    static String launcherCommand() {
        boolean windows = OnnxModelInstallGuidance.isWindows();
        return nativeExecutablePath()
                .map(path -> formatLauncherPath(path, windows))
                .orElse(ModelInstaller.JVM_LAUNCHER_COMMAND);
    }

    private static Optional<Path> nativeExecutablePath() {
        String processCommand = ProcessHandle.current().info().command().orElse("");
        if (processCommand.isBlank()) {
            return Optional.empty();
        }
        Path commandPath = Path.of(processCommand);
        Path executable = commandPath.getFileName();
        return executable != null && executable.toString().startsWith(EXECUTABLE_AMSHA_PREFIX)
                ? Optional.of(commandPath)
                : Optional.empty();
    }

    private static String formatLauncherPath(Path path, boolean windows) {
        String launchPath = path.getParent() == null ? windows ? ".\\" + path : "./" + path : path.toString();
        return launchPath.chars().anyMatch(Character::isWhitespace)
                ? "\"" + launchPath.replace("\"", "\\\"") + "\""
                : launchPath;
    }
}
