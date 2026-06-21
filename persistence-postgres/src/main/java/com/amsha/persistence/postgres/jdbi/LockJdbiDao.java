package com.amsha.persistence.postgres.jdbi;

import java.time.Instant;

import org.jdbi.v3.sqlobject.customizer.Bind;
import org.jdbi.v3.sqlobject.statement.SqlQuery;
import org.jdbi.v3.sqlobject.statement.SqlUpdate;

public interface LockJdbiDao {

    @SqlUpdate("DELETE FROM advisory_lock WHERE lock_key = :lockKey AND expires_at < :now")
    void deleteExpired(@Bind("lockKey") String lockKey, @Bind("now") Instant now);

    @SqlUpdate(
            "INSERT INTO advisory_lock (lock_key, owner_id, expires_at) VALUES (:lockKey, :ownerId, :expiresAt) ON CONFLICT (lock_key) DO NOTHING")
    int tryInsert(
            @Bind("lockKey") String lockKey, @Bind("ownerId") String ownerId, @Bind("expiresAt") Instant expiresAt);

    @SqlUpdate("DELETE FROM advisory_lock WHERE lock_key = :lockKey AND owner_id = :ownerId")
    void deleteByOwner(@Bind("lockKey") String lockKey, @Bind("ownerId") String ownerId);

    @SqlQuery("SELECT COUNT(*) FROM advisory_lock WHERE lock_key = :lockKey AND expires_at > :now")
    long countActive(@Bind("lockKey") String lockKey, @Bind("now") Instant now);
}
