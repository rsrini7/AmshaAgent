package com.amsha.app.cli;

import picocli.CommandLine;

import jakarta.annotation.Priority;
import jakarta.enterprise.context.control.ActivateRequestContext;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import com.amsha.channel.api.ChannelMessage;
import com.amsha.channel.api.ChannelType;
import com.amsha.channel.api.ChannelUser;
import com.amsha.common.util.LogUtil;
import com.amsha.core.agent.loop.CliResultBridge;
import com.amsha.core.agent.result.AgentResult;
import com.amsha.core.agent.role.AgentRoleEnum;
import com.amsha.core.lifecycle.SessionHeartbeatTracker;
import com.amsha.core.lifecycle.WorkspaceManager;
import com.amsha.core.pipeline.TurnDispatchResult;
import com.amsha.core.pipeline.TurnDispatchService;
import com.amsha.core.pipeline.TurnRequest;
import com.amsha.core.scope.WorkspaceContext;
import com.amsha.core.session.SessionCatalogService;
import com.amsha.core.state.ActiveWork;
import com.amsha.core.state.StatefulAgentStore;
import com.amsha.persistence.api.BootstrapStateDto;
import com.amsha.persistence.api.BootstrapStateStorePort;
import com.amsha.security.api.AutonomyLevel;
import com.amsha.tool.api.AgentSentinels;

import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.fusesource.jansi.AnsiConsole;
import org.jboss.logging.Logger;
import org.jline.reader.EndOfFileException;
import org.jline.reader.LineReader;
import org.jline.reader.LineReaderBuilder;
import org.jline.reader.UserInterruptException;
import org.jline.reader.impl.history.DefaultHistory;
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;

import io.quarkus.runtime.ShutdownEvent;

@CommandLine.Command(name = "chat", description = "Start an interactive chat session with Amsha Agent")
@SuppressWarnings("java:S1192")
public class ChatCommand implements Runnable {

    private static final String CLI_USER_ID = "cli-user";
    private static final String CLI_CHAT_ID = "cli-chat";
    private static final String APPROVAL_RESPONSE_MARKER = "⏸ Approval";
    private static final String PARALLEL_BATCH_RESPONSE_MARKER = "⏸ Parallel batch";
    private static final long PROGRESS_TICK_SECONDS = 20;

    @CommandLine.Option(
            names = {"-r", "--role"},
            description = "Explicit agent role. Omit to use shared intent and skill routing.")
    String role;

    @CommandLine.Option(
            names = {"-s", "--session-id"},
            description = "Resume an existing persisted session by ID; generates a new CLI session when omitted.")
    String requestedSessionId;

    @CommandLine.Option(
            names = {"--last-session"},
            description = "Resume the most recently active CLI session from the catalog.")
    boolean lastSession;

    @CommandLine.Option(
            names = {"--new-session"},
            description = "Start a fresh catalogued session (default behaviour).")
    boolean newSession;

    @Inject
    Logger log;

    @Inject
    WorkspaceManager workspaceManager;

    @Inject
    SessionHeartbeatTracker heartbeatTracker;

    @Inject
    CliResultBridge cliResultBridge;

    @Inject
    TurnDispatchService turnDispatchService;

    @Inject
    SessionCatalogService sessionCatalogService;

    @Inject
    StatefulAgentStore agentStore;

    @Inject
    Instance<BootstrapStateStorePort> bootstrapStateStores;

    private volatile String currentCliSessionId;
    private volatile String currentCliUserId = CLI_USER_ID;

    /**
     * Guard flag: set to true when an AwaitingApproval result is received.
     * Replaces the store query in the HITL guard to eliminate the TOCTOU window
     * where the approval could complete between the check and the dispatch.
     */
    private volatile boolean hitlPending = false;

    /** JLine3 line reader - used by the async HITL waiter to preserve the prompt. */
    private volatile LineReader lineReader;

    @ConfigProperty(name = "amsha.agent.max-iterations", defaultValue = "5")
    int maxIterations;

    @ConfigProperty(name = "amsha.hitl.timeout-hours", defaultValue = "24")
    long hitlTimeoutHours;

