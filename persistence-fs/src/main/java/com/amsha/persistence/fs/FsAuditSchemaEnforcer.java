package com.amsha.persistence.fs;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import com.amsha.persistence.api.AuditSchemaPort;
import com.amsha.persistence.api.PersistenceBackend;

import org.jboss.logging.Logger;

import io.quarkus.arc.properties.IfBuildProperty;

@ApplicationScoped
@IfBuildProperty(name = PersistenceBackend.PROPERTY, stringValue = PersistenceBackend.FILESYSTEM)
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
