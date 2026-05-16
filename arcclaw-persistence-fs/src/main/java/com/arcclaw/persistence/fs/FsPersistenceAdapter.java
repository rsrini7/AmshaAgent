package com.arcclaw.persistence.fs;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import com.arcclaw.persistence.api.PersistenceAction;
import com.arcclaw.persistence.api.PersistenceAdapterPort;
import io.quarkus.arc.Unremovable;

@ApplicationScoped
@Unremovable
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