    @Override
    @ActivateRequestContext
    public void run() {
        String cliUserId = resolveCliUserId();
        currentCliUserId = cliUserId;

        // 1. Resolve initial session — priority: --session-id > --new-session > --last-session > default new
        String sessionId;
        if (requestedSessionId != null && !requestedSessionId.isBlank()) {
            sessionId = sessionCatalogService.registerOrResumeSession(
                    requestedSessionId.trim(), cliUserId, ChannelType.CLI.id(), CLI_CHAT_ID);
        } else if (newSession) {
            sessionId = sessionCatalogService.createSession(cliUserId, ChannelType.CLI.id(), CLI_CHAT_ID);
        } else if (lastSession) {
            sessionId = sessionCatalogService
                    .latestResumableScoped(cliUserId, ChannelType.CLI.id(), CLI_CHAT_ID)
                    .map(entry -> sessionCatalogService.resume(
                            entry.sessionId(), cliUserId, ChannelType.CLI.id(), CLI_CHAT_ID))
                    .orElseGet(() -> sessionCatalogService.createSession(cliUserId, ChannelType.CLI.id(), CLI_CHAT_ID));
        } else {
            sessionId = sessionCatalogService.registerOrResumeSession(
                    AgentSentinels.CLI_SESSION_PREFIX + UUID.randomUUID(),
                    cliUserId,
                    ChannelType.CLI.id(),
                    CLI_CHAT_ID);
        }
        this.currentCliSessionId = sessionId;

        // Install Jansi ANSI support — auto-detects terminal capabilities.
        // Enables ANSI colors in CliReplyRenderer when connected to a terminal;
        // disables them for piped output / CI logs.
        AnsiConsole.systemInstall();

        log.infof("Starting CLI chat session %s with role %s", sessionId, role == null ? "auto" : role);
        System.out.println("✦ Amsha Agent Interactive Terminal");
        System.out.println("   Session : " + sessionId);
        System.out.println("   Role    : " + (role == null ? "auto" : role));
        System.out.println("   Commands: /exit to quit, /reset to start fresh");
        System.out.println("-------------------------------------------------");

        try {
            ScopedValue.where(WorkspaceContext.CHANNEL_TYPE, ChannelType.CLI.id())
                    .run(() -> runChatLoop(sessionId));
        } catch (Exception e) {
            log.errorf("Fatal error in chat session %s:\n%s", sessionId, LogUtil.trimmedStack(e));
            System.err.println("❌ Fatal: " + e.getMessage());
        } finally {
            String terminatingId = this.currentCliSessionId;
            if (terminatingId != null) {
                this.currentCliSessionId = null;
                workspaceManager.terminateSession(terminatingId);
            }
            AnsiConsole.systemUninstall();
        }

        log.infof("CLI chat session %s terminated", sessionId);
        System.out.println("\n[Terminated]");
    }

    private String resolveCliUserId() {
        if (bootstrapStateStores == null || bootstrapStateStores.isUnsatisfied()) {
            return CLI_USER_ID;
        }
        try {
            return bootstrapStateStores
                    .get()
                    .getState()
                    .filter(state -> BootstrapStateDto.STATUS_CLAIMED.equals(state.status()))
                    .map(BootstrapStateDto::ownerId)
                    .filter(ownerId -> ownerId != null && !ownerId.isBlank())
                    .orElse(CLI_USER_ID);
        } catch (RuntimeException exception) {
            log.debugf("Could not resolve claimed local CLI owner: %s", LogUtil.rootMessage(exception));
            return CLI_USER_ID;
        }
    }

    /**
     * Prioritized shutdown observer - cleans up session AND cancels any
     * in-flight HITL virtual thread BEFORE connection pools close.
     */
    @ActivateRequestContext
    void onShutdown(@Observes @Priority(Integer.MIN_VALUE) ShutdownEvent event) {
        String sessionId = this.currentCliSessionId;
        if (sessionId == null) return;
        this.currentCliSessionId = null;

        try {
            log.debugf("CLI ShutdownEvent triggered for session %s (absolute priority cleanup)", sessionId);
            hitlPending = false;
            CliJobNotifier.setTerminalPrinter(null);
            cliResultBridge.cancel(sessionId); // unblocks any HITL virtual thread immediately
            workspaceManager.terminateSession(sessionId);
        } catch (Exception e) {
            log.warnf("Session cleanup on shutdown incomplete for %s: %s", sessionId, LogUtil.rootMessage(e));
        }
    }

    private void runChatLoop(String sessionId) {
        heartbeatTracker.recordHeartbeat(sessionId);
        try {
            ScopedValue.where(WorkspaceContext.CHANNEL_TYPE, ChannelType.CLI.id())
                    .run(() -> chatLoop());
        } catch (Exception e) {
            log.errorf("Error in chat loop:\n%s", LogUtil.trimmedStack(e));
            System.err.println("❌ Chat error: " + e.getMessage());
        }
    }

