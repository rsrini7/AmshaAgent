package com.amsha.persistence.fs;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import com.amsha.persistence.api.PersistenceAction;
import com.amsha.persistence.api.PersistenceAdapterPort;
import com.amsha.persistence.api.PersistenceBackend;

import io.quarkus.arc.Unremovable;
import io.quarkus.arc.properties.IfBuildProperty;

@ApplicationScoped
@Unremovable
@IfBuildProperty(name = PersistenceBackend.PROPERTY, stringValue = PersistenceBackend.FILESYSTEM)
public class FsPersistenceAdapter implements PersistenceAdapterPort {

    @Inject
    FilesystemFileStore store;

    @Inject
    FsLockManager lockManager;

    @Override
    public void execute(String sessionId, String operationName, PersistenceAction action) {
        String lockKey = "persist-" + sessionId;
        boolean locked = lockManager.tryLock(lockKey, 30);
        try {
            action.execute();
        } finally {
            if (locked) {
                lockManager.unlock(lockKey);
            }
        }
    }
}
