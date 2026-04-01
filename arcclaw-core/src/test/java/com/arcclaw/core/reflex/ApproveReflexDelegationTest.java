package com.arcclaw.core.reflex;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;

import com.arcclaw.core.agent.loop.CheckpointApprovalService;
import com.arcclaw.core.pipeline.MessageContext;
import com.arcclaw.core.state.AgentCheckpoint;
import com.arcclaw.core.state.StatefulAgentStore;
import org.jboss.logging.Logger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

public class ApproveReflexDelegationTest {

    @Mock CheckpointApprovalService checkpointApprovalService;
    @Mock StatefulAgentStore statefulAgentStore;
    @InjectMocks ApproveReflex approveReflex;

    @BeforeEach
    void setup() {
        MockitoAnnotations.openMocks(this);
        approveReflex.log = Logger.getLogger("test");
    }

    @Test
    void test_bulkApprove_delegatesToCheckpointApprovalService() {
        AgentCheckpoint cp = new AgentCheckpoint();
        cp.setSessionId("s1");
        cp.setAgentRole("coordinator");
        cp.setBatchToolDecisions("{\"github\":\"PENDING\"}");

        when(statefulAgentStore.findLatestAwaitingApproval("s1")).thenReturn(Optional.of(cp));

        MessageContext context = mock(MessageContext.class);

        ReflexResult result = approveReflex.execute("s1", "u1", "c1", "/approve", context);

        verify(checkpointApprovalService).approveAll("s1", "CLI approve");
        assertNotNull(result);
        assertTrue(result.commandResponse().orElse("").contains("Approval processed"));
    }

    @Test
    void test_perToolApprove_delegatesToCheckpointApprovalService() {
        AgentCheckpoint cp = new AgentCheckpoint();
        cp.setSessionId("s1");
        cp.setAgentRole("coordinator");
        cp.setBatchToolDecisions("{\"github\":\"PENDING\"}");

        when(statefulAgentStore.findLatestAwaitingApproval("s1")).thenReturn(Optional.of(cp));

        MessageContext context = mock(MessageContext.class);

        ReflexResult result = approveReflex.execute("s1", "u1", "c1", "/approve github", context);

        verify(checkpointApprovalService).approveTool("s1", "github", "CLI approve");
        assertNotNull(result);
    }
}
