package com.amsha.app.config;

import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import io.quarkus.runtime.StartupEvent;

/**
 * Validates that the runtime persistence backend matches the backend
 * baked into the native image at build time.
 * <p>
 * In JVM mode, this is a no-op — all backend jars are on the classpath
 * and {@code @IfBuildProperty} selects which beans activate at runtime.
 * <p>
 * In native mode, only the baked-in backend's CDI beans exist in the image.
 * If the user's {@code .env} sets a different backend, we detect the mismatch
 * early with a clear error message instead of letting Quarkus throw a cryptic
 * {@code UnsatisfiedResolutionException} or Flyway crash.
 */
@ApplicationScoped
public class BackendValidator {

    private static final Logger log = Logger.getLogger(BackendValidator.class);

    @ConfigProperty(name = "amsha.persistence.backend", defaultValue = "sqlite")
    String runtimeBackend;

    void onStart(@Observes @Priority(1) StartupEvent ev) {
        String buildBackend = readBuildBackend();

        if ("default".equals(buildBackend)) {
            // JVM mode — all backends available, no validation needed
            log.debugf(
                    "BackendValidator: JVM mode (build-backend=default), runtime=%s — all backends available.",
                    runtimeBackend);
            return;
        }

        // Native mode — build-backend was baked in
        if (!buildBackend.equals(runtimeBackend)) {
            log.errorf(
                    "╔══════════════════════════════════════════════════════════════════╗%n"
                            + "║  BACKEND MISMATCH DETECTED                                      ║%n"
                            + "╠══════════════════════════════════════════════════════════════════╣%n"
                            + "║  This binary was built for backend:  %-28s ║%n"
                            + "║  Your .env is configured for:        %-28s ║%n"
                            + "║                                                                    ║%n"
                            + "║  Options:                                                          ║%n"
                            + "║    1. Use the correct binary:  amsha-%-24s ║%n"
                            + "║    2. Change .env:  AMSHA_PERSISTENCE_BACKEND=%-15s ║%n"
                            + "║    3. Rebuild:  mise run build:native-%-22s  ║%n"
                            + "╚══════════════════════════════════════════════════════════════════╝",
                    buildBackend,
                    runtimeBackend,
                    buildBackend.equals("postgres") ? "pg" : buildBackend,
                    buildBackend,
                    buildBackend.equals("postgres") ? "pg" : buildBackend);
            throw new BackendMismatchException(String.format(
                    "Native binary built for '%s' but .env has AMSHA_PERSISTENCE_BACKEND=%s. "
                            + "Use the '%s' binary or set AMSHA_PERSISTENCE_BACKEND=%s in .env.",
                    buildBackend, runtimeBackend, buildBackend, buildBackend));
        }

        log.infof("BackendValidator: native binary matches runtime config — backend=%s.", buildBackend);
    }

    private static String readBuildBackend() {
        try (InputStream is = BackendValidator.class.getClassLoader().getResourceAsStream("build-info.properties")) {
            if (is == null) {
                return "default";
            }
            Properties props = new Properties();
            props.load(is);
            return props.getProperty("quarkus.amsha.build.backend", "default");
        } catch (IOException e) {
            log.debugf("BackendValidator: could not read build-info.properties — assuming JVM mode.");
            return "default";
        }
    }
}
