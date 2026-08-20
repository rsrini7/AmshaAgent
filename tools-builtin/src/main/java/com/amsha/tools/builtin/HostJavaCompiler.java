package com.amsha.tools.builtin;

import jakarta.inject.Singleton;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

import org.jboss.logging.Logger;

@Singleton
public class HostJavaCompiler {

    private static final Logger log = Logger.getLogger(HostJavaCompiler.class);

    static final boolean IS_WINDOWS =
            System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");

    public void compile(Path javaFile, Path outputDir) throws IOException {
        Path javacExe = resolveJavac();
        if (javacExe == null) {
            throw new IOException("Java compilation failed: 'javac' compiler not found on the host system. "
                    + "Please ensure a full JDK is installed and 'javac' is on the PATH.");
        }

        ProcessBuilder pb = new ProcessBuilder(
                javacExe.toString(),
                "-source",
                "21",
                "-target",
                "21",
                "-d",
                outputDir.toString(),
                javaFile.toAbsolutePath().toString());
        pb.redirectErrorStream(true);
        pb.directory(outputDir.toFile());

        Process process = pb.start();
        String compilerOutput = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        int exitCode;
        try {
            exitCode = process.waitFor();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("javac was interrupted: " + e.getMessage());
        }

        if (exitCode != 0) {
            throw new IOException("Java compilation failed (exit " + exitCode + "):\n" + compilerOutput);
        }
        log.debugf("[HostJavaCompiler] javac compiled %s → %s (exit 0)", javaFile.getFileName(), outputDir);
    }

    private Path resolveJavac() {
        String javaHome = System.getProperty("java.home");
        if (javaHome != null) {
            Path javac = Path.of(javaHome, "bin", IS_WINDOWS ? "javac.exe" : "javac");
            if (Files.exists(javac)) return javac;
        }
        return isCommandAvailable("javac") ? Path.of("javac") : null;
    }

    private boolean isCommandAvailable(String cmd) {
        try {
            Process process = new ProcessBuilder(IS_WINDOWS ? "where" : "which", cmd).start();
            return process.waitFor() == 0;
        } catch (Exception e) {
            return false;
        }
    }
}