    private void chatLoop() {
        try (Terminal terminal = TerminalBuilder.builder().system(true).build()) {

            List<String> slashCommands = extractSlashCommands();
            SlashCommandCompleter completer = new SlashCommandCompleter(slashCommands);

            LineReader reader = LineReaderBuilder.builder()
                    .terminal(terminal)
                    .completer(completer)
                    .history(new DefaultHistory())
                    .variable(LineReader.HISTORY_FILE, Path.of(System.getProperty("user.home"), ".amsha_history"))
                    .build();

            this.lineReader = reader;
            CliJobNotifier.setTerminalPrinter(reader::printAbove);
            try {
                chatLoopInner(reader, terminal);
            } finally {
                CliJobNotifier.setTerminalPrinter(null);
            }

        } catch (Exception e) {
            log.warnf("JLine3 terminal init failed (%s), falling back to raw input", e.getMessage());
            chatLoopFallback();
        } finally {
            CliJobNotifier.setTerminalPrinter(null);
            this.lineReader = null;
        }
    }

    private void chatLoopInner(LineReader reader, Terminal terminal) {
        while (true) {
            String input;
            try {
                input = reader.readLine("\nYou> ");
                if (input == null) break;
                input = input.trim();
            } catch (EndOfFileException _) {
                break;
            } catch (UserInterruptException _) {
                continue;
            }

            if (CliInputSupport.isExitCommand(input)) {
                log.infof("CLI chat session %s exiting on user command", currentCliSessionId);
                terminal.writer().println("Goodbye.");
                break;
            }
            if (input.isBlank()) continue;

            if (!CliInputSupport.isApprovalCommand(input)) {
                if (hitlPending) {
                    terminal.writer()
                            .println("\n▶ Amsha Agent> ⏸ Approval pending - type "
                                    + CliInputSupport.APPROVE_COMMAND + " or " + CliInputSupport.DENY_COMMAND
                                    + " before sending new messages.");
                    continue;
                }
            }

            dispatchToWorkflow(input);
        }
    }

    /** Fallback for non-TTY environments (CI, piped stdin, Docker without -t). */
    private void chatLoopFallback() {
        CliInputSupport.runFallback(() -> hitlPending, this::dispatchToWorkflow);
    }

    private List<String> extractSlashCommands() {
        return CliInputSupport.slashCommands();
    }

    private void dispatchToWorkflow(String input) {
        var sessionId = currentCliSessionId;
        String cliUserId = currentCliUserId;
        try {
            log.infof("CLI chat session %s dispatching input: %s", sessionId, input);
            System.out.println("⚡ Thinking...");
            ChannelMessage message = new ChannelMessage.CliMessage(
                    new ChannelUser(cliUserId, CLI_CHAT_ID, "CLI User", ChannelType.CLI),
                    input,
                    sessionId,
                    Instant.now());
            TurnDispatchResult result = WorkspaceContext.run(
                    sessionId,
                    cliUserId,
                    CLI_CHAT_ID,
                    AutonomyLevel.SUPERVISED,
                    canonicalRole(role),
                    ChannelType.CLI.id(),
                    CLI_CHAT_ID,
                    () -> turnDispatchService.dispatch(new TurnRequest(
                            message,
                            CLI_CHAT_ID,
                            Optional.of(sessionId),
                            Optional.ofNullable(role),
                            Optional.of(maxIterations))));
            boolean isApprovalPrompt = result.commandResponse()
                    .map(resp ->
                            resp.contains(APPROVAL_RESPONSE_MARKER) || resp.contains(PARALLEL_BATCH_RESPONSE_MARKER))
                    .orElse(false);
            boolean newHitlPending = result.agentResult()
                    .map(AgentResult.AwaitingApproval.class::isInstance)
                    .orElse(isApprovalPrompt);
            if (newHitlPending) {
                // A synchronous /approve can itself pause on the next call in a
                // parallel batch. The original waiter already owns the bridge
                // future; registering another waiter would print the eventual
                // terminal answer twice.
                boolean startWaiter = !hitlPending;
                hitlPending = true;
                cliResultBridge.markAwaitingApproval(sessionId);
                if (startWaiter) {
                    waitForHitlResult(sessionId, cliResultBridge.register(sessionId));
                }
            } else if (!isSessionAwaitingApproval(sessionId)) {
                hitlPending = false;
            }
            // Session rotation: NewSessionReflex changes the active session on disk;
            // sync the local field so subsequent turns use the new id.
            result.sessionId().ifPresent(id -> {
                if (!id.equals(sessionId)) {
                    currentCliSessionId = id;
                    heartbeatTracker.recordHeartbeat(id);
                    System.out.println("Session " + sessionId + " saved; now active: " + id);
                }
            });

        } catch (Exception e) {
            log.errorf("Agent error in session %s:\n%s", sessionId, LogUtil.trimmedStack(e));
            System.err.println("❌ Error: " + e.getMessage());
        }
    }

