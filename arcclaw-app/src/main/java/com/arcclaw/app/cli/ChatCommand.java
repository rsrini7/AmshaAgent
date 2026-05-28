package com.arcclaw.app.cli;

import picocli.CommandLine;

import jakarta.annotation.Priority;
import jakarta.enterprise.context.control.ActivateRequestContext;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;

import java.io.BufferedReader;
import java.io.Console;
import java.io.InputStreamReader;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import com.arcclaw.channel.api.ChannelMessage;
import com.arcclaw.channel.api.ChannelType;
import com.arcclaw.channel.api.ChannelUser;
import com.arcclaw.common.util.LogUtil;
import com.arcclaw.core.agent.loop.AgentLoop;
import com.arcclaw.core.agent.loop.CliResultBridge;
import com.arcclaw.core.agent.reasoning.ReasoningEngine;
import com.arcclaw.core.agent.result.AgentResult;
import com.arcclaw.core.agent.role.AgentRoleEnum;
import com.arcclaw.core.lifecycle.SessionHeartbeatTracker;
import com.arcclaw.core.lifecycle.WorkspaceManager;
import com.arcclaw.core.pipeline.MessageContext;
import com.arcclaw.core.reflex.ReflexRegistry;
import com.arcclaw.core.reflex.ReflexResult;
import com.arcclaw.core.scope.WorkspaceContext;
import com.arcclaw.core.security.AutonomyLevel;
import com.arcclaw.core.state.StatefulAgentStore;
import com.arcclaw.core.util.TimeContextInjector;

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

@CommandLine.Command(name = "chat", description = "Start an interactive chat session with ArcClaw")
public class ChatCommand implements Runnable {

    @CommandLine.Option(
            names = {"-r", "--role"},
            defaultValue = "coordinator")
    String role = "coordinator";

    @Inject
    Logger log;

    @Inject
    AgentLoop agentLoop;

    @Inject
    ReasoningEngine reasoningEngine;

    @Inject
    WorkspaceManager workspaceManager;

    @Inject
    SessionHeartbeatTracker heartbeatTracker;

    @Inject
    ReflexRegistry reflexRegistry;

    @Inject
    StatefulAgentStore statefulAgentStore;

    @Inject
    CliResultBridge cliResultBridge;

    private volatile String currentCliSessionId;

    /**
     * Guard flag: set to true when either the virtual HITL waiter thread OR the
     * /approve reflex has already printed the approval result, so the other path
     * suppresses its own print. Volatile is sufficient - only one CLI session runs
     * at a time on the main thread.
     */
    private volatile boolean hitlResultPrinted = false;

    /**
     * Guard flag: set to true when an AwaitingApproval result is received.
     * Replaces the store query in the HITL guard to eliminate the TOCTOU window
     * where the approval could complete between the check and the dispatch.
     */
    private volatile boolean hitlPending = false;

    /** JLine3 line reader - exposed to HITL virtual thread for printAbove(). */
    private volatile LineReader lineReader;

    @ConfigProperty(name = "arcclaw.agent.max-iterations", defaultValue = "5")
    int maxIterations;

    @ConfigProperty(name = "arcclaw.agent.default-timezone", defaultValue = "Asia/Kolkata")
    String defaultTimezone;

    @ConfigProperty(name = "arcclaw.hitl.timeout-hours", defaultValue = "24")
    long hitlTimeoutHours;

    @Override
    @ActivateRequestContext
    public void run() {
        String sessionId = "cli-" + UUID.randomUUID();
        this.currentCliSessionId = sessionId;

        // Install Jansi ANSI support — auto-detects terminal capabilities.
        // Enables ANSI colors in CliReplyRenderer when connected to a terminal;
        // disables them for piped output / CI logs.
        AnsiConsole.systemInstall();

        System.out.println("✦ ArcClaw Interactive Terminal");
        System.out.println("   Session : " + sessionId);
        System.out.println("   Role    : " + role);
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
                    "cli-user",
                    "cli-chat",
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
                    .variable(LineReader.HISTORY_FILE, Path.of(System.getProperty("user.home"), ".arcclaw_history"))
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
                terminal.writer().println("Goodbye.");
                break;
            }
            if (input.isBlank()) continue;

            if (!input.startsWith("/approve") && !input.startsWith("/deny")) {
                if (hitlPending) {
                    cliResultBridge.register(sessionId);
                    terminal.writer()
                            .println(
                                    "\n▶ ArcClaw> ⏸ Approval pending - type /approve or /deny before sending new messages.");
                    continue;
                }
            }

            heartbeatTracker.recordHeartbeat(sessionId);

            MessageContext context = createCliContext(sessionId, input);

            TimeContextInjector.inject(context, input, defaultTimezone);

            ReflexResult reflexResult =
                    reflexRegistry.processIfReflex(sessionId, "cli-user", "cli-chat", input, context);

            if (reflexResult.commandResponse().isPresent()) {
                String response = reflexResult.commandResponse().get();
                if (!response.isBlank()) {
                    hitlResultPrinted = true;
                    if (input.startsWith("/approve") || input.startsWith("/deny")) {
                        hitlPending = false;
                    }
                    terminal.writer().println("\n▶ ArcClaw> " + response);
                }
                continue;
            }

