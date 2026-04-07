package com.arcclaw.core.reflex;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;
import java.util.concurrent.Callable;

import com.arcclaw.core.agent.loop.CheckpointApprovalService;
import com.arcclaw.core.pipeline.MessageContext;
import com.arcclaw.core.scope.WorkspaceScopeHolder;
import com.arcclaw.core.state.AgentCheckpoint;
import com.arcclaw.core.state.StatefulAgentStore;
import org.jboss.logging.Logger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.MockitoAnnotations;

public class ApproveReflexDelegationTest {

    @Mock CheckpointApprovalService checkpointApprovalService;
    @Mock StatefulAgentStore statefulAgentStore;
    @InjectMocks ApproveReflex approveReflex;
    
    private MockedStatic<WorkspaceScopeHolder> mockedScope;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setup() {
        MockitoAnnotations.openMocks(this);
        approveReflex.log = Logger.getLogger("test");
        
        mockedScope = mockStatic(WorkspaceScopeHolder.class);
        mockedScope.when(() -> WorkspaceScopeHolder.run(anyString(), any(Runnable.class)))
                .thenAnswer(inv -> { ((Runnable) inv.getArgument(1)).run(); return null; });
        mockedScope.when(() -> WorkspaceScopeHolder.run(anyString(), anyString(), anyString(), any(Runnable.class)))
                .thenAnswer(inv -> { ((Runnable) inv.getArgument(3)).run(); return null; });
        mockedScope.when(() -> WorkspaceScopeHolder.call(anyString(), any(Callable.class)))
                .thenAnswer(inv -> ((Callable<?>) inv.getArgument(1)).call());
        mockedScope.when(() -> WorkspaceScopeHolder.call(anyString(), anyString(), anyString(), any(Callable.class)))
                .thenAnswer(inv -> ((Callable<?>) inv.getArgument(3)).call());
    }

    @AfterEach
    void tearDown() {
        if (mockedScope != null) {
            mockedScope.close();
        }
    }

    @Test
    void test_bulkApprove_delegatesToCheckpointApprovalService() {
        AgentCheckpoint cp = new AgentCheckpoint();
        cp.setSessionId("s1");
        cp.setAgentRole("coordinator");
        cp.setBatchToolDecisions("{\"github\":\"PENDING\"}");

        when(statefulAgentStore.findLatestAwaitingApproval("s1")).thenReturn(Optional.of(cp));

        MessageContext context = mock(MessageContext.class);
        com.arcclaw.channel.api.ChannelUser user = new com.arcclaw.channel.api.ChannelUser("u1", "u1", "u1", com.arcclaw.channel.api.ChannelType.TELEGRAM);
        when(context.getUser()).thenReturn(user);

        ReflexResult result = approveReflex.execute("s1", "u1", "c1", "/approve", context);

        verify(checkpointApprovalService).approveAll("s1", "CLI approve");
        assertNotNull(result);
        assertEquals("", result.commandResponse().orElse(null));
    }

    @Test
    void test_perToolApprove_delegatesToCheckpointApprovalService() {
        AgentCheckpoint cp = new AgentCheckpoint();
        cp.setSessionId("s1");
        cp.setAgentRole("coordinator");
        cp.setBatchToolDecisions("{\"github\":\"PENDING\"}");

        when(statefulAgentStore.findLatestAwaitingApproval("s1")).thenReturn(Optional.of(cp));

        MessageContext context = mock(MessageContext.class);
        com.arcclaw.channel.api.ChannelUser user = new com.arcclaw.channel.api.ChannelUser("u1", "u1", "u1", com.arcclaw.channel.api.ChannelType.TELEGRAM);
        when(context.getUser()).thenReturn(user);

        ReflexResult result = approveReflex.execute("s1", "u1", "c1", "/approve github", context);

        verify(checkpointApprovalService).approveTool("s1", "github", "CLI approve");
        assertNotNull(result);
    }
}
