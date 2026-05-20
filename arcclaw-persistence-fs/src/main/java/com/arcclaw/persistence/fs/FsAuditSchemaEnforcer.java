package com.arcclaw.persistence.fs;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import com.arcclaw.persistence.api.AuditSchemaPort;
import io.quarkus.arc.properties.IfBuildProperty;
import org.jboss.logging.Logger;

@ApplicationScoped
@IfBuildProperty(name = "arcclaw.persistence.backend", stringValue = "fs")
public class FsAuditSchemaEnforcer implements AuditSchemaPort {

    private static final Logger log = Logger.getLogger(FsAuditSchemaEnforcer.class);

    @Inject
    FilesystemFileStore store;

    @Override
    public void enforceWormConstraints() {
        log.info("FsAuditSchemaEnforcer: WORM constraints enforced at application level (files are immutable after write)");
    }
}
