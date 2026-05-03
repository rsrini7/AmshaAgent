package com.arcclaw.app.config;

import java.io.IOException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.Optional;
import java.util.jar.Manifest;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import com.microsoft.playwright.Playwright;
import org.apache.commons.lang3.exception.ExceptionUtils;
import org.jboss.logging.Logger;

/**
 * Handles checking the installation status and version consistency of Playwright browsers.
 */
public class BrowserHealthChecker {

    private static final Logger log = Logger.getLogger(BrowserHealthChecker.class);

    public static boolean isChromiumInstalled(Path browsersPath) {
        Path sentinel = browsersPath.resolve(".arcclaw-playwright-version");
        String currentVersion = detectPlaywrightVersion();
        if (Files.exists(sentinel)) {
            try {
                String cachedVersion = Files.readString(sentinel).trim();
                if (!currentVersion.equals(cachedVersion)) {
                    log.warnf("[BrowserHealthChecker] Playwright upgraded %s → %s, wiping browser cache",
                              cachedVersion, currentVersion);
                    deleteDirectory(browsersPath);
                    return false;
                }
            } catch (IOException e) {
                log.warnf("[BrowserHealthChecker] Could not read sentinel file: %s", e.getMessage());
            }
        }

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
                try (Stream<Path> subDirs = Files.list(p)) {
                    return subDirs.anyMatch(sub -> {
                        String subName = sub.getFileName().toString();
                        if (!subName.startsWith("chrome-headless-shell")) return false;
                        Path exe = isWindows()
                            ? sub.resolve("chrome-headless-shell.exe")
                            : sub.resolve("chrome-headless-shell");
                        return Files.exists(exe);
                    });
                } catch (IOException e) { return false; }
            });
        } catch (IOException e) { return false; }
    }

    public static String detectPlaywrightVersion() {
        // 1. Try to fetch version dynamically from the Playwright class URL location
        try {
            URL u = Playwright.class.getResource("/com/microsoft/playwright/Playwright.class");
            if (u != null) {
                String path = u.toString();
                var m = Pattern.compile("(?i)playwright-(?:driver-bundle-)?(\\d+\\.\\d+\\.\\d+)").matcher(path);
                if (m.find()) return m.group(1);
            }
        } catch (Exception e) {
            log.debugf("Failed to read Playwright version dynamically from class URL: %s", ExceptionUtils.getRootCauseMessage(e));
        }

        // 2. Try getting version from the package's implementation version
        try {
            String ver = Playwright.class.getPackage().getImplementationVersion();
            if (ver != null && ver.matches("\\d+\\.\\d+\\.\\d+")) return ver;
        } catch (Exception ignored) {}

        // 3. Fallback: Try MANIFEST.MF on classpath
        try {
            Enumeration<URL> resources = BrowserHealthChecker.class
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

        // 4. Fallback: scan current directory for driver-bundle-*.jar
        try (Stream<Path> files = Files.list(Path.of(System.getProperty("user.dir")))) {
            Optional<String> found = files.map(p -> p.getFileName().toString())
                .filter(name -> name.startsWith("driver-bundle-") && name.endsWith(".jar"))
                .map(name -> name.substring("driver-bundle-".length(), name.length() - ".jar".length()))
                .filter(v -> v.matches("\\d+\\.\\d+\\.\\d+"))
                .sorted(Comparator.reverseOrder())
                .findFirst();
            if (found.isPresent()) return found.get();
        } catch (IOException ignored) {}

        return "1.56.0"; // Final fallback matching project pom.xml and quarkus-playwright 2.2.1
    }

    private static String readExpectedRevision(Path binaryDir) {
        // The extracted driver's package.json has the expected browser revision
        Path pkgJson = binaryDir.resolve(".playwright-driver")
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

    public static String detectPlatform() {
        String os   = System.getProperty("os.name", "").toLowerCase();
        String arch = System.getProperty("os.arch", "").toLowerCase();
        if (os.contains("win"))   return "win32_x64";
        if (os.contains("mac"))   return arch.contains("aarch64") ? "mac-arm64" : "mac";
        return "linux";
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase().contains("win");
    }

    public static void deleteDirectory(Path dir) {
        if (!Files.exists(dir)) return;
        try (Stream<Path> walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder())
                .forEach(p -> {
                    try {
                        Files.delete(p);
                    } catch (IOException e) {
                        log.debugf("Benign delete error: %s", e.getMessage());
                    }
                });
        } catch (IOException e) {
            log.debugf("Failed to delete directory %s: %s", dir, e.getMessage());
        }
    }
}
