package com.amsha.app.cli;

import picocli.CommandLine;

import jakarta.annotation.Priority;
import jakarta.enterprise.context.control.ActivateRequestContext;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;

import java.io.BufferedReader;
import java.io.Console;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
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
import com.amsha.core.security.AutonomyLevel;

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

    @CommandLine.Option(
            names = {"-r", "--role"},
            description = "Explicit agent role. Omit to use shared intent and skill routing.")
    String role;

    @CommandLine.Option(
            names = {"-s", "--session-id"},
            description = "Resume an existing persisted session by ID; generates a new CLI session when omitted.")
    String requestedSessionId;

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

    private volatile String currentCliSessionId;

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
        String sessionId = requestedSessionId == null || requestedSessionId.isBlank()
                ? "cli-" + UUID.randomUUID()
                : requestedSessionId.trim();
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
            if (this.currentCliSessionId != null) {
                this.currentCliSessionId = null;
                workspaceManager.terminateSession(sessionId);
            }
            AnsiConsole.systemUninstall();
        }

        log.infof("CLI chat session %s terminated", sessionId);
        System.out.println("\n[Terminated]");
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
            cliResultBridge.cancel(sessionId); // unblocks any HITL virtual thread immediately
            workspaceManager.terminateSession(sessionId);
        } catch (Exception e) {
            log.warnf("Session cleanup on shutdown incomplete for %s: %s", sessionId, LogUtil.rootMessage(e));
        }
    }

    private void runChatLoop(String sessionId) {
        try {
            heartbeatTracker.recordHeartbeat(sessionId);
            WorkspaceContext.run(
                    sessionId,
                    CLI_USER_ID,
                    CLI_CHAT_ID,
                    AutonomyLevel.SUPERVISED,
                    AgentRoleEnum.COORDINATOR.toScopedName(),
                    () -> {
                        chatLoop(sessionId);
                        return null;
                    });
        } catch (Exception e) {
            log.errorf("Error in WorkspaceContext for session %s:\n%s", sessionId, LogUtil.trimmedStack(e));
            System.err.println("❌ Session error: " + e.getMessage());
        }
    }

    private void chatLoop(String sessionId) {
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
            chatLoopInner(sessionId, reader, terminal);

        } catch (Exception e) {
            log.warnf("JLine3 terminal init failed (%s), falling back to raw input", e.getMessage());
            chatLoopFallback(sessionId);
        } finally {
            this.lineReader = null;
        }
    }

    private void chatLoopInner(String sessionId, LineReader reader, Terminal terminal) {
        while (true) {
            String input;
            try {
                input = reader.readLine("\nYou> ");
                if (input == null) break;
                input = input.trim();
            } catch (EndOfFileException _) {
                break;
            } catch (UserInterruptException _) {
                // Ctrl+C during line editing - continue loop
                continue;
            }

            if ("/exit".equalsIgnoreCase(input) || "/quit".equalsIgnoreCase(input)) {
                log.infof("CLI chat session %s exiting on user command", sessionId);
                terminal.writer().println("Goodbye.");
                break;
            }
            if (input.isBlank()) continue;

            if (!input.startsWith("/approve") && !input.startsWith("/deny")) {
                if (hitlPending) {
                    terminal.writer()
                            .println(
                                    "\n▶ Amsha Agent> ⏸ Approval pending - type /approve or /deny before sending new messages.");
                    continue;
                }
            }

            dispatchToWorkflow(sessionId, input);
        }
    }

    /** Fallback for non-TTY environments (CI, piped stdin, Docker without -t). */
    private void chatLoopFallback(String sessionId) {
        Console console = System.console();
        BufferedReader fallbackReader =
                (console == null) ? new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8)) : null;

        while (true) {
            String input;
            try {
                if (console != null) {
                    input = console.readLine("\nYou> ");
                } else {
                    System.out.print("\nYou> ");
                    System.out.flush();
                    input = fallbackReader.readLine();
                }
                if (input == null) break;
                input = input.trim();
            } catch (Exception _) {
                break;
            }

            if ("/exit".equalsIgnoreCase(input) || "/quit".equalsIgnoreCase(input)) {
                System.out.println("Goodbye.");
                break;
            }
            if (input.isBlank()) continue;

            if (!input.startsWith("/approve") && !input.startsWith("/deny")) {
                if (hitlPending) {
                    System.out.println(
                            "\n▶ Amsha Agent> ⏸ Approval pending - type /approve or /deny before sending new messages.");
                    continue;
                }
            }

            dispatchToWorkflow(sessionId, input);
        }
    }

    private List<String> extractSlashCommands() {
        return List.of(
                "/approve",
                "/approvals",
                "/commands",
                "/crons",
                "/deletecron",
                "/deny",
                "/exit",
                "/halt",
                "/help",
                "/history",
                "/quit",
                "/reset",
                "/retry",
                "/status",
                "/store",
                "/vault");
    }

    private void dispatchToWorkflow(String sessionId, String input) {
        try {
            log.infof("CLI chat session %s dispatching input: %s", sessionId, input);
            System.out.println("⚡ Thinking...");
            ChannelMessage message = new ChannelMessage.CliMessage(
                    new ChannelUser(CLI_USER_ID, CLI_CHAT_ID, "CLI User", ChannelType.CLI),
                    input,
                    sessionId,
                    Instant.now());
            TurnDispatchResult result = turnDispatchService.dispatch(new TurnRequest(
                    message,
                    CLI_CHAT_ID,
                    Optional.of(sessionId),
                    Optional.ofNullable(role),
                    Optional.of(maxIterations)));
            hitlPending = result.agentResult()
                    .map(AgentResult.AwaitingApproval.class::isInstance)
                    .orElse(false);
            if (hitlPending) {
                waitForHitlResult(sessionId, cliResultBridge.register(sessionId));
            }
            if (input.startsWith("/approve") || input.startsWith("/deny")) {
                hitlPending = false;
            }

        } catch (Exception e) {
            log.errorf("Agent error in session %s:\n%s", sessionId, LogUtil.trimmedStack(e));
            System.err.println("❌ Error: " + e.getMessage());
        }
    }

    private void waitForHitlResult(String sessionId, CompletableFuture<AgentResult> resultFuture) {
        Thread.ofVirtual().name("hitl-waiter-" + sessionId).start(() -> {
            try {
                AgentResult result = resultFuture.get(hitlTimeoutHours, TimeUnit.HOURS);
                if (result == null) return;
                String output = formatTerminalResult(result);
                if (output == null) return;
                LineReader reader = lineReader;
                if (reader != null) {
                    reader.printAbove("\n▶ Amsha Agent> " + output);
                } else {
                    System.out.println("\n▶ Amsha Agent> " + output);
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

    private static String formatTerminalResult(AgentResult result) {
        return switch (result) {
            case AgentResult.Finished f -> f.answer();
            case AgentResult.StructuredFinished sf -> sf.answer();
            case AgentResult.Failed f -> "❌ Error: " + f.reason();
            case AgentResult.LoopExceeded l -> "⏱ Timed out after " + l.maxIterations() + " steps.";
            case AgentResult.AwaitingRetry r -> "⏳ Rate limit hit. Retry in " + (r.retryAfterMs() / 1000) + "s...";
            case AgentResult.AwaitingApproval _ -> null;
        };
    }
}
