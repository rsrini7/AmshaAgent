package com.amsha.app;

import static org.junit.jupiter.api.Assertions.assertTrue;

import jakarta.annotation.Priority;

import java.lang.reflect.Method;

import com.amsha.app.db.ToolAttemptSchemaGuard;
import com.amsha.app.recovery.StartupAttemptReconciliationObserver;
import com.amsha.persistence.sqlite.RagSchemaInitializer;
import com.amsha.persistence.sqlite.SqliteWalConfigurer;

import org.junit.jupiter.api.Test;

import io.quarkus.runtime.StartupEvent;
import io.quarkus.test.junit.QuarkusTest;

@QuarkusTest
public class StartupOrderTest {

    @Test
    void verifyStartupPriorities() throws Exception {
        // Assert SqliteWalConfigurer priority is lower (fires first) than RagSchemaInitializer.
        // This ensures the FTS database is attached before RAG tries to create virtual tables.

        Method walMethod = SqliteWalConfigurer.class.getDeclaredMethod("onStartup", StartupEvent.class);
        Method guardMethod = ToolAttemptSchemaGuard.class.getDeclaredMethod("onStart", StartupEvent.class);
        Method obsMethod =
                StartupAttemptReconciliationObserver.class.getDeclaredMethod("onStartup", StartupEvent.class);
        Method ragMethod = RagSchemaInitializer.class.getDeclaredMethod("onStart", StartupEvent.class);

        Priority walPriority = walMethod.getAnnotation(Priority.class);
        Priority guardPriority = guardMethod.getAnnotation(Priority.class);
        Priority obsPriority = obsMethod.getParameters()[0].getAnnotation(Priority.class);
        Priority ragPriority = ragMethod.getAnnotation(Priority.class);

        assertTrue(walPriority != null, "SqliteWalConfigurer.onStartup should have @Priority");
        assertTrue(guardPriority != null, "ToolAttemptSchemaGuard.onStart should have @Priority");
        assertTrue(obsPriority != null, "StartupAttemptReconciliationObserver.onStartup should have @Priority");
        assertTrue(ragPriority != null, "RagSchemaInitializer.onStart should have @Priority");

        assertTrue(
                walPriority.value() < guardPriority.value(),
                "SqliteWalConfigurer priority (" + walPriority.value() + ") "
                        + "must be smaller than ToolAttemptSchemaGuard priority ("
                        + guardPriority.value() + ") to configure WAL before schema checks.");

        assertTrue(
                guardPriority.value() < obsPriority.value(),
                "ToolAttemptSchemaGuard priority (" + guardPriority.value() + ") "
                        + "must be smaller than StartupAttemptReconciliationObserver priority ("
                        + obsPriority.value() + ") so schema is verified before attempt reconciliation.");

        assertTrue(
                obsPriority.value() < ragPriority.value(),
                "StartupAttemptReconciliationObserver priority (" + obsPriority.value() + ") "
                        + "must be smaller than RagSchemaInitializer priority ("
                        + ragPriority.value() + ") so attempt reconciliation finishes before user-facing services.");
    }
}
