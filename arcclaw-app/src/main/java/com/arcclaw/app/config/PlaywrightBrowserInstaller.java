package com.arcclaw.app.config;

import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.jar.Manifest;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Playwright;
import org.apache.commons.lang3.exception.ExceptionUtils;
import org.jboss.logging.Logger;

/**
 * Handles the idempotent installation of Playwright browser binaries (specifically Chromium).
 *
 * <p>Addressing module boundaries: Since the Playwright dependency is contained within the
 * 'arcclaw-tool-playwright' module, we attempt to use 'mvnw' if available in the source tree
 * to ensure all dependencies are correctly resolved via Maven. For binary distributions,
 * we fall back to invoking the CLI from the current classpath.
 */
public class PlaywrightBrowserInstaller {

    private static final Logger log = Logger.getLogger(PlaywrightBrowserInstaller.class);

    private static final String PLAYWRIGHT_DRIVER_LOCATION = ".playwright-driver";

    /**
     * Checks if Chromium is already installed in the specified path.
     * Looks for any directory starting with 'chromium-' in the browsers path.
     */
    public static boolean isChromiumInstalled(Path browsersPath) {
        if (!Files.exists(browsersPath)) return false;
        String expectedRevision = readExpectedRevision(browsersPath.getParent());
        try (Stream<Path> files = Files.list(browsersPath)) {
            return files.anyMatch(p -> {
                String name = p.getFileName().toString();
                if (!Files.isDirectory(p)) return false;
                boolean nameMatch = expectedRevision != null
                    ? name.equals("chromium-" + expectedRevision) ||
                    name.equals("chromium_headless_shell-" + expectedRevision)
                    : name.startsWith("chromium-") ||
                    name.startsWith("chromium_headless_shell-");
                if (!nameMatch) return false;
                // verify the actual executable exists, not just the folder
                Path exe = isWindows()
                    ? p.resolve("chrome-headless-shell-win64").resolve("chrome-headless-shell.exe")
                    : p.resolve("chrome-headless-shell").resolve("chrome-headless-shell");
                return Files.exists(exe);
            });
        } catch (IOException e) { return false; }
    }

    private static String readExpectedRevision(Path binaryDir) {
        // The extracted driver's package.json has the expected browser revision
        Path pkgJson = binaryDir.resolve(PLAYWRIGHT_DRIVER_LOCATION)
            .resolve(detectPlatform()).resolve("package").resolve("package.json");
        if (!Files.exists(pkgJson)) return null;
        try {
            String content = Files.readString(pkgJson);
            // "chromium": { "revision": "1208" }
            var m = Pattern.compile("\"chromium\"[^}]+\"revision\":\\s*\"(\\d+)\"")
                .matcher(content);
            return m.find() ? m.group(1) : null;
        } catch (IOException e) { return null; }
    }

