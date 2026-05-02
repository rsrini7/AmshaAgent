package com.arcclaw.app.config;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Enumeration;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import org.apache.commons.lang3.exception.ExceptionUtils;
import org.jboss.logging.Logger;

/**
 * Handles the idempotent installation of Playwright browser binaries (specifically Chromium).
 * Focuses on driver extraction from bundle JARs.
 */
public class PlaywrightBrowserInstaller {

    private static final Logger log = Logger.getLogger(PlaywrightBrowserInstaller.class);

    private static final String PLAYWRIGHT_DRIVER_LOCATION = ".playwright-driver";

    public static boolean isChromiumInstalled(Path browsersPath) {
        return BrowserHealthChecker.isChromiumInstalled(browsersPath);
    }

    public static void installChromium(Path browsersPath) {
        BrowserDownloadService.installChromium(browsersPath);
    }

    /**
     * Extracts the platform-specific Playwright Node.js CLI driver from driver-bundle.jar
     * to a stable directory alongside the binary. Sets playwright.cli.dir automatically.
     */
    public static void extractDriverIfNeeded(Path binaryDir) {
        String playwrightEnabled = System.getProperty("ARCCLAW_TOOLS_PLAYWRIGHT_ENABLED",
            System.getenv("ARCCLAW_TOOLS_PLAYWRIGHT_ENABLED") != null
                ? System.getenv("ARCCLAW_TOOLS_PLAYWRIGHT_ENABLED") : "false");
        if (!"true".equalsIgnoreCase(playwrightEnabled)) return;

        String platform  = BrowserHealthChecker.detectPlatform();
        Path  driverDir  = binaryDir.resolve(PLAYWRIGHT_DRIVER_LOCATION).resolve(platform).toAbsolutePath().normalize();
        Path  markerFile = driverDir.resolve(".playwright-version");

        String currentVersion = BrowserHealthChecker.detectPlaywrightVersion();

        Path nodeExe = driverDir.resolve(isWindows() ? "node.exe" : "node");
        if (Files.exists(driverDir) && Files.exists(markerFile) && Files.exists(nodeExe)) {
            try {
                String extracted = Files.readString(markerFile).trim();
                if (extracted.equals(currentVersion) && !currentVersion.equals("unknown")) {
                    log.debugf("Playwright driver already extracted (v%s) at %s", currentVersion, driverDir);
                    System.setProperty("playwright.cli.dir", driverDir.toString());
                    return;
                }
            } catch (IOException e) {
                log.debugf("Failed to read Playwright driver version: %s", ExceptionUtils.getRootCauseMessage(e));
            }
        }

        Path bundleJar = findDriverBundleJar(currentVersion);
        if (bundleJar == null) {
            log.warnf("driver-bundle-%s.jar not found - Playwright may fail to initialize in native mode.", currentVersion);
            return;
        }

        try {
            log.infof("Extracting Playwright CLI driver (v%s, %s) to %s", currentVersion, platform, driverDir);
            extractFromJar(bundleJar, "driver/" + platform, driverDir);

            if (!isWindows()) {
                makeExecutable(driverDir.resolve("playwright"));
                makeExecutable(driverDir.resolve("node"));
            }

            Files.writeString(markerFile, currentVersion);
            System.setProperty("playwright.cli.dir", driverDir.toString());
            log.infof("Playwright CLI driver ready at %s", driverDir);

        } catch (Exception e) {
            log.warnf("Failed to extract Playwright driver: %s", e.getMessage());
            if (System.getProperty("org.graalvm.nativeimage.imagecode") != null) {
                System.err.println();
                System.err.println("  ⚠️  Playwright Driver Missing (Native Mode)");
                System.err.println("     Could not find driver-bundle JAR to extract the Node.js driver.");
                System.err.println("     Please place 'driver-bundle-" + currentVersion + ".jar' next to the binary");
                System.err.println("     or extract it manually to: " + driverDir);
                System.err.println();
            }
        }
    }

    private static Path findDriverBundleJar(String version) {
        Path m2Jar = Path.of(System.getProperty("user.home"), ".m2", "repository",
            "com", "microsoft", "playwright", "driver-bundle", version,
            "driver-bundle-" + version + ".jar");
        if (Files.exists(m2Jar)) return m2Jar;

        String cp = System.getProperty("java.class.path", "");
        for (String entry : cp.split(File.pathSeparator)) {
            if (entry.contains("driver-bundle") && entry.endsWith(".jar")) {
                Path p = Path.of(entry);
                if (Files.exists(p)) return p;
            }
        }

        Path cwdJar = Path.of(System.getProperty("user.dir")).resolve("driver-bundle-" + version + ".jar");
        if (Files.exists(cwdJar)) return cwdJar;
        
        Path cwdJarSimple = Path.of(System.getProperty("user.dir")).resolve("driver-bundle.jar");
        if (Files.exists(cwdJarSimple)) return cwdJarSimple;

        return null;
    }

    private static void extractFromJar(Path jar, String jarPrefix, Path targetDir) throws Exception {
        targetDir = targetDir.toAbsolutePath().normalize();
        Files.createDirectories(targetDir);
        String prefixWithSlash = jarPrefix + "/";
        try (var zf = new ZipFile(jar.toFile())) {
            Enumeration<? extends ZipEntry> entries = zf.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                if (!entry.getName().startsWith(prefixWithSlash)) continue;
                String relative = entry.getName().substring(prefixWithSlash.length());
                if (relative.isEmpty()) continue;
                Path out = targetDir.resolve(relative).normalize();
                if (!out.startsWith(targetDir)) continue;
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

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase().contains("win");
    }
}
