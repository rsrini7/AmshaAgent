package com.amsha.selfevolve.runtime;

import com.amsha.selfevolve.api.DirectiveBundle;
import com.amsha.selfevolve.api.DirectiveState;
import com.amsha.selfevolve.api.EvolutionEvent;
import com.amsha.selfevolve.api.EvolutionEventType;
import com.amsha.selfevolve.api.ExtractedMemory;
import com.amsha.selfevolve.api.FlushReason;
import com.amsha.selfevolve.api.MemoryRecordType;
import com.amsha.selfevolve.api.SynthesizedDirective;
import com.amsha.selfevolve.store.postgres.PostgresDirectiveJdbiDao;
import com.amsha.selfevolve.store.postgres.PostgresEvolutionEventJdbiDao;
import com.amsha.selfevolve.store.postgres.PostgresExtractedMemoryJdbiDao;
import com.amsha.selfevolve.store.sqlite.DirectiveJdbiDao;
import com.amsha.selfevolve.store.sqlite.EvolutionEventJdbiDao;
import com.amsha.selfevolve.store.sqlite.ExtractedMemoryJdbiDao;

import io.quarkus.runtime.annotations.RegisterForReflection;

/**
 * GraalVM native-image reflection registration for self-evolve DTOs and DAOs.
 */
@RegisterForReflection(
        targets = {
            EvolutionEvent.class,
            EvolutionEventType.class,
            FlushReason.class,
            ExtractedMemory.class,
            MemoryRecordType.class,
            DirectiveState.class,
            SynthesizedDirective.class,
            DirectiveBundle.class,
            EvolutionEventJdbiDao.class,
            ExtractedMemoryJdbiDao.class,
            DirectiveJdbiDao.class,
            PostgresEvolutionEventJdbiDao.class,
            PostgresExtractedMemoryJdbiDao.class,
            PostgresDirectiveJdbiDao.class
        })
public class SelfEvolveReflectionConfig {}