    /**
     * Installs Playwright Chromium browser.
     * Prefers using 'mvnw' if in a source tree; otherwise uses the current Java runtime.
     */
    public static void installChromium(Path browsersPath) {
        System.err.println();
        System.err.println("  → Installing Playwright Chromium into " + browsersPath + " ...");
        System.err.println("    (This may take a few minutes depending on your connection)");
        System.err.println();

        try {
            // FIRST: use extracted driver - guarantees version match with driver-bundle
            // binaryDir is parent of .playwright-browsers
            Path binaryDir = browsersPath.getParent();
            Path driverDir = binaryDir.resolve(PLAYWRIGHT_DRIVER_LOCATION).resolve(detectPlatform());
            Path nodeExe   = driverDir.resolve(isWindows() ? "node.exe" : "node");
            Path cliJs     = driverDir.resolve("package").resolve("cli.js");

            if (Files.exists(nodeExe) && Files.exists(cliJs)) {
                try {
                    log.info("Installing Chromium via extracted driver (version-matched)");
                    List<String> command = new ArrayList<>();
                    command.add(nodeExe.toAbsolutePath().toString());
                    command.add(cliJs.toAbsolutePath().toString());
                    command.add("install");
                    command.add("chromium");
                    ProcessBuilder pb = new ProcessBuilder(command);
                    pb.environment().put("PLAYWRIGHT_BROWSERS_PATH", browsersPath.toString());
                    pb.environment().put("PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD", "0");
                    pb.inheritIO();
                    int exit = pb.start().waitFor();
                    if (exit == 0) {
                        System.err.println();
                        System.err.println("  ✓ Playwright Chromium installed successfully.");
                        System.err.println();
                        System.err.println("  ─────────────────────────────────────────────────────");
                        System.err.println("  ✓ Browser ready - starting ArcClaw...");
                        System.err.println("  ─────────────────────────────────────────────────────");
                        System.err.println();
                        return;
                    }
                } catch (Exception e) {
                    log.warnf("Driver-based install failed (%s) - falling back...", safeMessage(e));
                }
            }

            // FALLBACK: mvnw (source tree) or npx/system (binary distribution)
            Path cwd  = Path.of(System.getProperty("user.dir"));
            Path mvnw = findMvnw(cwd);
            if (mvnw != null) {
                runMavenInstaller(mvnw, browsersPath);
            } else {
                runBinaryInstaller(browsersPath);
            }
        } catch (Exception e) {
            System.err.println();
            System.err.println("  ✗ Playwright Chromium installation failed: " + e.getMessage());
            log.error("Playwright installation failure", e);
        }
    }

    private static Path findMvnw(Path start) {
        Path p = start;
        while (p != null) {
            Path candidate = p.resolve("mvnw");
            if (Files.exists(candidate)) return candidate;
            p = p.getParent();
        }
        return null;
    }

    private static void runMavenInstaller(Path mvnw, Path browsersPath) throws Exception {
        List<String> command = new ArrayList<>();
        String os = System.getProperty("os.name", "").toLowerCase();

        if (os.contains("win")) {
            command.add("cmd.exe");
            command.add("/c");
            command.add("mvnw.cmd");
        } else {
            command.add("./mvnw");
        }

        command.add("exec:java");
        command.add("-e");
        command.add("-pl");
        command.add("arcclaw-tool-playwright");
        command.add("-Dexec.mainClass=com.microsoft.playwright.CLI");
        command.add("-Dexec.args=install --with-deps chromium");
        command.add("-DPLAYWRIGHT_BROWSERS_PATH=" + browsersPath.toString());

        ProcessBuilder pb = new ProcessBuilder(command);
        pb.directory(mvnw.getParent().toFile());
        pb.inheritIO();
        Process p = pb.start();
        int exitCode = p.waitFor();

        if (exitCode != 0) {
            throw new RuntimeException("Maven installer returned exit code " + exitCode);
        }

        System.err.println();
        System.err.println("  ✓ Playwright Chromium installed successfully.");

        // In promptPlaywrightSetupIfNeeded(), after installChromium() call:
        System.err.println();
        System.err.println("  ─────────────────────────────────────────────────────");
        System.err.println("  ✓ Browser ready - starting ArcClaw...");
        System.err.println("  ─────────────────────────────────────────────────────");
        System.err.println();
    }

    private static void runBinaryInstaller(Path browsersPath) throws Exception {
        StringBuilder attempts = new StringBuilder();

        // Classpath installer - naturally skipped on native image because
        // java.class.path is blank/unavailable, causing an immediate exception
        // that falls through to the next installer.
        try {
            runClassInstaller(browsersPath);
            return;
        } catch (Exception e) {
            attempts.append("classpath installer: ").append(safeMessage(e));
        }

        try {
            runSystemPlaywrightInstaller(browsersPath);
            return;
        } catch (Exception e) {
            if (!attempts.isEmpty()) attempts.append(" | ");
            attempts.append("system playwright CLI: ").append(safeMessage(e));
        }

        try {
            runNpxPlaywrightInstaller(browsersPath);
            return;
        } catch (Exception e) {
            if (!attempts.isEmpty()) attempts.append(" | ");
            attempts.append("npx playwright CLI: ").append(safeMessage(e));
        }

        // In-process installer - works on native image only when native binary was built
        // with com/microsoft/playwright/impl/driver/jar/** in native.resources.includes.
        // Without that, DriverJar.getDriverResourceURI() returns null (NPE). The exception
        // is caught below and reported as a failed attempt - no crash.
        try {
            runInProcessInstaller(browsersPath);
            return;
        } catch (Exception e) {
            if (!attempts.isEmpty()) attempts.append(" | ");
            attempts.append("in-process installer: ").append(safeMessage(e));
        }

        throw new RuntimeException(
            "Could not install Chromium. Tried: " + attempts +
            ". Install manually: playwright install chromium  " +
            "or from source: ./mvnw exec:java -pl arcclaw-tool-playwright" +
            " -Dexec.mainClass=com.microsoft.playwright.CLI" +
            " -Dexec.args=\"install --with-deps chromium\"");
    }