    private void waitForHitlResult(String sessionId, CompletableFuture<AgentResult> resultFuture) {
        startProgressTicker(sessionId, resultFuture);
        Thread.ofVirtual().name("hitl-waiter-" + sessionId).start(() -> {
            try {
                CompletableFuture<AgentResult> next = resultFuture;
                while (true) {
                    AgentResult result = next.get(hitlTimeoutHours, TimeUnit.HOURS);
                    if (result == null) return;
                    if (result instanceof AgentResult.AwaitingApproval awaiting) {
                        printAbovePrompt(formatApprovalPrompt(awaiting));
                        hitlPending = true;
                        cliResultBridge.markAwaitingApproval(sessionId);
                        next = cliResultBridge.register(sessionId);
                        continue;
                    }
                    String output = formatTerminalResult(result);
                    if (output != null) printAbovePrompt("\n▶ Amsha Agent> " + output);
                    return;
                }
            } catch (CancellationException e) {
                log.debugf("CLI HITL waiter cancelled for session %s", sessionId);
            } catch (TimeoutException e) {
                log.debugf("CLI HITL waiter timed out for session %s after %dh", sessionId, hitlTimeoutHours);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (Exception e) {
                log.warnf("CLI HITL waiter error for session %s: %s", sessionId, LogUtil.rootMessage(e));
            } finally {
                hitlPending = false;
            }
        });
    }

    private static String formatApprovalPrompt(AgentResult.AwaitingApproval awaiting) {
        return CliInputSupport.formatApprovalPrompt(awaiting);
    }

    /**
     * Emits a periodic progress line while an approved turn executes, so the
     * terminal is not silently frozen between {@code /approve} and the answer.
     * The phase comes from the agent loop's activity heartbeat, which is already
     * refreshed on every reasoning step.
     */
    private void startProgressTicker(String sessionId, CompletableFuture<AgentResult> resultFuture) {
        Thread.ofVirtual().name("cli-progress-" + sessionId).start(() -> {
            long elapsedSeconds = 0;
            try {
                while (!resultFuture.isDone()) {
                    Thread.sleep(Duration.ofSeconds(PROGRESS_TICK_SECONDS));
                    if (resultFuture.isDone()) return;
                    if (WorkspaceManager.isSessionHalted(sessionId)) {
                        // /halt terminated the session but the bridge future was never
                        // completed (result-delivery gap) — stop spinning immediately.
                        return;
                    }
                    if (isSessionAwaitingApproval(sessionId)) {
                        continue;
                    }
                    elapsedSeconds += PROGRESS_TICK_SECONDS;
                    printAbovePrompt("⏳ Still working… " + elapsedSeconds + "s" + describeSessionPhase(sessionId));
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (RuntimeException e) {
                log.debugf("CLI progress ticker stopped for %s: %s", sessionId, LogUtil.rootMessage(e));
            }
        });
    }

    private void printAbovePrompt(String line) {
        LineReader reader = lineReader;
        if (reader != null) {
            reader.printAbove(line);
        } else {
            System.out.println(line);
        }
    }

    /**
     * Renders the coarse activity phase recorded by the agent loop,
     * e.g. {@code " · running read_file"}. Empty when nothing is running or phase is blank.
     */
    static String describePhase(String phase) {
        return CliInputSupport.describePhase(phase);
    }

    String describeSessionPhase(String sessionId) {
        if (agentStore == null) {
            return "";
        }
        return agentStore
                .getActiveWork(sessionId)
                .map(ActiveWork::phase)
                .map(ChatCommand::describePhase)
                .orElse("");
    }

    boolean isSessionAwaitingApproval(String sessionId) {
        if (agentStore == null) return false;
        return agentStore.hasPendingApproval(sessionId)
                || agentStore
                        .findLatestAwaitingApprovalBySessionContaining(sessionId)
                        .isPresent();
    }

    static String formatTerminalResult(AgentResult result) {
        return switch (result) {
            case AgentResult.Finished f -> f.answer();
            case AgentResult.StructuredFinished sf -> sf.answer();
            case AgentResult.Failed f -> "❌ Error: " + f.reason();
            case AgentResult.LoopExceeded l -> "⏱ Timed out after " + l.maxIterations() + " steps.";
            case AgentResult.AwaitingRetry r -> "⏳ Rate limit hit. Retry in " + (r.retryAfterMs() / 1000) + "s...";
            case AgentResult.AwaitingApproval _ -> null;
        };
    }

    /**
     * Canonicalizes the CLI role argument using {@link AgentRoleEnum}:
     * null / blank → COORDINATOR; known built-in → scoped name; unknown → pass-through.
     */
    static String canonicalRole(String raw) {
        if (raw == null || raw.isBlank()) {
            return AgentRoleEnum.COORDINATOR.toScopedName();
        }
        try {
            return AgentRoleEnum.fromString(raw).toScopedName();
        } catch (IllegalArgumentException _) {
            // Dynamic / skill role — preserve as-is.
            return raw.trim();
        }
    }
}
