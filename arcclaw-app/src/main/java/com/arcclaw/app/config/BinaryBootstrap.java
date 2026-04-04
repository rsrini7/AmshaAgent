package com.arcclaw.app.config;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Bootstraps the application by resolving the binary directory, loading .env files,
 * extracting default config, and setting up environment variables before Quarkus starts.
 */
public class BinaryBootstrap {

    private static final Pattern ENV_PATTERN = Pattern.compile("^\\s*([A-Za-z_][A-Za-z0-9_.]*?)\\s*=\\s*(.*?)\\s*$");

    public static void bootstrap(String[] args) {
        Path binaryDir = resolveBinaryDir();
        
        reexecWithLdPreloadIfNeeded(args);
        loadDotEnv(binaryDir);
        extractEnvExampleIfNeeded(binaryDir);
        
        // 1. Resolve and Provision Data Directory
        String rawDataDir = System.getProperty("ARCCLAW_DATA_DIR", 
                System.getenv().getOrDefault("ARCCLAW_DATA_DIR", binaryDir.resolve("data").toString()));
        try {
            Path dirPath = Path.of(rawDataDir).toAbsolutePath().normalize();
            if (!Files.exists(dirPath)) {
                Files.createDirectories(dirPath);
                Files.createDirectories(dirPath.resolve("workspaces"));
            }
            System.setProperty("ARCCLAW_DATA_DIR", dirPath.toString());
        } catch (IOException e) {
            System.err.println("[ArcClaw] ⚠️  Warning: Failed to create data directory: " + e.getMessage());
        }

        // 2. Resolve and Provision Native Libraries (sqlite-vec)
        String vecPath = System.getProperty("ARCCLAW_RAG_VEC_PATH",
                System.getenv().getOrDefault("ARCCLAW_RAG_VEC_PATH", binaryDir.resolve("lib/vec0").toString()));
        try {
            Path absVecPath = Path.of(vecPath).toAbsolutePath().normalize();
            if (vecPath.contains("vec0")) { // Base path for extraction
                Vec0NativeLibraryInstaller.performInstallation(absVecPath.toString());
            }
            System.setProperty("ARCCLAW_RAG_VEC_PATH", absVecPath.toString());
        } catch (Exception e) {
            System.err.println("[ArcClaw] ⚠️  Warning: Failed to provision sqlite-vec: " + e.getMessage());
        }
    }

    private static Path resolveBinaryDir() {
        // 1. If running as Native Image (GraalVM)
        if (System.getProperty("org.graalvm.nativeimage.imagecode") != null) {
            String command = ProcessHandle.current().info().command().orElse("");
            if (!command.isEmpty()) {
                try {
                    Path p = Path.of(command).toAbsolutePath().getParent();
                    if (Files.exists(p)) return p;
                } catch (Exception ignored) {}
            }
        }

        // 2. Fallback for JAR or IDE: Use the location of the JAR file
        try {
            var codeSource = BinaryBootstrap.class.getProtectionDomain().getCodeSource();
            if (codeSource != null) {
                Path classPath = Path.of(codeSource.getLocation().toURI());
                if (Files.isRegularFile(classPath)) { // It's a JAR or regular file
                    return classPath.getParent().toAbsolutePath();
                }
            }
        } catch (Exception ignored) {}

        // 3. Fallback: Current Working Directory
        return Path.of(System.getProperty("user.dir")).toAbsolutePath();
    }

    private static void reexecWithLdPreloadIfNeeded(String[] args) {
        String os = System.getProperty("os.name").toLowerCase();
        if (!os.contains("linux")) return;
        if (System.getenv("ARCCLAW_LD_PRELOAD_SET") != null) return;
        if (System.getenv("LD_PRELOAD") != null) return; // Skip re-exec if already set by caller

        String libmPath = null;
        String[] candidates = {
            "/lib/x86_64-linux-gnu/libm.so.6",
            "/lib64/libm.so.6",
            "/usr/lib/x86_64-linux-gnu/libm.so.6",
            "/lib/aarch64-linux-gnu/libm.so.6"
        };

        for (String c : candidates) {
            if (Files.exists(Path.of(c))) {
                libmPath = c;
                break;
            }
        }

        if (libmPath == null) {
            System.err.println("[ArcClaw] ⚠️  Warning: libm.so.6 not found for LD_PRELOAD. Execution might fail on some Linux distros.");
            return;
        }

        String command = ProcessHandle.current().info().command().orElse("");
        if (command.isEmpty()) return;

        try {
            ProcessBuilder pb = new ProcessBuilder();
            pb.command().add(command);
            for (String arg : args) pb.command().add(arg);
            
            pb.environment().put("LD_PRELOAD", libmPath);
            pb.environment().put("ARCCLAW_LD_PRELOAD_SET", "1");
            pb.inheritIO();
            
            Process p = pb.start();
            System.exit(p.waitFor());
        } catch (Exception e) {
            System.err.println("[ArcClaw] ⚠️  Warning: Failed to re-exec with LD_PRELOAD: " + e.getMessage());
        }
    }

    static void loadDotEnv(Path dir) {
        Path envFile = dir.resolve(".env");
        if (!Files.exists(envFile)) return;

        try (BufferedReader reader = Files.newBufferedReader(envFile)) {
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#")) continue;

                Matcher m = ENV_PATTERN.matcher(line);
                if (m.find()) {
                    String key = m.group(1);
                    String value = m.group(2).trim();

                    // Strip inline comments
                    if (value.contains(" #") || value.contains("\t#")) {
                        if (!(value.startsWith("\"") || value.startsWith("'"))) {
                            int idx = value.indexOf(" #");
                            if (idx == -1) idx = value.indexOf("\t#");
                            value = value.substring(0, idx).trim();
                        }
                    }

                    if (System.getenv(key) == null && System.getProperty(key) == null) {
                        System.setProperty(key, value);
                    }
                }
            }
        } catch (IOException e) {
            System.err.println("[ArcClaw] ⚠️  Warning: Failed to read .env: " + e.getMessage());
        }
    }

    private static void extractEnvExampleIfNeeded(Path dir) {
        Path envFile = dir.resolve(".env");
        if (Files.exists(envFile)) return;

        Path exampleFile = dir.resolve(".env.example");
        if (!Files.exists(exampleFile)) {
            try (InputStream in = BinaryBootstrap.class.getResourceAsStream("/env-example")) {
                if (in == null) {
                    System.err.println("[ArcClaw] ⚠️  Warning: env-example not found in classpath.");
                    return;
                }
                Files.copy(in, exampleFile, StandardCopyOption.REPLACE_EXISTING);
                System.err.println("[ArcClaw] No .env found. Created .env.example next to the binary.");
                System.err.println("[ArcClaw] Copy and configure it:  cp .env.example .env");
            } catch (IOException e) {
                System.err.println("[ArcClaw] ⚠️  Warning: Failed to extract .env.example: " + e.getMessage());
            }
        } else {
            System.err.println("[ArcClaw] No .env found. Use the existing .env.example:  cp .env.example .env");
        }
    }
}
