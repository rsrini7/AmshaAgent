package com.amsha.persistence.postgres;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;

import org.jboss.logging.Logger;
import org.jdbi.v3.core.Handle;
import org.jdbi.v3.core.HandleConsumer;
import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.quarkus.runtime.StartupEvent;

/**
 * Unit coverage for {@link PostgresAuditSchemaEnforcer}'s startup observer and its two failure arms.
 *
 * <p>Why a separate class: the WORM DDL only runs against a live, migrated PostgreSQL, so no integration
 * test can reach either {@code catch} block — the one that swallows a failed trigger installation and the
 * one that treats "not PostgreSQL yet" as a normal skip. Both exist to keep application startup alive, so
 * leaving them dark means the property they protect is unverified.
 *
 * <p>The happy path is asserted on the emitted SQL rather than on a return value: the enforcement only
 * works if the trigger <em>function</em> is created before the two triggers that reference it.
 */
class PostgresAuditSchemaEnforcerTest {

    private Jdbi jdbi;
    private Logger log;
    private PostgresAuditSchemaEnforcer enforcer;

    @BeforeEach
    void setUp() {
        jdbi = mock(Jdbi.class);
        log = mock(Logger.class);
        enforcer = new PostgresAuditSchemaEnforcer(jdbi, log);
    }

    @Test
    @DisplayName("the protected no-arg constructor builds an unconfigured instance")
    void theProtectedNoArgConstructorBuildsAnUnconfiguredInstance() {
        // Same package as the enforcer, so the protected constructor is reachable without reflection.
        assertDoesNotThrow(() -> new PostgresAuditSchemaEnforcer());
    }

    @Test
    @DisplayName("enforceWormConstraints creates the guard function before the two triggers that call it")
    void enforceWormConstraintsInstallsTheFunctionAndBothTriggers() {
        Handle handle = mock(Handle.class);
        List<String> statements = new ArrayList<>();
        doAnswer(invocation -> {
                    HandleConsumer<RuntimeException> consumer = invocation.getArgument(0);
                    consumer.useHandle(handle);
                    return null;
                })
                .when(jdbi)
                .useHandle(any());
        when(handle.execute(anyString())).thenAnswer(invocation -> {
            statements.add(invocation.getArgument(0));
            return 0;
        });

        enforcer.enforceWormConstraints();

        assertEquals(2, statements.size());
        assertTrue(
                statements.get(0).contains("prevent_audit_modification"),
                "the trigger function must exist before any trigger references it");
        assertTrue(statements.get(1).contains("trg_prevent_audit_update"));
        assertTrue(statements.get(1).contains("trg_prevent_audit_delete"));
    }

    @Test
    @DisplayName("a failed trigger installation is logged and swallowed so startup survives")
    void aFailedEnforcementIsLoggedAndSwallowed() {
        doThrow(new IllegalStateException("permission denied")).when(jdbi).useHandle(any());

        assertDoesNotThrow(() -> enforcer.enforceWormConstraints());

        verify(log).error(anyString(), any(Throwable.class));
    }

    @Test
    @DisplayName("a non-PostgreSQL or not-yet-migrated database is skipped rather than fatal")
    void aStartupProbeFailureIsSwallowed() {
        when(jdbi.withHandle(any())).thenThrow(new IllegalStateException("relation \"audit_event\" does not exist"));

        assertDoesNotThrow(() -> enforcer.onStart(new StartupEvent()));
    }
}
