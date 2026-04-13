package com.arcclaw.app;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Method;

import jakarta.annotation.Priority;

import com.arcclaw.persistence.sqlite.SqliteWalConfigurer;
import com.arcclaw.persistence.sqlite.RagSchemaInitializer;
import io.quarkus.runtime.StartupEvent;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

@QuarkusTest
public class StartupOrderTest {

    @Test
    void verifyStartupPriorities() throws Exception {
        // Assert SqliteWalConfigurer priority is lower (fires first) than RagSchemaInitializer.
        // This ensures the FTS database is attached before RAG tries to create virtual tables.
        
        Method walMethod = SqliteWalConfigurer.class.getDeclaredMethod("onStartup", StartupEvent.class);
        Method ragMethod = RagSchemaInitializer.class.getDeclaredMethod("onStart", StartupEvent.class);
        
        Priority walPriority = walMethod.getAnnotation(Priority.class);
        Priority ragPriority = ragMethod.getAnnotation(Priority.class);
        
        assertTrue(walPriority != null, "SqliteWalConfigurer.onStartup should have @Priority");
        assertTrue(ragPriority != null, "RagSchemaInitializer.onStart should have @Priority");
        
        assertTrue(walPriority.value() < ragPriority.value(), 
            "SqliteWalConfigurer priority (" + walPriority.value() + ") " +
            "must be smaller than RagSchemaInitializer priority (" + ragPriority.value() + ") " +
            "to fire first.");
    }
}
