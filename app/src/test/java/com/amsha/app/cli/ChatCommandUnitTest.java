package com.amsha.app.cli;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.*;

import jakarta.enterprise.inject.Instance;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.BooleanSupplier;

import com.amsha.channel.api.ChannelType;
import com.amsha.core.agent.loop.CliResultBridge;
import com.amsha.core.agent.result.AgentResult;
import com.amsha.core.agent.role.AgentRoleEnum;
import com.amsha.core.domain.model.AgentCheckpointRecord;
import com.amsha.core.lifecycle.SessionHeartbeatTracker;
import com.amsha.core.lifecycle.WorkspaceManager;
import com.amsha.core.pipeline.TurnDispatchResult;
import com.amsha.core.pipeline.TurnDispatchService;
import com.amsha.core.session.SessionCatalogService;
import com.amsha.core.state.ActiveWork;
import com.amsha.core.state.StatefulAgentStore;
import com.amsha.persistence.api.BootstrapStateDto;
import com.amsha.persistence.api.BootstrapStateStorePort;
import com.amsha.persistence.api.SessionCatalogEntry;
import com.amsha.tool.api.AgentSentinels;

import org.fusesource.jansi.AnsiConsole;
import org.jboss.logging.Logger;
import org.jline.reader.EndOfFileException;
import org.jline.reader.LineReader;
import org.jline.reader.LineReaderBuilder;
import org.jline.reader.UserInterruptException;
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

import io.quarkus.runtime.ShutdownEvent;

/**
 * Plain-JUnit tests for {@link ChatCommand}.
 *
 * <p>JLine3's {@code readLine()} blocks forever in a Surefire fork with no TTY — the reason the sibling
 * {@code ChatCommandApprovalTest} is {@code @Disabled}. Two seams get around that. The loop body is driven
 * through a mocked {@link LineReader} and {@link Terminal}, so the interactive paths run without a real
 * terminal. The entry point itself is driven by {@link #runCliEntryPoint}, which statically substitutes
 * {@link TerminalBuilder} to make terminal creation fail and feeds input as a byte stream — exercising the
 * documented non-TTY fallback, which is what CI and piped invocations actually use.
 *
 * <p>Anything the HITL waiter does happens on its own virtual thread, so every assertion about its side effects
 * polls through {@link #awaitTrue} rather than reading the flag straight after dispatch.
 *
 * <p>Still dark after this, deliberately: {@code chatLoop}'s body past a <em>successful</em> terminal build (the
 * interactive prompt), {@code startProgressTicker}'s tick body (it only fires after a 20-second sleep, which is
 * too slow and too timing-sensitive to buy), and the two {@code catch (InterruptedException)} arms, which no
 * production path interrupts.
 */
class ChatCommandUnitTest {

    private static final String CLI_USER_ID = "cli-user";
    private static final String SESSION = "session-1";

    private final ChatCommand chatCommand = new ChatCommand();

    private final ByteArrayOutputStream capturedOut = new ByteArrayOutputStream();
    private final ByteArrayOutputStream capturedErr = new ByteArrayOutputStream();
    private PrintStream originalOut;
    private PrintStream originalErr;

    @BeforeEach
    void setUp() {
        originalOut = System.out;
        originalErr = System.err;
        System.setOut(new PrintStream(capturedOut, true, StandardCharsets.UTF_8));
        System.setErr(new PrintStream(capturedErr, true, StandardCharsets.UTF_8));
        // A real logger, so nothing has to be stubbed just to keep the debug/info lines honest.
        chatCommand.log = Logger.getLogger(ChatCommand.class);
        chatCommand.workspaceManager = mock(WorkspaceManager.class);
        chatCommand.heartbeatTracker = mock(SessionHeartbeatTracker.class);
        chatCommand.cliResultBridge = mock(CliResultBridge.class);
        chatCommand.turnDispatchService = mock(TurnDispatchService.class);
        chatCommand.sessionCatalogService = mock(SessionCatalogService.class);
    }

    @AfterEach
    void tearDown() {
        System.setOut(originalOut);
        System.setErr(originalErr);
    }

    // ---------- reflection helpers ----------

    private void setField(String name, Object value) throws Exception {
        Field f = ChatCommand.class.getDeclaredField(name);
        f.setAccessible(true);
        f.set(chatCommand, value);
    }

    private Object getField(String name) throws Exception {
        Field f = ChatCommand.class.getDeclaredField(name);
        f.setAccessible(true);
        return f.get(chatCommand);
    }

    private Object invoke(String name, Class<?>[] types, Object... args) throws Exception {
        Method m = ChatCommand.class.getDeclaredMethod(name, types);
        m.setAccessible(true);
        return m.invoke(chatCommand, args);
    }

    private String invokeResolveCliUserId() throws Exception {
        return (String) invoke("resolveCliUserId", new Class<?>[0]);
    }

    private void invokeDispatch(String input) throws Exception {
        invoke("dispatchToWorkflow", new Class<?>[] {String.class}, input);
    }

