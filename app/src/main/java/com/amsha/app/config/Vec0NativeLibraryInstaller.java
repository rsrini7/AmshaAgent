package com.amsha.app.config;

import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Locale;

import com.amsha.common.util.LogUtil;

import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import io.quarkus.runtime.StartupEvent;

/**
 * Extracts the correct sqlite-vec native binary for the current OS/arch
 * from JAR resources on first boot, eliminating manual vec0 setup.
 *
 * <p>Fires at priority 50 - before {@code RagSchemaInitializer} (priority 100)
 * to ensure vec0 is available before the first SQLite connection is opened.
 */
@ApplicationScoped
public class Vec0NativeLibraryInstaller {

    private static final Logger log = Logger.getLogger(Vec0NativeLibraryInstaller.class);

    private static final String RESOURCE_PREFIX = "/native/vec0/vec0-";

    @ConfigProperty(name = "amsha.rag.vec-extension-path", defaultValue = "./lib/vec0")
    String configuredVecPath;

    void onStart(@Observes @Priority(10) StartupEvent ev) {
        performInstallation(configuredVecPath);
    }

    /**
     * Performs the extraction of sqlite-vec native library from resources.
     * Can be called as a CDI observer or statically from main().
     */
    public static void performInstallation(String configuredVecPath) {
        if (configuredVecPath == null) return;

        Path target = Path.of(configuredVecPath + nativeExtension());
        if (Files.exists(target)) {
            log.infof("Vec0 binary already present at [%s] - skipping auto-install.", target);
            return;
        }

        String resourceName = RESOURCE_PREFIX + osArchKey() + nativeExtension();
        log.infof("Vec0 auto-install: extracting [%s] from JAR resources.", resourceName);

        try (InputStream in = Vec0NativeLibraryInstaller.class.getResourceAsStream(resourceName)) {
            if (in == null) {
                log.warnf(
                        "Vec0 binary not found in JAR for platform [%s]. "
                                + "RAG vector search will degrade to FTS5-only. "
                                + "Manually place the binary at [%s].",
                        osArchKey(), target);
                return;
            }
            Files.createDirectories(target.getParent());
            Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
            log.infof("Vec0 binary installed to [%s].", target.toAbsolutePath());
        } catch (IOException e) {
            log.warnf("Vec0 auto-install failed: %s. RAG will degrade to FTS5-only.", LogUtil.rootMessage(e));
        }
    }

    /** Returns the OS+arch key used in the resource filename. */
    static String osArchKey() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        String arch = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);

        if (os.contains("linux")) return arch.contains("aarch64") ? "linux-aarch64" : "linux-x86_64";
        if (os.contains("mac")) return arch.contains("aarch64") ? "macos-aarch64" : "macos-x86_64";
        if (os.contains("windows")) return "windows-x86_64";
        return "unknown";
    }

    /** Returns the native library file extension for the current OS. */
    static String nativeExtension() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (os.contains("windows")) return ".dll";
        if (os.contains("mac")) return ".dylib";
        return ".so";
    }
}
