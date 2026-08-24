package com.amsha.persistence.api;

import static java.lang.annotation.ElementType.*;

import jakarta.inject.Qualifier;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * CDI qualifier for tiered memory backend identification.
 * <p>
 * Each {@code TieredMemoryStorePort} implementation must be annotated with
 * {@code @TieredBackend("sqlite")}, {@code @TieredBackend("postgres")}, or
 * {@code @TieredBackend("fs")} so the backend selector can resolve by
 * CDI-qualified selection rather than by class-name substring matching
 * or runtime reflection on bean classes.
 * <p>
 * For programmatic selection use the {@code TieredBackendLiteral} annotation literal
 * (amsha-memory): {@code Instance<TieredMemoryStorePort>.select(new TieredBackendLiteral("sqlite"))}.
 */
@Qualifier
@Retention(RetentionPolicy.RUNTIME)
@Target({TYPE, METHOD, FIELD, PARAMETER})
public @interface TieredBackend {
    /**
     * Backend identifier: sqlite, postgres, or fs.
     */
    String value();
}
