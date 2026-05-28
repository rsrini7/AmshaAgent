package com.arcclaw.core.config;

import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.logging.Level;
import java.util.logging.Logger;

import io.quarkus.runtime.Startup;

@ApplicationScoped
@Startup
public class RetryUtilsLogFilter {

    @PostConstruct
    void install() {
        Logger logger = Logger.getLogger("dev.langchain4j.internal.RetryUtils");
        logger.setFilter(record -> !(record.getLevel().equals(Level.WARNING)
                && record.getMessage() != null
                && record.getMessage().startsWith("A retriable exception occurred")));
    }
}
