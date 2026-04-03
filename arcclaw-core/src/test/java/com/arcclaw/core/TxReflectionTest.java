package com.arcclaw.core;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

import jakarta.transaction.Transactional;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

import com.arcclaw.core.lifecycle.SessionReaper;
import com.arcclaw.core.state.StatefulAgentStoreImpl;

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
            com.arcclaw.core.state.AgentCheckpoint.class);
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
            
        Method doUpdateBatchToolDecision = StatefulAgentStoreImpl.class.getDeclaredMethod("doUpdateBatchToolDecision",
            String.class, String.class, String.class, String.class);
        Transactional tx2 = doUpdateBatchToolDecision.getAnnotation(Transactional.class);
        assertNotNull(tx2, "doUpdateBatchToolDecision must be annotated with @Transactional");
        assertEquals(Transactional.TxType.REQUIRES_NEW, tx2.value(),
            "doUpdateBatchToolDecision must be REQUIRES_NEW");
    }
}
