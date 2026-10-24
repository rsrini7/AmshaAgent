package com.amsha.localml.onnx;

import java.util.List;
import java.util.Locale;

/** Shared command names and installation hints for the supported Amsha runtimes. */
public final class OnnxModelInstallGuidance {
    public static final String DEVELOPER_JAR_LAUNCHER = "mise run jar:run-dev-dep --";

    private static final String OS_WINDOWS_PREFIX = "windows";
    private static final List<NativeLauncher> NATIVE_LAUNCHERS = List.of(
            new NativeLauncher("SQLite", "amsha-sqlite"),
            new NativeLauncher("PostgreSQL", "amsha-pg"),
            new NativeLauncher("Filesystem", "amsha-fs"));

    private OnnxModelInstallGuidance() {}

    public static List<String> nativeBinaryNames() {
        return NATIVE_LAUNCHERS.stream().map(NativeLauncher::executable).toList();
    }

    public static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith(OS_WINDOWS_PREFIX);
    }

    public static String forSelector(String selector) {
        boolean windows = isWindows();
        StringBuilder guidance = new StringBuilder("Install it using one of these options:\n")
                .append("  Developer JAR (run from source checkout root): ")
                .append(DEVELOPER_JAR_LAUNCHER)
                .append(" models install ")
                .append(selector);
        for (NativeLauncher launcher : NATIVE_LAUNCHERS) {
            guidance.append("\n  ")
                    .append(launcher.backend())
                    .append(" native (run from binary directory): ")
                    .append(windows ? ".\\" : "./")
                    .append(launcher.executable())
                    .append(windows ? ".exe" : "")
                    .append(" models install ")
                    .append(selector);
        }
        return guidance.toString();
    }

    private record NativeLauncher(String backend, String executable) {}
}