    private static void runClassInstaller(Path browsersPath) throws Exception {
        String classpath = System.getProperty("java.class.path");
        if (classpath == null || classpath.isBlank()) {
            throw new RuntimeException("java.class.path is unavailable");
        }

        List<String> command = new ArrayList<>();
        command.add(resolveJavaExecutable());
        command.add("-cp");
        command.add(classpath);
        command.add("-DPLAYWRIGHT_BROWSERS_PATH=" + browsersPath.toString());
        command.add("com.microsoft.playwright.CLI");
        command.add("install");
        command.add("--with-deps");
        command.add("chromium");

        ProcessBuilder pb = new ProcessBuilder(command);
        pb.inheritIO();
        Process p = pb.start();
        int exitCode = p.waitFor();

        if (exitCode == 0) {
            System.err.println();
            System.err.println("  ✓ Playwright Chromium installed successfully.");
        } else {
            throw new RuntimeException("CLI returned exit code " + exitCode);
        }
    }

    private static void runSystemPlaywrightInstaller(Path browsersPath) throws Exception {
        List<String> command = new ArrayList<>();
        if (isWindows()) {
            command.add("cmd.exe");
            command.add("/c");
            command.add("playwright");
        } else {
            command.add("playwright");
        }
        command.add("install");
        command.add("chromium");
        runExternalInstaller(command, browsersPath, "system playwright CLI");
    }

    private static void runNpxPlaywrightInstaller(Path browsersPath) throws Exception {
        List<String> command = new ArrayList<>();
        if (isWindows()) {
            command.add("cmd.exe");
            command.add("/c");
            command.add("npx");
        } else {
            command.add("npx");
        }
        command.add("--yes");
        command.add("playwright");
        command.add("install");
        command.add("chromium");
        runExternalInstaller(command, browsersPath, "npx playwright CLI");
    }

    private static void runExternalInstaller(List<String> command, Path browsersPath, String label)
            throws Exception {
        ProcessBuilder pb = new ProcessBuilder(command);
        pb.environment().put("PLAYWRIGHT_BROWSERS_PATH", browsersPath.toString());
        pb.environment().put("PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD", "0");
        pb.inheritIO();
        Process p = pb.start();
        int exitCode = p.waitFor();
        if (exitCode != 0) {
            throw new RuntimeException(label + " returned exit code " + exitCode);
        }
        System.err.println();
        System.err.println("  ✓ Playwright Chromium installed successfully.");
    }

    private static void runInProcessInstaller(Path browsersPath) {
        Map<String, String> env = new HashMap<>(System.getenv());
        // Ensure path is absolute for the installer subprocess (Fix for Windows Binary)
        String absolutePath = browsersPath.toAbsolutePath().normalize().toString();
        env.put("PLAYWRIGHT_BROWSERS_PATH", absolutePath);
        env.put("PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD", "0");

        log.infof("Running in-process Playwright installer with BROWSERS_PATH=%s", absolutePath);
        Playwright.CreateOptions options = new Playwright.CreateOptions().setEnv(env);
        try (Playwright playwright = Playwright.create(options)) {
            Browser browser = playwright.chromium().launch(
                new BrowserType.LaunchOptions().setHeadless(true));
            browser.close();
            System.err.println();
            System.err.println("  ✓ Playwright Chromium installed successfully.");
        }
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase().contains("win");
    }