    private void invokeWaitForHitl(String sessionId, CompletableFuture<AgentResult> future) throws Exception {
        invoke("waitForHitlResult", new Class<?>[] {String.class, CompletableFuture.class}, sessionId, future);
    }

    private void invokePrintAbove(String line) throws Exception {
        invoke("printAbovePrompt", new Class<?>[] {String.class}, line);
        System.out.flush();
    }

    private String stdout() {
        System.out.flush();
        return capturedOut.toString(StandardCharsets.UTF_8);
    }

    /** Polls until {@code condition} holds, so assertions never race the HITL waiter's virtual thread. */
    private static void awaitTrue(BooleanSupplier condition, String description) {
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            try {
                Thread.sleep(10);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        fail("timed out waiting for: " + description);
    }

    private static AgentResult.Finished finished(String answer) {
        return new AgentResult.Finished(answer, List.of(), 10, 10);
    }

    /** The factory is declared on the interface, so the record type is recovered by cast where it is needed. */
    private static AgentResult awaitingApproval() {
        return AgentResult.awaitingApproval("read_file", "{}", SESSION, List.of());
    }

    private static AgentResult.AwaitingApproval awaitingApprovalFrame() {
        return (AgentResult.AwaitingApproval) awaitingApproval();
    }

    // ---------- pre-existing: pure statics ----------

    @Test
    void testCanonicalRole() {
        assertEquals(AgentRoleEnum.COORDINATOR.toScopedName(), ChatCommand.canonicalRole(null));
        assertEquals(AgentRoleEnum.COORDINATOR.toScopedName(), ChatCommand.canonicalRole(""));
        assertEquals(AgentRoleEnum.CODER.toScopedName(), ChatCommand.canonicalRole("coder"));
        assertEquals("custom_role", ChatCommand.canonicalRole("custom_role"));
    }

    @Test
    void testFormatTerminalResult() {
        AgentResult.Finished finished = new AgentResult.Finished("Hello world", List.of(), 10, 10);
        assertEquals("Hello world", ChatCommand.formatTerminalResult(finished));

        AgentResult.StructuredFinished sf =
                new AgentResult.StructuredFinished("Structured answer", "{}", List.of(), 10, 10);
        assertEquals("Structured answer", ChatCommand.formatTerminalResult(sf));

        AgentResult.Failed failed =
                new AgentResult.Failed("Error occurred", AgentResult.FailureCategory.SYSTEM_ERROR, List.of(), 0, 0);
        assertTrue(ChatCommand.formatTerminalResult(failed).contains("Error occurred"));

        AgentResult.LoopExceeded exceeded = new AgentResult.LoopExceeded(5, List.of(), 0, 0);
        assertTrue(ChatCommand.formatTerminalResult(exceeded).contains("5 steps"));

        AgentResult.AwaitingRetry retry = new AgentResult.AwaitingRetry("session-1", 3000, List.of(), 0, 0);
        assertTrue(ChatCommand.formatTerminalResult(retry).contains("3s"));

        AgentResult approval = AgentResult.awaitingApproval("tool", "{}", "session-1", List.of());
        assertNull(ChatCommand.formatTerminalResult(approval));
    }

    @Test
    void testExtractSlashCommands() throws Exception {
        Method extract = ChatCommand.class.getDeclaredMethod("extractSlashCommands");
        extract.setAccessible(true);
        @SuppressWarnings("unchecked")
        List<String> cmds = (List<String>) extract.invoke(chatCommand);
        assertTrue(cmds.contains("/exit"));
        assertTrue(cmds.contains("/approve"));
        assertTrue(cmds.contains("/deny"));
    }

    @Test
    void testDescribePhase() {
        assertEquals("", ChatCommand.describePhase(null));
        assertEquals("", ChatCommand.describePhase("  "));
        assertEquals(" · running read_file", ChatCommand.describePhase("TOOL:read_file"));
        assertEquals(" · running tools", ChatCommand.describePhase("PARALLEL_TOOLS"));
        assertEquals(" · reading tool results", ChatCommand.describePhase("OBSERVING"));
        assertEquals(" · writing the answer", ChatCommand.describePhase("FINISHING"));
        assertEquals(" · thinking", ChatCommand.describePhase("RUNNING"));
    }

    @Test
    void testOnShutdownCleanWhenNoSession() {
        assertDoesNotThrow(() -> chatCommand.onShutdown(null));
    }

    @Test
    void testIsSessionAwaitingApproval() {
        assertFalse(chatCommand.isSessionAwaitingApproval("sess-1"));

        StatefulAgentStore mockStore = Mockito.mock(StatefulAgentStore.class);
        chatCommand.agentStore = mockStore;

        Mockito.when(mockStore.hasPendingApproval("sess-1")).thenReturn(true);
        assertTrue(chatCommand.isSessionAwaitingApproval("sess-1"));

        Mockito.when(mockStore.hasPendingApproval("sess-1")).thenReturn(false);
        Mockito.when(mockStore.findLatestAwaitingApprovalBySessionContaining("sess-1"))
                .thenReturn(Optional.empty());
        assertFalse(chatCommand.isSessionAwaitingApproval("sess-1"));

        Mockito.when(mockStore.findLatestAwaitingApprovalBySessionContaining("sess-1"))
                .thenReturn(Optional.of(new AgentCheckpointRecord()));
        assertTrue(chatCommand.isSessionAwaitingApproval("sess-1"));
    }

    // ---------- resolveCliUserId ----------

    private BootstrapStateStorePort storeReturning(Optional<BootstrapStateDto> state) {
        BootstrapStateStorePort store = mock(BootstrapStateStorePort.class);
        when(store.getState()).thenReturn(state);
        return store;
    }

    @SuppressWarnings("unchecked")
    private Instance<BootstrapStateStorePort> resolvable(BootstrapStateStorePort store) {
        Instance<BootstrapStateStorePort> instance = mock(Instance.class);
        when(instance.isUnsatisfied()).thenReturn(false);
        when(instance.get()).thenReturn(store);
        return instance;
    }

    @SuppressWarnings("unchecked")
    private Instance<BootstrapStateStorePort> unsatisfied() {
        Instance<BootstrapStateStorePort> instance = mock(Instance.class);
        when(instance.isUnsatisfied()).thenReturn(true);
        return instance;
    }

    private static BootstrapStateDto claimedState(String ownerId) {
        return new BootstrapStateDto(
                "boot-1", BootstrapStateDto.STATUS_CLAIMED, ownerId, "ws-1", "digest", null, null, 1, Instant.now());
    }

    @Test
    @DisplayName("with no bootstrap store the CLI falls back to the shared local user")
    void resolveCliUserIdFallsBackWithoutAStore() throws Exception {
        chatCommand.bootstrapStateStores = null;
        assertEquals(CLI_USER_ID, invokeResolveCliUserId());

        chatCommand.bootstrapStateStores = unsatisfied();
        assertEquals(CLI_USER_ID, invokeResolveCliUserId());
    }

    @Test
    @DisplayName("a claimed bootstrap state supplies the CLI user id")
    void resolveCliUserIdUsesTheClaimedOwner() throws Exception {
        chatCommand.bootstrapStateStores = resolvable(storeReturning(Optional.of(claimedState("owner-1"))));

        assertEquals("owner-1", invokeResolveCliUserId());
    }

    @Test
    @DisplayName("unclaimed, owner-less and absent bootstrap states all fall back")
    void resolveCliUserIdFallsBackForUnusableStates() throws Exception {
        BootstrapStateDto unclaimed = new BootstrapStateDto(
                "boot-1",
                BootstrapStateDto.STATUS_UNCLAIMED,
                "owner-1",
                "ws-1",
                "digest",
                null,
                null,
                1,
                Instant.now());
        chatCommand.bootstrapStateStores = resolvable(storeReturning(Optional.of(unclaimed)));
        assertEquals(CLI_USER_ID, invokeResolveCliUserId());

        chatCommand.bootstrapStateStores = resolvable(storeReturning(Optional.of(claimedState(null))));
        assertEquals(CLI_USER_ID, invokeResolveCliUserId());

        chatCommand.bootstrapStateStores = resolvable(storeReturning(Optional.of(claimedState("   "))));
        assertEquals(CLI_USER_ID, invokeResolveCliUserId());

        chatCommand.bootstrapStateStores = resolvable(storeReturning(Optional.empty()));
        assertEquals(CLI_USER_ID, invokeResolveCliUserId());
    }

    @Test
    @DisplayName("a bootstrap store that throws degrades to the shared local user rather than failing")
    void resolveCliUserIdFallsBackWhenTheStoreThrows() throws Exception {
        BootstrapStateStorePort store = mock(BootstrapStateStorePort.class);
        when(store.getState()).thenThrow(new IllegalStateException("store unavailable"));
        chatCommand.bootstrapStateStores = resolvable(store);

        assertEquals(CLI_USER_ID, invokeResolveCliUserId());
    }

    // ---------- onShutdown ----------

    @Test
    @DisplayName("shutdown cancels the bridge and terminates the active session")
    void shutdownTerminatesTheActiveSession() throws Exception {
        setField("currentCliSessionId", SESSION);

        chatCommand.onShutdown(new ShutdownEvent());

        verify(chatCommand.cliResultBridge).cancel(SESSION);
        verify(chatCommand.workspaceManager).terminateSession(SESSION);
        assertNull(getField("currentCliSessionId"));
    }

    @Test
    @DisplayName("a cleanup failure during shutdown is swallowed")
    void shutdownSwallowsCleanupFailures() throws Exception {
        setField("currentCliSessionId", SESSION);
        doThrow(new IllegalStateException("already gone"))
                .when(chatCommand.workspaceManager)
                .terminateSession(SESSION);

        assertDoesNotThrow(() -> chatCommand.onShutdown(new ShutdownEvent()));
    }

    // ---------- chatLoopInner ----------

    private Terminal terminalWritingTo(StringWriter sink) {
        Terminal terminal = mock(Terminal.class);
        when(terminal.writer()).thenReturn(new PrintWriter(sink, true));
        return terminal;
    }

    private void invokeChatLoop(LineReader reader, StringWriter sink) throws Exception {
        invoke("chatLoopInner", new Class<?>[] {LineReader.class, Terminal.class}, reader, terminalWritingTo(sink));
    }

    private void prepareForDispatch() throws Exception {
        setField("currentCliSessionId", SESSION);
        setField("currentCliUserId", CLI_USER_ID);
        chatCommand.maxIterations = 3;
        when(chatCommand.turnDispatchService.dispatch(any()))
                .thenReturn(
                        new TurnDispatchResult(Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty()));
    }

    @Test
    @DisplayName("the loop exits on /exit and says goodbye")
    void chatLoopInnerExitsOnTheExitCommand() throws Exception {
        LineReader reader = mock(LineReader.class);
        when(reader.readLine(anyString())).thenReturn("/exit");
        StringWriter sink = new StringWriter();

        invokeChatLoop(reader, sink);

        assertTrue(sink.toString().contains("Goodbye."), sink.toString());
        verifyNoInteractions(chatCommand.turnDispatchService);
    }

    @Test
    @DisplayName("a null read means end of stream and ends the loop")
    void chatLoopInnerExitsOnANullRead() throws Exception {
        LineReader reader = mock(LineReader.class);
        when(reader.readLine(anyString())).thenReturn(null);

        invokeChatLoop(reader, new StringWriter());

        verifyNoInteractions(chatCommand.turnDispatchService);
    }

    @Test
    @DisplayName("blank input is skipped and end-of-file ends the loop")
    void chatLoopInnerSkipsBlankInputThenEndsOnEof() throws Exception {
        LineReader reader = mock(LineReader.class);
        when(reader.readLine(anyString())).thenReturn("   ").thenThrow(new EndOfFileException());

        invokeChatLoop(reader, new StringWriter());

        verifyNoInteractions(chatCommand.turnDispatchService);
    }

    @Test
    @DisplayName("Ctrl-C is ignored and the loop keeps reading")
    void chatLoopInnerContinuesAfterAUserInterrupt() throws Exception {
        LineReader reader = mock(LineReader.class);
        when(reader.readLine(anyString()))
                .thenThrow(new UserInterruptException("ctrl-c"))
                .thenReturn("/exit");

        invokeChatLoop(reader, new StringWriter());

        verifyNoInteractions(chatCommand.turnDispatchService);
    }

    @Test
    @DisplayName("new input is refused while an approval is pending, but /approve is allowed through")
    void chatLoopInnerGuardsInputWhileApprovalIsPending() throws Exception {
        prepareForDispatch();
        setField("hitlPending", true);
        LineReader reader = mock(LineReader.class);
        when(reader.readLine(anyString()))
                .thenReturn("/status") // blocked: approval pending
                .thenReturn("/approve") // allowed: it is the way out
                .thenThrow(new EndOfFileException());
        StringWriter sink = new StringWriter();

        invokeChatLoop(reader, sink);

        assertTrue(sink.toString().contains("Approval pending"), sink.toString());
        verify(chatCommand.turnDispatchService, times(1)).dispatch(any());
    }

    // ---------- dispatchToWorkflow ----------

    private void stubDispatch(TurnDispatchResult result) {
        when(chatCommand.turnDispatchService.dispatch(any())).thenReturn(result);
    }

    private static TurnDispatchResult resultWith(Optional<AgentResult> agentResult) {
        return new TurnDispatchResult(Optional.empty(), Optional.empty(), agentResult, Optional.empty());
    }

    private static TurnDispatchResult resultWithCommandResponse(String response) {
        return new TurnDispatchResult(Optional.empty(), Optional.empty(), Optional.empty(), Optional.of(response));
    }

    @Test
    @DisplayName("a plain successful turn dispatches and leaves no approval pending")
    void dispatchToWorkflowHandlesAPlainTurn() throws Exception {
        prepareForDispatch();

        invokeDispatch("hello");

        verify(chatCommand.turnDispatchService).dispatch(any());
        assertFalse((boolean) getField("hitlPending"));
        verify(chatCommand.cliResultBridge, never()).markAwaitingApproval(anyString());
    }

    @Test
    @DisplayName("an AwaitingApproval result marks the bridge and registers a waiter")
    void dispatchToWorkflowRegistersWaiterOnApproval() throws Exception {
        prepareForDispatch();
        stubDispatch(resultWith(Optional.of(awaitingApproval())));
        when(chatCommand.cliResultBridge.register(SESSION))
                .thenReturn(CompletableFuture.completedFuture(finished("done")));

        invokeDispatch("do something sensitive");

        verify(chatCommand.cliResultBridge).markAwaitingApproval(SESSION);
        verify(chatCommand.cliResultBridge).register(SESSION);
        // The waiter owns the flag from here: it clears it once the result lands.
        awaitTrue(() -> stdout().contains("▶ Amsha Agent> done"), "the registered waiter to print its result");
    }

    @Test
    @DisplayName("a command response that prompts for approval also counts as pending")
    void dispatchToWorkflowTreatsAnApprovalPromptAsPending() throws Exception {
        prepareForDispatch();
        stubDispatch(resultWithCommandResponse("⏸ Approval needed for tool: read_file"));
        when(chatCommand.cliResultBridge.register(SESSION))
                .thenReturn(CompletableFuture.completedFuture(finished("done")));

        invokeDispatch("do something sensitive");

        verify(chatCommand.cliResultBridge).markAwaitingApproval(SESSION);
        verify(chatCommand.cliResultBridge).register(SESSION);
        awaitTrue(() -> stdout().contains("▶ Amsha Agent> done"), "the registered waiter to print its result");
    }

    @Test
    @DisplayName("a parallel-batch prompt also counts as pending")
    void dispatchToWorkflowTreatsAParallelBatchPromptAsPending() throws Exception {
        prepareForDispatch();
        stubDispatch(resultWithCommandResponse("⏸ Parallel batch started"));
        when(chatCommand.cliResultBridge.register(SESSION))
                .thenReturn(CompletableFuture.completedFuture(finished("done")));

        invokeDispatch("batch it");

        verify(chatCommand.cliResultBridge).markAwaitingApproval(SESSION);
        awaitTrue(() -> stdout().contains("▶ Amsha Agent> done"), "the registered waiter to print its result");
    }

    @Test
    @DisplayName("a turn that resolves a different session switches the active session")
    void dispatchToWorkflowFollowsASessionRotation() throws Exception {
        prepareForDispatch();
        stubDispatch(
                new TurnDispatchResult(Optional.of("session-2"), Optional.empty(), Optional.empty(), Optional.empty()));

        invokeDispatch("rotate");

        assertEquals("session-2", getField("currentCliSessionId"));
        verify(chatCommand.heartbeatTracker).recordHeartbeat("session-2");
        assertTrue(stdout().contains("now active: session-2"), stdout());
    }

    @Test
    @DisplayName("a dispatch failure is reported and does not propagate")
    void dispatchToWorkflowSwallowsDispatchFailures() throws Exception {
        prepareForDispatch();
        when(chatCommand.turnDispatchService.dispatch(any())).thenThrow(new IllegalStateException("boom"));

        assertDoesNotThrow(() -> invokeDispatch("explode"));

        assertTrue(stdout().contains("Thinking"), stdout());
        assertTrue(capturedErr.toString(StandardCharsets.UTF_8).contains("boom"));
    }

    @Test
    @DisplayName("an approval that arrives while one is already pending does not register a second waiter")
    void dispatchToWorkflowDoesNotDoubleRegisterWaiter() throws Exception {
        prepareForDispatch();
        setField("hitlPending", true);
        stubDispatch(resultWith(Optional.of(awaitingApproval())));

        invokeDispatch("second pause");

        verify(chatCommand.cliResultBridge).markAwaitingApproval(SESSION);
        verify(chatCommand.cliResultBridge, never()).register(anyString());
    }

    // ---------- waitForHitlResult ----------

    @Test
    @DisplayName("the waiter prints the final answer and clears the pending flag")
    void hitlWaiterPrintsTheFinalAnswer() throws Exception {
        setField("hitlPending", true);

        invokeWaitForHitl(SESSION, CompletableFuture.completedFuture(finished("the answer")));

        awaitTrue(() -> stdout().contains("▶ Amsha Agent> the answer"), "the waiter to print the answer");
        awaitTrue(() -> !isHitlPending(), "the pending flag to clear");
    }

    @Test
    @DisplayName("a repeated AwaitingApproval re-prompts and waits on the next future")
    void hitlWaiterRepromptsOnARepeatedApproval() throws Exception {
        setField("hitlPending", true);
        when(chatCommand.cliResultBridge.register(SESSION))
                .thenReturn(CompletableFuture.completedFuture(finished("second answer")));

        invokeWaitForHitl(SESSION, CompletableFuture.completedFuture(awaitingApproval()));

        awaitTrue(() -> stdout().contains("Approval needed for tool: read_file"), "the waiter to re-prompt");
        awaitTrue(() -> stdout().contains("▶ Amsha Agent> second answer"), "the waiter to print the second answer");
        verify(chatCommand.cliResultBridge, atLeastOnce()).markAwaitingApproval(SESSION);
    }

    @Test
    @DisplayName("a cancelled waiter stops quietly and clears the pending flag")
    void hitlWaiterStopsOnCancellation() throws Exception {
        setField("hitlPending", true);
        CompletableFuture<AgentResult> cancelled = new CompletableFuture<>();
        cancelled.cancel(true);

        invokeWaitForHitl(SESSION, cancelled);

        awaitTrue(() -> !isHitlPending(), "the pending flag to clear");
        assertFalse(stdout().contains("▶ Amsha Agent>"));
    }

    @Test
    @DisplayName("a waiter that times out stops and clears the pending flag")
    void hitlWaiterStopsOnTimeout() throws Exception {
        setField("hitlPending", true);
        chatCommand.hitlTimeoutHours = 0; // a zero-hour budget means get() does not wait at all

        invokeWaitForHitl(SESSION, new CompletableFuture<>());

        awaitTrue(() -> !isHitlPending(), "the pending flag to clear");
    }

    @Test
    @DisplayName("an unexpected waiter failure is logged and clears the pending flag")
    void hitlWaiterStopsOnAnUnexpectedFailure() throws Exception {
        setField("hitlPending", true);
        CompletableFuture<AgentResult> failed = new CompletableFuture<>();
        failed.completeExceptionally(new IllegalStateException("bridge exploded"));

        invokeWaitForHitl(SESSION, failed);

        awaitTrue(() -> !isHitlPending(), "the pending flag to clear");
    }

    @Test
    @DisplayName("the waiter ignores a null result without printing anything")
    void hitlWaiterIgnoresANullResult() throws Exception {
        setField("hitlPending", true);
        CompletableFuture<AgentResult> nullResult = new CompletableFuture<>();
        nullResult.complete(null);

        invokeWaitForHitl(SESSION, nullResult);

        awaitTrue(() -> !isHitlPending(), "the pending flag to clear");
        assertFalse(stdout().contains("▶ Amsha Agent>"));
    }

    @Test
    @DisplayName("a result that renders to nothing prints nothing")
    void hitlWaiterPrintsNothingForAnUnrenderableResult() throws Exception {
        setField("hitlPending", true);

        // Finished(null) renders to a null string, which the output guard suppresses.
        invokeWaitForHitl(SESSION, CompletableFuture.completedFuture(new AgentResult.Finished(null, List.of(), 0, 0)));

        awaitTrue(() -> !isHitlPending(), "the pending flag to clear");
        assertFalse(stdout().contains("▶ Amsha Agent>"));
    }

    private boolean isHitlPending() {
        try {
            return (boolean) getField("hitlPending");
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    // ---------- printAbovePrompt / describeSessionPhase ----------

    @Test
    @DisplayName("printAbovePrompt prefers the JLine reader and falls back to stdout")
    void printAbovePromptUsesTheReaderWhenPresent() throws Exception {
        invokePrintAbove("first line");
        assertTrue(stdout().contains("first line"), stdout());

        capturedOut.reset();
        LineReader reader = mock(LineReader.class);
        setField("lineReader", reader);

        invokePrintAbove("second line");

        verify(reader).printAbove("second line");
        assertFalse(stdout().contains("second line"), stdout());
    }

    @Test
    @DisplayName("describeSessionPhase is empty without a store and maps the active phase otherwise")
    void describeSessionPhaseMapsTheActiveWork() {
        chatCommand.agentStore = null;
        assertEquals("", chatCommand.describeSessionPhase(SESSION));

        StatefulAgentStore store = mock(StatefulAgentStore.class);
        chatCommand.agentStore = store;
        when(store.getActiveWork(SESSION)).thenReturn(Optional.empty());
        assertEquals("", chatCommand.describeSessionPhase(SESSION));

        when(store.getActiveWork(SESSION)).thenReturn(Optional.of(ActiveWork.of("goal", "TOOL:read_file")));
        assertEquals(" · running read_file", chatCommand.describeSessionPhase(SESSION));
    }

    @Test
    @DisplayName("formatApprovalPrompt renders the tool and its parameters")
    void formatApprovalPromptRendersTheTool() throws Exception {
        String prompt = (String) invoke(
                "formatApprovalPrompt", new Class<?>[] {AgentResult.AwaitingApproval.class}, awaitingApprovalFrame());

        assertTrue(prompt.contains("Approval needed for tool: read_file"), prompt);
        assertTrue(prompt.contains("/approve or /deny"), prompt);
    }

    // ---------- run() / chatLoop: the terminal is the only seam ----------

    private static final String STDIN_EXIT = "/exit\n";

    private static SessionCatalogEntry catalogEntry(String sessionId) {
        Instant now = Instant.now();
        return new SessionCatalogEntry(
                "cat-1",
                CLI_USER_ID,
                ChannelType.CLI.id(),
                "cli-chat",
                sessionId,
                null,
                null,
                now,
                now,
                1,
                true,
                "ACTIVE");
    }

    /**
     * Drives the real entry point by substituting the two pieces of third-party terminal plumbing a Surefire
     * fork cannot supply.
     *
     * <p>{@link TerminalBuilder} is statically mocked to fail, which is precisely the environment the CLI is
     * built to survive — {@code chatLoop} catches the failure and drops to {@link CliInputSupport#runFallback}.
     * Input then arrives as a byte stream, so no real terminal and no real stdin are ever touched and nothing
     * can block.
     *
     * <p>{@link AnsiConsole} is neutralised because {@code systemInstall()} grabs the <em>native</em> file
     * descriptor, so its output bypasses {@code System.setOut} entirely and Surefire reports it as a corrupted
     * channel. Nothing about Jansi is under test, so stubbing it keeps the assertions on the captured stream
     * honest.
     *
     * <p>{@code runFallback} reads {@code System.console()} when one exists; a real console would block on the
     * operator, so the whole scenario is skipped rather than risked when one is attached.
     */
    private void runCliEntryPoint(Executable body) throws Throwable {
        Assumptions.assumeTrue(
                System.console() == null, "a real console is attached; the fallback reader would block on it");
        TerminalBuilder builder = mock(TerminalBuilder.class);
        when(builder.system(true)).thenReturn(builder);
        when(builder.build()).thenThrow(new IOException("no terminal in a forked JVM"));

        InputStream originalIn = System.in;
        System.setIn(new ByteArrayInputStream(STDIN_EXIT.getBytes(StandardCharsets.UTF_8)));
        try (MockedStatic<TerminalBuilder> terminalBuilder = mockStatic(TerminalBuilder.class);
                MockedStatic<AnsiConsole> ansiConsole = mockStatic(AnsiConsole.class)) {
            terminalBuilder.when(TerminalBuilder::builder).thenReturn(builder);
            body.execute();
        } finally {
            System.setIn(originalIn);
        }
    }

    @Test
    @DisplayName("when JLine cannot open a terminal the loop falls back to raw stdin")
    void chatLoopFallsBackWhenTheTerminalCannotBeOpened() throws Throwable {
        runCliEntryPoint(() -> invoke("chatLoop", new Class<?>[0]));

        assertTrue(stdout().contains("Goodbye."), stdout());
        verifyNoInteractions(chatCommand.turnDispatchService);
    }

    @Test
    @DisplayName("run() mints a session, prints the banner and terminates it once the loop ends")
    void runCreatesASessionAndTerminatesIt() throws Throwable {
        when(chatCommand.sessionCatalogService.registerOrResumeSession(
                        anyString(), anyString(), anyString(), anyString()))
                .thenReturn(SESSION);

        runCliEntryPoint(() -> chatCommand.run());

        assertTrue(stdout().contains("Amsha Agent Interactive Terminal"), stdout());
        assertTrue(stdout().contains("Session : " + SESSION), stdout());
        assertTrue(stdout().contains("Role    : auto"), stdout());
        assertTrue(stdout().contains("Goodbye."), stdout());
        assertTrue(stdout().contains("[Terminated]"), stdout());
        verify(chatCommand.sessionCatalogService)
                .registerOrResumeSession(
                        startsWith(AgentSentinels.CLI_SESSION_PREFIX),
                        eq(CLI_USER_ID),
                        eq(ChannelType.CLI.id()),
                        eq("cli-chat"));
        verify(chatCommand.heartbeatTracker).recordHeartbeat(SESSION);
        verify(chatCommand.workspaceManager).terminateSession(SESSION);
    }

    @Test
    @DisplayName("--session-id resumes exactly the requested session, trimmed")
    void runResumesTheRequestedSession() throws Throwable {
        chatCommand.requestedSessionId = "  sess-9  ";
        when(chatCommand.sessionCatalogService.registerOrResumeSession(
                        "sess-9", CLI_USER_ID, ChannelType.CLI.id(), "cli-chat"))
                .thenReturn("sess-9");

        runCliEntryPoint(() -> chatCommand.run());

        assertTrue(stdout().contains("Session : sess-9"), stdout());
    }

    @Test
    @DisplayName("--new-session always mints a fresh session")
    void runCreatesAFreshSessionOnDemand() throws Throwable {
        chatCommand.newSession = true;
        when(chatCommand.sessionCatalogService.createSession(CLI_USER_ID, ChannelType.CLI.id(), "cli-chat"))
                .thenReturn("fresh-1");

        runCliEntryPoint(() -> chatCommand.run());

        assertTrue(stdout().contains("Session : fresh-1"), stdout());
    }

    @Test
    @DisplayName("--last-session resumes the newest catalogued session, and mints one when the catalog is empty")
    void runResumesTheLastSessionOrFallsBackToANewOne() throws Throwable {
        chatCommand.lastSession = true;
        when(chatCommand.sessionCatalogService.latestResumableScoped(CLI_USER_ID, ChannelType.CLI.id(), "cli-chat"))
                .thenReturn(Optional.of(catalogEntry("sess-7")));
        when(chatCommand.sessionCatalogService.resume("sess-7", CLI_USER_ID, ChannelType.CLI.id(), "cli-chat"))
                .thenReturn("sess-7");

        runCliEntryPoint(() -> chatCommand.run());
        assertTrue(stdout().contains("Session : sess-7"), stdout());

        reset(chatCommand.sessionCatalogService);
        when(chatCommand.sessionCatalogService.latestResumableScoped(anyString(), anyString(), anyString()))
                .thenReturn(Optional.empty());
        when(chatCommand.sessionCatalogService.createSession(CLI_USER_ID, ChannelType.CLI.id(), "cli-chat"))
                .thenReturn("sess-new");
        capturedOut.reset();

        runCliEntryPoint(() -> chatCommand.run());

        assertTrue(stdout().contains("Session : sess-new"), stdout());
    }

    @Test
    @DisplayName("a fatal error escaping the loop is reported and still terminates the session")
    void runReportsAFatalErrorAndStillCleansUp() throws Throwable {
        when(chatCommand.sessionCatalogService.registerOrResumeSession(
                        anyString(), anyString(), anyString(), anyString()))
                .thenReturn(SESSION);
        // recordHeartbeat runs outside chatLoop's own guard, so it is the one throw that reaches run()'s catch.
        doThrow(new IllegalStateException("heartbeat store down"))
                .when(chatCommand.heartbeatTracker)
                .recordHeartbeat(SESSION);

        assertDoesNotThrow(() -> runCliEntryPoint(() -> chatCommand.run()));

        assertTrue(capturedErr.toString(StandardCharsets.UTF_8).contains("Fatal"), capturedErr.toString());
        assertTrue(stdout().contains("[Terminated]"), stdout());
        verify(chatCommand.workspaceManager).terminateSession(SESSION);
    }

    @Test
    @DisplayName("chatLoop builds the JLine reader, runs the loop and clears the printer afterwards")
    void chatLoopRunsTheInteractivePathWhenATerminalIsAvailable() throws Throwable {
        StringWriter sink = new StringWriter();
        Terminal terminal = terminalWritingTo(sink);
        LineReader reader = mock(LineReader.class);
        when(reader.readLine(anyString())).thenReturn("/exit");

        TerminalBuilder terminalBuilder = mock(TerminalBuilder.class);
        when(terminalBuilder.system(true)).thenReturn(terminalBuilder);
        when(terminalBuilder.build()).thenReturn(terminal);

        LineReaderBuilder readerBuilder = mock(LineReaderBuilder.class);
        when(readerBuilder.terminal(any())).thenReturn(readerBuilder);
        when(readerBuilder.completer(any())).thenReturn(readerBuilder);
        when(readerBuilder.history(any())).thenReturn(readerBuilder);
        when(readerBuilder.variable(anyString(), any())).thenReturn(readerBuilder);
        when(readerBuilder.build()).thenReturn(reader);

        try (MockedStatic<TerminalBuilder> terminals = mockStatic(TerminalBuilder.class);
                MockedStatic<LineReaderBuilder> readers = mockStatic(LineReaderBuilder.class)) {
            terminals.when(TerminalBuilder::builder).thenReturn(terminalBuilder);
            readers.when(LineReaderBuilder::builder).thenReturn(readerBuilder);
            invoke("chatLoop", new Class<?>[0]);
        }

        assertTrue(sink.toString().contains("Goodbye."), sink.toString());
        assertNull(getField("lineReader"));
    }

    // ---------- chatLoopFallback -> CliInputSupport.runFallback ----------

    private void invokeFallbackWithStdin(InputStream stdin) throws Exception {
        Assumptions.assumeTrue(
                System.console() == null, "a real console is attached; the fallback reader would block on it");
        InputStream originalIn = System.in;
        System.setIn(stdin);
        try {
            invoke("chatLoopFallback", new Class<?>[0]);
        } finally {
            System.setIn(originalIn);
        }
    }

    private void invokeFallbackWithStdin(String stdin) throws Exception {
        invokeFallbackWithStdin(new ByteArrayInputStream(stdin.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    @DisplayName("the fallback loop skips blank lines, dispatches the rest and ends on /exit")
    void fallbackLoopSkipsBlankInputAndDispatches() throws Exception {
        prepareForDispatch();

        invokeFallbackWithStdin("   \nhello\n/exit\n");

        verify(chatCommand.turnDispatchService).dispatch(any());
        assertTrue(stdout().contains("Goodbye."), stdout());
    }

    @Test
    @DisplayName("the fallback loop refuses new input while an approval is pending")
    void fallbackLoopRefusesInputWhileApprovalIsPending() throws Exception {
        setField("hitlPending", true);

        invokeFallbackWithStdin("/status\n/exit\n");

        assertTrue(stdout().contains("Approval pending"), stdout());
        verifyNoInteractions(chatCommand.turnDispatchService);
    }

    @Test
    @DisplayName("a stdin failure ends the fallback loop instead of propagating")
    void fallbackLoopEndsWhenStdinFails() throws Exception {
        InputStream failingStdin = new InputStream() {
            @Override
            public int read() throws IOException {
                throw new IOException("stdin went away");
            }
        };

        assertDoesNotThrow(() -> invokeFallbackWithStdin(failingStdin));
    }
}
