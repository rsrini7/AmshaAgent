package com.amsha.core.agent.loop;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import com.amsha.core.agent.result.AgentResult;

import org.junit.jupiter.api.Test;

class CliResultBridgeTest {

    @Test
    void timedOutPollerDoesNotStealTheNextApprovalResult() throws Exception {
        CliResultBridge bridge = new CliResultBridge();
        String sessionId = "cli-timeout";
        bridge.markAwaitingApproval(sessionId);

        assertNull(bridge.waitForResult(sessionId, 1));

        CompletableFuture<AgentResult> nextWaiter = bridge.register(sessionId);
        AgentResult result = new AgentResult.Finished("answer", List.of(), 1, 1);
        bridge.complete(sessionId, result);

        assertSame(result, nextWaiter.get(1, TimeUnit.SECONDS));
    }
}
