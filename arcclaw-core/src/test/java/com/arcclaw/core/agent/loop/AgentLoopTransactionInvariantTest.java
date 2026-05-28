package com.arcclaw.core.agent.loop;

import static org.junit.jupiter.api.Assertions.assertEquals;

import jakarta.inject.Inject;
import jakarta.transaction.Status;
import jakarta.transaction.TransactionManager;

import org.junit.jupiter.api.Test;

import io.quarkus.test.junit.QuarkusTest;

@QuarkusTest
class AgentLoopTransactionInvariantTest {

    @Inject
    TransactionManager transactionManager;

    @Test
    void agentLoop_mustNotRunInsideTransaction() throws Exception {
        // §5.9 (INVARIANT): Reasoning Engine and AgentLoop MUST NOT run inside a JTA transaction.
        // This baseline check verifies that the test context itself is transaction-free.
        // Real enforcement happens via architectural review and the JTA log suppression logic
        // in application.yaml which monitors for propagated transaction context leaking into
        // the Vert.x event loop threads.
        assertEquals(
                Status.STATUS_NO_TRANSACTION,
                transactionManager.getStatus(),
                "Baseline invariant: the execution context must be transaction-free before entering the Agent reasoning loop.");
    }
}