            dispatchToAgent(sessionId, reflexResult.sanitizedInput(), context);
        }
    }

    /** Fallback for non-TTY environments (CI, piped stdin, Docker without -t). */
    private void chatLoopFallback(String sessionId) {
        Console console = System.console();
        BufferedReader fallbackReader = (console == null) ? new BufferedReader(new InputStreamReader(System.in)) : null;

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
                    cliResultBridge.register(sessionId);
                    System.out.println(
                            "\n▶ ArcClaw> ⏸ Approval pending - type /approve or /deny before sending new messages.");
                    continue;
                }
            }

            heartbeatTracker.recordHeartbeat(sessionId);

            MessageContext context = createCliContext(sessionId, input);

            TimeContextInjector.inject(context, input, defaultTimezone);

            ReflexResult reflexResult =
                    reflexRegistry.processIfReflex(sessionId, "cli-user", "cli-chat", input, context);

            if (reflexResult.commandResponse().isPresent()) {
                String response = reflexResult.commandResponse().get();
                if (!response.isBlank()) {
                    hitlResultPrinted = true;
                    if (input.startsWith("/approve") || input.startsWith("/deny")) {
                        hitlPending = false;
                    }
                    System.out.println("\n▶ ArcClaw> " + response);
                }
                continue;
            }

            dispatchToAgent(sessionId, reflexResult.sanitizedInput(), context);
        }
    }

    private List<String> extractSlashCommands() {
        return List.of(
                "/approve",
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

    private void dispatchToAgent(String sessionId, String input, MessageContext context) {
        try {
            System.out.println("⚡ Thinking...");

            // Register BEFORE agentLoop.run() so the future exists even if
            // complete() fires on a REST thread before we reach the switch below.
            // The virtual thread holds this reference directly - no second register() needed.
            CompletableFuture<AgentResult> resultFuture = cliResultBridge.register(sessionId);

            hitlResultPrinted = false; // reset for this dispatch cycle

            AgentResult result = agentLoop.run(sessionId, role, input, reasoningEngine, maxIterations, context);

            String output =
                    switch (result) {
                        case AgentResult.Finished f -> f.answer();
                        case AgentResult.StructuredFinished sf -> sf.answer();
                        case AgentResult.Failed f -> "❌ Error: " + f.reason();
                        case AgentResult.LoopExceeded l -> "⏱ Timed out after " + l.maxIterations() + " steps.";
                        case AgentResult.AwaitingRetry r ->
                            "⏳ Rate limit hit. I'll automatically retry in " + (r.retryAfterMs() / 1000) + "s...";
                        case AgentResult.AwaitingApproval a -> {
                            hitlPending = true;
                            heartbeatTracker.recordHeartbeat(sessionId);
                            String truncatedParams =
                                    a.paramsJson() != null && a.paramsJson().length() > 200
                                            ? a.paramsJson().substring(0, 200) + "... [truncated]"
                                            : a.paramsJson();

                            System.out.println(
                                    "\n▶ ArcClaw> ⏸ Approval needed for tool: " + a.toolName() + "\n   Params: "
                                            + truncatedParams
                                            + "\n   Type /approve or /deny (or approve via REST/UI - result will appear automatically)");

                            // Spawn a virtual thread that holds the pre-registered future directly.
                            // This is race-safe: even if complete() fires before get() is called,
                            // the future is already in COMPLETED state and get() returns immediately.
                            Thread.ofVirtual().name("hitl-waiter-" + sessionId).start(() -> {
                                try {
                                    AgentResult hitlResult = resultFuture.get(hitlTimeoutHours, TimeUnit.HOURS);
                                    if (hitlResult == null || hitlResultPrinted) return;
                                    hitlResultPrinted = true;

                                    String hitlOutput =
                                            switch (hitlResult) {
                                                case AgentResult.Finished f -> f.answer();
                                                case AgentResult.StructuredFinished sf -> sf.answer();
                                                case AgentResult.Failed f -> "❌ Error: " + f.reason();
                                                case AgentResult.LoopExceeded l ->
                                                    "⏱ Timed out after " + l.maxIterations() + " steps.";
                                                case AgentResult.AwaitingRetry r ->
                                                    "⏳ Rate limit hit. Retry in " + (r.retryAfterMs() / 1000) + "s...";
                                                default -> null;
                                            };
                                    if (hitlOutput != null) {
                                        LineReader lr = lineReader;
                                        if (lr != null) {
                                            lr.printAbove("\n▶ ArcClaw> " + hitlOutput);
                                        } else {
                                            System.out.println("\n▶ ArcClaw> " + hitlOutput);
                                            System.out.print("\nYou> ");
                                            System.out.flush();
                                        }
                                    }
                                } catch (CancellationException e) {
                                    log.debugf("HITL waiter cancelled for session %s (shutdown or GC)", sessionId);
                                } catch (TimeoutException e) {
                                    log.debugf(
                                            "HITL waiter timed out for session %s after %dh",
                                            sessionId, hitlTimeoutHours);
                                } catch (InterruptedException e) {
                                    Thread.currentThread().interrupt();
                                } catch (Exception e) {
                                    log.warnf(
                                            "HITL waiter error for session %s: %s", sessionId, LogUtil.rootMessage(e));
                                } finally {
                                    hitlPending = false;
                                }
                            });

                            yield null; // main thread returns to scanner.nextLine()
                        }
                    };

            // Cancel bridge state for all terminal results.
            // For AwaitingApproval we intentionally skip - the virtual thread owns cleanup.
            if (!(result instanceof AgentResult.AwaitingApproval)) {
                cliResultBridge.cancel(sessionId);
            }

            if (output != null) {
                System.out.println("\n▶ ArcClaw> " + output);
            }

        } catch (Exception e) {
            log.errorf("Agent error in session %s:\n%s", sessionId, LogUtil.trimmedStack(e));
            System.err.println("❌ Error: " + e.getMessage());
        }
    }

    private MessageContext createCliContext(String sessionId, String input) {
        ChannelUser user = new ChannelUser("cli-user", "cli-user", "CLI User", ChannelType.CLI);
        ChannelMessage msg = new ChannelMessage.CliMessage(user, input, sessionId, Instant.now());
        return new MessageContext("cli-chat", user, msg);
    }
}
