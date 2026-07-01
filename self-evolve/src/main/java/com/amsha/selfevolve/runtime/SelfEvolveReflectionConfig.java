package com.amsha.selfevolve.runtime;

import com.amsha.selfevolve.api.EvolutionEvent;
import com.amsha.selfevolve.api.EvolutionEventType;
import com.amsha.selfevolve.api.FlushReason;
import com.amsha.selfevolve.store.postgres.PostgresEvolutionEventJdbiDao;
import com.amsha.selfevolve.store.sqlite.EvolutionEventJdbiDao;

import io.quarkus.runtime.annotations.RegisterForReflection;

/**
 * GraalVM native-image reflection registration for self-evolve DTOs and DAOs.
 */
@RegisterForReflection(
        targets = {
            EvolutionEvent.class,
            EvolutionEventType.class,
            FlushReason.class,
            EvolutionEventJdbiDao.class,
            PostgresEvolutionEventJdbiDao.class
        })
public class SelfEvolveReflectionConfig {}
