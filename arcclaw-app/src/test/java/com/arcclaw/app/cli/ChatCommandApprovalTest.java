package com.arcclaw.app.cli;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.PrintStream;
import java.util.List;
import java.util.Optional;

import jakarta.inject.Inject;

import com.arcclaw.core.agent.loop.AgentLoop;
import com.arcclaw.core.agent.reasoning.ReasoningEngine;
import com.arcclaw.core.agent.result.AgentResult;
import com.arcclaw.core.lifecycle.SessionHeartbeatTracker;
import com.arcclaw.core.lifecycle.WorkspaceManager;
import com.arcclaw.core.reflex.ReflexRegistry;
import com.arcclaw.core.reflex.ReflexResult;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

@QuarkusTest
public class ChatCommandApprovalTest {

    @Inject
    ChatCommand chatCommand;

    @InjectMock
    AgentLoop agentLoop;

    @InjectMock
    WorkspaceManager workspaceManager;

    @InjectMock
    SessionHeartbeatTracker heartbeatTracker;

    @InjectMock
    ReflexRegistry reflexRegistry;

    @InjectMock
    ReasoningEngine reasoningEngine;

    private final InputStream systemIn = System.in;
    private final PrintStream systemOut = System.out;
    private ByteArrayOutputStream testOut;

    @BeforeEach
    void setUp() {
        testOut = new ByteArrayOutputStream();
        System.setOut(new PrintStream(testOut));
    }

    @AfterEach
    void tearDown() {
        System.setIn(systemIn);
        System.setOut(systemOut);
    }

    @Test
    void testAwaitingApprovalFlow() {
        // Setup input: first a message that triggers AwaitingApproval, then /exit
        String input = "run sensitive tool\n/exit\n";
        System.setIn(new ByteArrayInputStream(input.getBytes()));

        // Mock ReflexRegistry to return no reflex for "run sensitive tool"
        when(reflexRegistry.processIfReflex(anyString(), anyString(), anyString(), eq("run sensitive tool"), any()))
                .thenReturn(new ReflexResult(Optional.empty(), "run sensitive tool"));

        // Mock AgentLoop to return AwaitingApproval
        String longParams = "{\"key\": \"" + "a".repeat(250) + "\"}";
        AgentResult.AwaitingApproval awaiting = new AgentResult.AwaitingApproval(
                "test-tool", longParams, "session-123", List.of(), 10, 10
        );
        when(agentLoop.run(any(), any(), eq("run sensitive tool"), any(), anyInt(), any()))
                .thenReturn(awaiting);

        // Run the command
        chatCommand.run();

        // Verify heartbeats
        // 1. Session start
        // 2. After "run sensitive tool" input
        // 3. On AwaitingApproval
        verify(heartbeatTracker, atLeastOnce()).recordHeartbeat(anyString());

        // Verify output formatting
        String output = testOut.toString();
        Assertions.assertTrue(output.contains("⏸ Approval needed for tool: test-tool"));
        Assertions.assertTrue(output.contains("Params: {\"key\": \"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"));
        Assertions.assertTrue(output.contains("[truncated]"));
        Assertions.assertTrue(output.contains("Type /approve or /deny"));
    }

    @Test
    void testApproveReflexRouting() {
        // Setup input: /approve
        String input = "/approve\n/exit\n";
        System.setIn(new ByteArrayInputStream(input.getBytes()));

        // Mock ReflexRegistry to handle /approve
        when(reflexRegistry.processIfReflex(anyString(), anyString(), anyString(), eq("/approve"), any()))
                .thenReturn(new ReflexResult(Optional.of("Approved!"), "/approve"));

        // Run the command
        chatCommand.run();

        // Verify ReflexRegistry was called
        verify(reflexRegistry).processIfReflex(anyString(), eq("cli-user"), eq("cli-chat"), eq("/approve"), any());
        
        // Verify output contains reflex response
        String output = testOut.toString();
        Assertions.assertTrue(output.contains("▶ ArcClaw> Approved!"));
    }
}
