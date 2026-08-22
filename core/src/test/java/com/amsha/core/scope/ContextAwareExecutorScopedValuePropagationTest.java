package com.amsha.core.scope;

import static org.junit.jupiter.api.Assertions.*;

import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import com.amsha.security.api.AutonomyLevel;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

public class ContextAwareExecutorScopedValuePropagationTest {

    private ExecutorService baseExecutor;
    private ContextAwareExecutorService contextAwareExecutor;

    @BeforeEach
    void setup() {
        baseExecutor = Executors.newSingleThreadExecutor();
        contextAwareExecutor = new ContextAwareExecutorService(baseExecutor);
    }

    @AfterEach
    void teardown() {
        if (baseExecutor != null) {
            baseExecutor.shutdownNow();
        }
    }

    @Test
    void test_gapExisted_propagatesAllSixFields() throws Exception {
        // Arrange
        String sid = "s1";
        String uid = "u1";
        String cid = "c1";
        AutonomyLevel auto = AutonomyLevel.FULL;
        String role = "coordinator";
        String channel = "TELEGRAM";

        Callable<Boolean> task = () -> {
            // Assert all 6 are non-null and match the caller's values.
            assertEquals(sid, WorkspaceContext.SESSION_ID.get());
            assertEquals(uid, WorkspaceContext.USER_ID.get());
            assertEquals(cid, WorkspaceContext.CHAT_ID.get());
            assertEquals(auto, WorkspaceContext.AUTONOMY.get());
            assertEquals(role, WorkspaceContext.AGENT_ROLE.get());
            assertEquals(channel, WorkspaceContext.channelType().orElse(""));
            return true;
        };

        // Act
        Future<Boolean> future =
                WorkspaceContext.run(sid, uid, cid, auto, role, channel, () -> contextAwareExecutor.submit(task));

        // Assert
        assertTrue(future.get(), "Context was not propagated correctly into virtual thread");
    }

    @Test
    void test_governanceIsEnforced_autonomyPropagated() throws Exception {
        // Arrange
        String sid = "s1";
        String uid = "u1";
        String cid = "c1";
        AutonomyLevel auto = AutonomyLevel.SUPERVISED; // Not default and not FULL
        String role = "researcher";

        Callable<Boolean> task = () -> {
            // Inside the task, assert WorkspaceContext.autonomy() == SUPERVISED
            assertEquals(AutonomyLevel.SUPERVISED, WorkspaceContext.AUTONOMY.get());
            return true;
        };

        // Act
        Future<Boolean> future =
                WorkspaceContext.run(sid, uid, cid, auto, role, () -> contextAwareExecutor.submit(task));

        // Assert
        assertTrue(future.get(), "Autonomy level was not propagated correctly to the executor thread");
    }

    @Test
    void propagatesExplicitActiveIdentityWorkspace() throws Exception {
        Future<String> future = WorkspaceContext.callWithActiveWorkspace(
                "workspace-1",
                () -> WorkspaceContext.run(
                        "s1",
                        "u1",
                        "c1",
                        AutonomyLevel.FULL,
                        "coordinator",
                        () -> contextAwareExecutor.submit(
                                () -> WorkspaceContext.activeWorkspaceId().orElseThrow())));

        assertEquals("workspace-1", future.get());
    }
}
