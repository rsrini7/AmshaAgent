package com.arcclaw.core;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

import jakarta.transaction.Transactional;

import com.arcclaw.core.lifecycle.SessionReaper;
import com.arcclaw.core.state.AgentCheckpoint;
import com.arcclaw.core.state.StatefulAgentStoreImpl;
import org.junit.jupiter.api.Test;

public class TxReflectionTest {

    @Test
    void testA1_SessionReaperGCEntryMethodsAreNotRequired() throws Exception {
        // Find public methods in SessionReaper
        for (Method method : SessionReaper.class.getDeclaredMethods()) {
            if (Modifier.isPublic(method.getModifiers()) && !method.isSynthetic()) {
                Transactional tx = method.getAnnotation(Transactional.class);
                if (tx != null) {
                    assertNotEquals(Transactional.TxType.REQUIRED, tx.value(),
                        "GC entry method " + method.getName() + " must NOT be REQUIRED");
                }
            }
        }
        
        // Assert markApprovalTimedOut is NOT_SUPPORTED
        Method markApprovalTimedOut = SessionReaper.class.getDeclaredMethod("markApprovalTimedOut", 
            AgentCheckpoint.class);
        Transactional tx = markApprovalTimedOut.getAnnotation(Transactional.class);
        assertNotNull(tx, "markApprovalTimedOut must be annotated with @Transactional");
        assertEquals(Transactional.TxType.NOT_SUPPORTED, tx.value(),
            "markApprovalTimedOut must be NOT_SUPPORTED");
    }

    @Test
    void testA2_UpdateBatchToolDecisionIsNotSupported() throws Exception {
        Method updateBatchToolDecision = StatefulAgentStoreImpl.class.getDeclaredMethod("updateBatchToolDecision",
            String.class, String.class, String.class, String.class);
        Transactional tx1 = updateBatchToolDecision.getAnnotation(Transactional.class);
        assertNotNull(tx1, "updateBatchToolDecision must be annotated with @Transactional");
        assertEquals(Transactional.TxType.NOT_SUPPORTED, tx1.value(),
            "updateBatchToolDecision override must be NOT_SUPPORTED");
    }
}
