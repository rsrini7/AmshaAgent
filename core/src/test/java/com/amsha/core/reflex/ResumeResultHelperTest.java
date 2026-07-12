package com.amsha.core.reflex;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import com.amsha.core.agent.loop.CheckpointResumeHandler;
import com.amsha.core.agent.loop.CliResultBridge;
import com.amsha.core.agent.result.AgentResult;

import org.junit.jupiter.api.Test;

class ResumeResultHelperTest {

    @Test
    void synchronousCliResumeCancelsExistingWaiterBeforeResuming() throws Exception {
        CheckpointResumeHandler resumeHandler = mock(CheckpointResumeHandler.class);
        CliResultBridge bridge = mock(CliResultBridge.class);
        ResumeResultHelper helper = new ResumeResultHelper();
        helper.checkpointResumeHandler = resumeHandler;
        helper.cliResultBridge = bridge;
        when(resumeHandler.resumeAfterBatchDecisionSync("session-1", "coordinator", "user", "chat", "cli"))
                .thenReturn(new AgentResult.Finished("done", List.of(), 0, 0));

        String response = helper.runSync("session-1", "coordinator", "user", "chat");

        assertEquals("done", response);
        verify(bridge, times(2)).cancel("session-1");
        verify(bridge, never()).register("session-1");
    }
}
