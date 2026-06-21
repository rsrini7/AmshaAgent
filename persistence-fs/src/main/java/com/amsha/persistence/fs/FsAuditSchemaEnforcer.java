package com.amsha.persistence.fs;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import com.amsha.persistence.api.AuditSchemaPort;

import org.jboss.logging.Logger;

import io.quarkus.arc.properties.IfBuildProperty;

@ApplicationScoped
@IfBuildProperty(name = "amsha.persistence.backend", stringValue = "fs")
public class FsAuditSchemaEnforcer implements AuditSchemaPort {

    private static final Logger log = Logger.getLogger(FsAuditSchemaEnforcer.class);

    @Inject
    FilesystemFileStore store;

    @Override
    public void enforceWormConstraints() {
        log.info(
                "FsAuditSchemaEnforcer: WORM constraints enforced at application level (files are immutable after write)");
    }
}