    private static String safeMessage(Throwable t) {
        String msg = t.getMessage();
        return (msg == null || msg.isBlank()) ? t.getClass().getSimpleName() : msg;
    }

    private static String resolveJavaExecutable() {
        String os = System.getProperty("os.name", "").toLowerCase();
        String javaName = os.contains("win") ? "java.exe" : "java";

        String javaHome = System.getProperty("java.home");
        if (javaHome != null && !javaHome.isBlank()) {
            Path javaFromHome = Path.of(javaHome, "bin", javaName);
            if (Files.exists(javaFromHome)) return javaFromHome.toString();
        }

        String currentCommand = ProcessHandle.current().info().command().orElse("");
        if (!currentCommand.isBlank()) {
            Path commandPath = Path.of(currentCommand);
            Path fileName = commandPath.getFileName();
            if (fileName != null) {
                String runningName = fileName.toString().toLowerCase();
                if ("java".equals(runningName) || "java.exe".equals(runningName)) {
                    return commandPath.toString();
                }
            }
        }

        return javaName;
    }

    /**
     * Extracts the platform-specific Playwright Node.js CLI driver from driver-bundle.jar
     * to a stable directory alongside the binary. Sets playwright.cli.dir automatically.
     *
     * Called from AppBootstrap before Quarkus starts - transparent to users.
     * Re-extracts automatically when playwright version changes (version tracked in marker file).
     */
    public static void extractDriverIfNeeded(Path binaryDir) {
        String playwrightEnabled = System.getProperty("ARCCLAW_TOOLS_PLAYWRIGHT_ENABLED",
            System.getenv("ARCCLAW_TOOLS_PLAYWRIGHT_ENABLED") != null
                ? System.getenv("ARCCLAW_TOOLS_PLAYWRIGHT_ENABLED") : "false");
        if (!"true".equalsIgnoreCase(playwrightEnabled)) return;

        String platform  = detectPlatform();          // "win32_x64", "mac", "mac-arm64", "linux"
        Path  driverDir  = binaryDir.resolve(PLAYWRIGHT_DRIVER_LOCATION).resolve(platform);
        Path  markerFile = driverDir.resolve(".playwright-version");

        // Detect current version from driver-bundle JAR on classpath
        String currentVersion = detectPlaywrightVersion();

        // Check if already extracted and version matches
        if (Files.exists(driverDir) && Files.exists(markerFile)) {
            try {
                String extracted = Files.readString(markerFile).trim();
                if (extracted.equals(currentVersion)) {
                    log.debugf("Playwright driver already extracted (v%s) at %s", currentVersion, driverDir);
                    System.setProperty("playwright.cli.dir", driverDir.toString());
                    return;
                }
                log.infof("Playwright driver version changed: %s → %s, re-extracting...", extracted, currentVersion);
            } catch (IOException e) {
                log.debugf("Failed to read Playwright driver version: %s", ExceptionUtils.getRootCauseMessage(e));
            }
        }

        // Find driver-bundle JAR
        Path bundleJar = findDriverBundleJar(currentVersion);
        if (bundleJar == null) {
            log.warnf("driver-bundle-%s.jar not found - Playwright may fail to initialize in native mode.", currentVersion);
            return;
        }

        try {
            log.infof("Extracting Playwright CLI driver (v%s, %s) to %s", currentVersion, platform, driverDir);
            extractFromJar(bundleJar, "driver/" + platform, driverDir);

            // Make executables on Unix
            if (!isWindows()) {
                makeExecutable(driverDir.resolve("playwright"));
                makeExecutable(driverDir.resolve("node"));
            }

            // Write version marker
            Files.writeString(markerFile, currentVersion);
            System.setProperty("playwright.cli.dir", driverDir.toString());
            log.infof("Playwright CLI driver ready at %s", driverDir);

        } catch (Exception e) {
            log.warnf("Failed to extract Playwright driver: %s - will fall back to DriverJar", e.getMessage());
        }
    }

