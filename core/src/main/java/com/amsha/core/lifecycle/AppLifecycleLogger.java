package com.amsha.core.lifecycle;

import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import jakarta.interceptor.Interceptor;

import java.lang.management.ManagementFactory;

import com.amsha.core.config.SystemMetadataRegistry;

import org.jboss.logging.Logger;

import io.quarkus.runtime.StartupEvent;

/**
 * Logs application lifecycle events in a concise format,
 * matching the built-in Quarkus shutdown message.
 */
@ApplicationScoped
public class AppLifecycleLogger {
    private static final Logger log = Logger.getLogger("com.amsha.lifecycle");

    @Inject
    SystemMetadataRegistry metadata;

    void onStart(@Observes @Priority(Interceptor.Priority.APPLICATION + 1000) StartupEvent ev) {
        double uptime = ManagementFactory.getRuntimeMXBean().getUptime() / 1000.0;
        log.infof("%s started in %.3fs", metadata.getAppName(), uptime);
    }
}
