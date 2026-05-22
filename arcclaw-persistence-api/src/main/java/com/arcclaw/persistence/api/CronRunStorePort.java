package com.arcclaw.persistence.api;

import java.util.List;
import java.util.Optional;

/**
 * Port for persisting and querying cron job execution history.
 */
public interface CronRunStorePort {

    void save(CronRunDto dto);

    Optional<CronRunDto> findById(String id);

    List<CronRunDto> findByCronId(String cronId, int offset, int limit);

    long countByCronId(String cronId);

    List<CronRunDto> findAll(String status, int offset, int limit);

    long countAll(String status);
}