    private static String detectPlatform() {
        String os   = System.getProperty("os.name", "").toLowerCase();
        String arch = System.getProperty("os.arch", "").toLowerCase();
        if (os.contains("win"))   return "win32_x64";
        if (os.contains("mac"))   return arch.contains("aarch64") ? "mac-arm64" : "mac";
        return "linux";  // linux / linux-arm64 - extend if needed
    }

    private static String detectPlaywrightVersion() {
        // 1. Try MANIFEST.MF in playwright JAR on classpath
        try {
            Enumeration<URL> resources = PlaywrightBrowserInstaller.class
                .getClassLoader().getResources("META-INF/MANIFEST.MF");
            while (resources.hasMoreElements()) {
                try (var in = resources.nextElement().openStream()) {
                    Manifest mf = new Manifest(in);
                    String ver = mf.getMainAttributes().getValue("Implementation-Version");
                    if (ver != null && ver.matches("\\d+\\.\\d+\\.\\d+")) return ver;
                } catch (Exception e) {
                    log.debugf("Failed to read Playwright manifest: %s", ExceptionUtils.getRootCauseMessage(e));
                }
            }
        } catch (Exception e) {
            log.debugf("Failed to list Playwright manifest resources: %s", ExceptionUtils.getRootCauseMessage(e));
        }

        // 2. Fallback: scan Maven local repo for known driver-bundle version
        Path m2 = Path.of(System.getProperty("user.home"), ".m2", "repository",
            "com", "microsoft", "playwright", "driver-bundle");
        if (Files.exists(m2)) {
            try (Stream<Path> dirs = Files.list(m2)) {
                return dirs.filter(Files::isDirectory)
                    .map(p -> p.getFileName().toString())
                    .filter(v -> v.matches("\\d+\\.\\d+\\.\\d+"))
                    .sorted(Comparator.reverseOrder())
                    .findFirst().orElse("unknown");
            } catch (IOException e) {
                log.debugf("Failed to read Playwright driver version: %s", ExceptionUtils.getRootCauseMessage(e));
            }
        }
        return "unknown";
    }

    private static Path findDriverBundleJar(String version) {
        // 1. Maven local repo (works on dev machine and CI)
        Path m2Jar = Path.of(System.getProperty("user.home"), ".m2", "repository",
            "com", "microsoft", "playwright", "driver-bundle", version,
            "driver-bundle-" + version + ".jar");
        if (Files.exists(m2Jar)) return m2Jar;

        // 2. Classpath scan (JVM mode - finds it inside fat JAR or classpath entry)
        String cp = System.getProperty("java.class.path", "");
        for (String entry : cp.split(File.pathSeparator)) {
            if (entry.contains("driver-bundle") && entry.endsWith(".jar")) {
                Path p = Path.of(entry);
                if (Files.exists(p)) return p;
            }
        }
        return null;
    }

    private static void extractFromJar(Path jar, String jarPrefix, Path targetDir) throws Exception {
        Files.createDirectories(targetDir);
        String prefixWithSlash = jarPrefix + "/";
        try (var zf = new ZipFile(jar.toFile())) {
            Enumeration<? extends ZipEntry> entries = zf.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                if (!entry.getName().startsWith(prefixWithSlash)) continue;
                // Strip "driver/win32_x64/" prefix → relative path under targetDir
                String relative = entry.getName().substring(prefixWithSlash.length());
                if (relative.isEmpty()) continue;
                Path out = targetDir.resolve(relative).normalize();
                if (!out.startsWith(targetDir)) continue; // zip-slip guard
                if (entry.isDirectory()) {
                    Files.createDirectories(out);
                } else {
                    Files.createDirectories(out.getParent());
                    try (var in = zf.getInputStream(entry)) {
                        Files.copy(in, out, StandardCopyOption.REPLACE_EXISTING);
                    }
                }
            }
        }
    }

    private static void makeExecutable(Path file) {
        if (Files.exists(file)) file.toFile().setExecutable(true);
    }
}
