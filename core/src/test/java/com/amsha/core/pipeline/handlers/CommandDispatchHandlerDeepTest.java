package com.amsha.core.pipeline.handlers;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.Callable;

import com.amsha.channel.api.ChannelCommand;
import com.amsha.channel.api.ChannelMessage;
import com.amsha.channel.api.ChannelPlugin;
import com.amsha.channel.api.ChannelSender;
import com.amsha.channel.api.ChannelType;
import com.amsha.channel.api.ChannelUser;
import com.amsha.core.channel.ChannelPluginRegistry;
import com.amsha.core.pipeline.HandleResult;
import com.amsha.core.pipeline.MessageContext;
import com.amsha.core.pipeline.parsers.CommandParserRegistry;
import com.amsha.core.reflex.ReflexRegistry;
import com.amsha.core.reflex.ReflexResult;
import com.amsha.core.scope.WorkspaceContext;
import com.amsha.core.security.AutonomyLevel;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

/**
 * Deep unit tests for {@link CommandDispatchHandler}.
 * Tests all command dispatch branches with real method calls.
 * Only external dependencies are mocked.
 */
class CommandDispatchHandlerDeepTest {

    private CommandDispatchHandler handler;
    private ReflexRegistry reflexRegistry;
    private ChannelSender sender;
    private ChannelPluginRegistry channelPluginRegistry;
    private CommandParserRegistry commandParserRegistry;
    private MockedStatic<WorkspaceContext> mockedContext;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setup() throws Exception {
        reflexRegistry = mock(ReflexRegistry.class);
        sender = mock(ChannelSender.class);
        channelPluginRegistry = mock(ChannelPluginRegistry.class);
        commandParserRegistry = mock(CommandParserRegistry.class);

        ChannelPlugin plugin = mock(ChannelPlugin.class);
        doReturn(sender).when(plugin).sender();
        doReturn(Optional.of(plugin)).when(channelPluginRegistry).find(any(ChannelType.class));

        handler = new CommandDispatchHandler();
        handler.reflexRegistry = reflexRegistry;
        handler.channelPluginRegistry = channelPluginRegistry;
        handler.commandParserRegistry = commandParserRegistry;
        handler.defaultTimezone = "Asia/Kolkata";

        // Default: real parsing
        doAnswer(inv -> ChannelCommand.parse(inv.getArgument(0)))
                .when(commandParserRegistry)
                .parse(any(), any());

        mockedContext = mockStatic(WorkspaceContext.class);
        mockedContext
                .when(() -> WorkspaceContext.run(
                        anyString(),
                        anyString(),
                        anyString(),
                        any(AutonomyLevel.class),
                        anyString(),
                        any(Callable.class)))
                .thenAnswer(inv -> {
                    Callable<?> task = inv.getArgument(5);
                    return task.call();
                });
    }

    @AfterEach
    void tearDown() {
        mockedContext.close();
    }

    private MessageContext createCtx(ChannelUser user, ChannelMessage msg) {
        return new MessageContext("chat1", user, msg);
    }

    private ChannelUser createUser() {
        return new ChannelUser("1", "1", "user", ChannelType.CLI);
    }

    private void stubReflexResult(String expectedInput, ReflexResult result) {
        lenient()
                .doReturn(result)
                .when(reflexRegistry)
                .processIfReflex(anyString(), anyString(), anyString(), eq(expectedInput), any());
    }

    private void stubReflexThrow(String expectedInput, RuntimeException ex) {
        lenient()
                .doThrow(ex)
                .when(reflexRegistry)
                .processIfReflex(anyString(), anyString(), anyString(), eq(expectedInput), any());
    }

    private void stubParseUnknown(String raw) {
        lenient()
                .doReturn(new ChannelCommand.Unknown(raw))
                .when(commandParserRegistry)
                .parse(any(), any());
    }

    private void stubNoChannelPlugin() {
        lenient().doReturn(Optional.empty()).when(channelPluginRegistry).find(any(ChannelType.class));
    }

    // ── priority ─────────────────────────────────────────────────────────

    @Test
    void priority_returns45() {
        assertEquals(45, handler.priority());
    }

    // ── Approve commands ─────────────────────────────────────────────────

    @Nested
    class ApproveCommandTests {

        @Test
        void approveWithTargetId_dispatchesApproveWithId() {
            ChannelUser user = createUser();
            ChannelMessage msg = new ChannelMessage.CliMessage(user, "/approve tool123", "sid", Instant.now());
            MessageContext ctx = createCtx(user, msg);
            ctx.setSessionId("test-session");

            stubReflexResult("/approve tool123", new ReflexResult(Optional.of("Approved"), "/approve tool123"));

            assertEquals(HandleResult.STOP, handler.handle(ctx));
            verify(sender).sendRaw(user, "Approved");
        }

        @Test
        void approveWithoutTarget_dispatchesApprove() {
            ChannelUser user = createUser();
            ChannelMessage msg = new ChannelMessage.CliMessage(user, "/approve", "sid", Instant.now());
            MessageContext ctx = createCtx(user, msg);
            ctx.setSessionId("test-session");

            stubReflexResult("/approve", new ReflexResult(Optional.of("Approved all"), "/approve"));

            assertEquals(HandleResult.STOP, handler.handle(ctx));
            verify(sender).sendRaw(user, "Approved all");
        }
    }

    // ── Deny commands ────────────────────────────────────────────────────

    @Nested
    class DenyCommandTests {

        @Test
        void denyWithTargetId_dispatchesDenyWithId() {
            ChannelUser user = createUser();
            ChannelMessage msg = new ChannelMessage.CliMessage(user, "/deny tool456", "sid", Instant.now());
            MessageContext ctx = createCtx(user, msg);
            ctx.setSessionId("test-session");

            stubReflexResult("/deny tool456", new ReflexResult(Optional.of("Denied"), "/deny tool456"));

            assertEquals(HandleResult.STOP, handler.handle(ctx));
            verify(sender).sendRaw(user, "Denied");
        }

        @Test
        void denyWithoutTarget_dispatchesDeny() {
            ChannelUser user = createUser();
            ChannelMessage msg = new ChannelMessage.CliMessage(user, "/deny", "sid", Instant.now());
            MessageContext ctx = createCtx(user, msg);
            ctx.setSessionId("test-session");

            stubReflexResult("/deny", new ReflexResult(Optional.empty(), "/deny"));

            assertEquals(HandleResult.STOP, handler.handle(ctx));
        }
    }

    // ── Status / History / Halt / Reset ──────────────────────────────────

    @Nested
    class BuiltInCommandTests {

        @Test
        void status_dispatchesSlashStatus() {
            ChannelUser user = createUser();
            ChannelMessage msg = new ChannelMessage.CliMessage(user, "/status", "sid", Instant.now());
            MessageContext ctx = createCtx(user, msg);
            ctx.setSessionId("s1");

            stubReflexResult("/status", new ReflexResult(Optional.of("Status: active"), "/status"));

            assertEquals(HandleResult.STOP, handler.handle(ctx));
            verify(sender).sendRaw(user, "Status: active");
        }

        @Test
        void history_dispatchesSlashHistory() {
            ChannelUser user = createUser();
            ChannelMessage msg = new ChannelMessage.CliMessage(user, "/history", "sid", Instant.now());
            MessageContext ctx = createCtx(user, msg);
            ctx.setSessionId("s1");

            stubReflexResult("/history", new ReflexResult(Optional.of("History shown"), "/history"));

            assertEquals(HandleResult.STOP, handler.handle(ctx));
            verify(sender).sendRaw(user, "History shown");
        }

        @Test
        void halt_dispatchesSlashHalt() {
            ChannelUser user = createUser();
            ChannelMessage msg = new ChannelMessage.CliMessage(user, "/halt", "sid", Instant.now());
            MessageContext ctx = createCtx(user, msg);
            ctx.setSessionId("s1");

            stubReflexResult("/halt", new ReflexResult(Optional.of("Halted"), "/halt"));

            assertEquals(HandleResult.STOP, handler.handle(ctx));
            verify(sender).sendRaw(user, "Halted");
        }

        @Test
        void reset_dispatchesSlashReset() {
            ChannelUser user = createUser();
            ChannelMessage msg = new ChannelMessage.CliMessage(user, "/reset", "sid", Instant.now());
            MessageContext ctx = createCtx(user, msg);
            ctx.setSessionId("s1");

            stubReflexResult("/reset", new ReflexResult(Optional.of("Reset done"), "/reset"));

            assertEquals(HandleResult.STOP, handler.handle(ctx));
            verify(sender).sendRaw(user, "Reset done");
        }
    }

    // ── Start command ────────────────────────────────────────────────────

    @Nested
    class StartCommandTests {

        @Test
        void startWithPayload_dispatchesPayload() {
            ChannelUser user = createUser();
            ChannelMessage msg = new ChannelMessage.CliMessage(user, "/start tell me a joke", "sid", Instant.now());
            MessageContext ctx = createCtx(user, msg);
            ctx.setSessionId("s1");

            stubReflexResult("tell me a joke", new ReflexResult(Optional.empty(), "tell me a joke"));

            assertEquals(HandleResult.STOP, handler.handle(ctx));
        }

        @Test
        void startWithoutPayload_sendsWelcomeMessage() {
            ChannelUser user = createUser();
            ChannelMessage msg = new ChannelMessage.CliMessage(user, "/start", "sid", Instant.now());
            MessageContext ctx = createCtx(user, msg);
            ctx.setSessionId("s1");

            assertEquals(HandleResult.STOP, handler.handle(ctx));
            verify(sender).sendRaw(eq(user), contains("Amsha Agent"));
        }
    }

    // ── CronTasks / DeleteCron ───────────────────────────────────────────

    @Nested
    class CronCommandTests {

        @Test
        void cronTasks_dispatchesSlashCron() {
            ChannelUser user = createUser();
            ChannelMessage msg = new ChannelMessage.CliMessage(user, "/crons", "sid", Instant.now());
            MessageContext ctx = createCtx(user, msg);
            ctx.setSessionId("s1");

            stubReflexResult("/cron", new ReflexResult(Optional.of("No cron tasks"), "/cron"));

            assertEquals(HandleResult.STOP, handler.handle(ctx));
        }

        @Test
        void deleteCronWithId_dispatchesDeleteCronWithId() {
            ChannelUser user = createUser();
            ChannelMessage msg = new ChannelMessage.CliMessage(user, "/deletecron abc-123", "sid", Instant.now());
            MessageContext ctx = createCtx(user, msg);
            ctx.setSessionId("s1");

            stubReflexResult("deletecron abc-123", new ReflexResult(Optional.of("Deleted"), "deletecron abc-123"));

            assertEquals(HandleResult.STOP, handler.handle(ctx));
            verify(sender).sendRaw(user, "Deleted");
        }

        @Test
        void deleteCronWithoutId_dispatchesDeleteCron() {
            ChannelUser user = createUser();
            ChannelMessage msg = new ChannelMessage.CliMessage(user, "/deletecron", "sid", Instant.now());
            MessageContext ctx = createCtx(user, msg);
            ctx.setSessionId("s1");

            stubReflexResult("deletecron", new ReflexResult(Optional.of("Specify cron ID"), "deletecron"));

            assertEquals(HandleResult.STOP, handler.handle(ctx));
        }
    }

    // ── Unknown commands ─────────────────────────────────────────────────

    @Nested
    class UnknownCommandTests {

        @Test
        void unknownSlashStart_sendsWelcomeMessage() {
            ChannelUser user = createUser();
            ChannelMessage msg = new ChannelMessage.CliMessage(user, "/start extra stuff", "sid", Instant.now());
            MessageContext ctx = createCtx(user, msg);
            ctx.setSessionId("s1");

            stubParseUnknown("/start");

            assertEquals(HandleResult.STOP, handler.handle(ctx));
            verify(sender).sendRaw(eq(user), contains("Amsha Agent"));
        }

        @Test
        void unknownCommand_withCommandResponse_sendsResponse() {
            ChannelUser user = createUser();
            ChannelMessage msg = new ChannelMessage.CliMessage(user, "/customcommand", "sid", Instant.now());
            MessageContext ctx = createCtx(user, msg);
            ctx.setSessionId("s1");

            stubParseUnknown("/customcommand");
            stubReflexResult("/customcommand", new ReflexResult(Optional.of("Custom response"), "/customcommand"));

            assertEquals(HandleResult.STOP, handler.handle(ctx));
            verify(sender).sendRaw(user, "Custom response");
        }

        @Test
        void unknownCommand_noResponse_cont() {
            ChannelUser user = createUser();
            ChannelMessage msg = new ChannelMessage.CliMessage(user, "/mystery", "sid", Instant.now());
            MessageContext ctx = createCtx(user, msg);
            ctx.setSessionId("s1");

            stubParseUnknown("/mystery");
            stubReflexResult("/mystery", new ReflexResult(Optional.empty(), "/mystery"));

            assertEquals(HandleResult.CONTINUE, handler.handle(ctx));
        }

        @Test
        void unknownCommand_exception_sendsErrorToUser() {
            ChannelUser user = createUser();
            ChannelMessage msg = new ChannelMessage.CliMessage(user, "/boom", "sid", Instant.now());
            MessageContext ctx = createCtx(user, msg);
            ctx.setSessionId("s1");

            stubParseUnknown("/boom");
            stubReflexThrow("/boom", new RuntimeException("test error"));

            assertEquals(HandleResult.STOP, handler.handle(ctx));
            verify(sender).sendRaw(eq(user), contains("internal error"));
        }

        @Test
        void unknownCommand_exceptionNoSender_logsWarn() {
            ChannelUser user = createUser();
            ChannelMessage msg = new ChannelMessage.CliMessage(user, "/boom", "sid", Instant.now());
            MessageContext ctx = createCtx(user, msg);
            ctx.setSessionId("s1");

            stubNoChannelPlugin();
            stubParseUnknown("/boom");
            stubReflexThrow("/boom", new RuntimeException("test error"));

            assertEquals(HandleResult.STOP, handler.handle(ctx));
            verify(sender, never()).sendRaw(any(), any());
        }

        @Test
        void unknownCommand_blankResponse_stillSends() {
            // The actual code sends blank responses (no blank check in handleUnknownCommand)
            ChannelUser user = createUser();
            ChannelMessage msg = new ChannelMessage.CliMessage(user, "/mystery2", "sid", Instant.now());
            MessageContext ctx = createCtx(user, msg);
            ctx.setSessionId("s1");

            stubParseUnknown("/mystery2");
            stubReflexResult("/mystery2", new ReflexResult(Optional.of("  "), "/mystery2"));

            assertEquals(HandleResult.STOP, handler.handle(ctx));
            // Blank responses are sent (no blank check in the code)
            verify(sender).sendRaw(user, "  ");
        }

        @Test
        void unknownCommand_noSenderForChannel_logsWarn() {
            ChannelUser user = createUser();
            ChannelMessage msg = new ChannelMessage.CliMessage(user, "/mystery3", "sid", Instant.now());
            MessageContext ctx = createCtx(user, msg);
            ctx.setSessionId("s1");

            stubNoChannelPlugin();
            stubParseUnknown("/mystery3");
            stubReflexResult("/mystery3", new ReflexResult(Optional.of("Response"), "/mystery3"));

            assertEquals(HandleResult.STOP, handler.handle(ctx));
            verify(sender, never()).sendRaw(any(), any());
        }
    }

    // ── Plain text (not a command) ───────────────────────────────────────

    @Nested
    class PlainTextTests {

        @Test
        void plainText_returnsContinue() {
            ChannelUser user = createUser();
            ChannelMessage msg = new ChannelMessage.CliMessage(user, "Hello world", "sid", Instant.now());
            MessageContext ctx = createCtx(user, msg);

            assertEquals(HandleResult.CONTINUE, handler.handle(ctx));
            verify(sender, never()).sendRaw(any(), any());
        }
    }

    // ── Dispatch exception handling ──────────────────────────────────────

    @Nested
    class DispatchExceptionTests {

        @Test
        void approveException_sendsErrorToUser() {
            ChannelUser user = createUser();
            ChannelMessage msg = new ChannelMessage.CliMessage(user, "/approve", "sid", Instant.now());
            MessageContext ctx = createCtx(user, msg);
            ctx.setSessionId("s1");

            stubReflexThrow("/approve", new RuntimeException("DB error"));

            assertEquals(HandleResult.STOP, handler.handle(ctx));
            verify(sender).sendRaw(eq(user), contains("internal error"));
        }

        @Test
        void statusException_sendsErrorToUser() {
            ChannelUser user = createUser();
            ChannelMessage msg = new ChannelMessage.CliMessage(user, "/status", "sid", Instant.now());
            MessageContext ctx = createCtx(user, msg);
            ctx.setSessionId("s1");

            stubReflexThrow("/status", new RuntimeException("timeout"));

            assertEquals(HandleResult.STOP, handler.handle(ctx));
            verify(sender).sendRaw(eq(user), contains("internal error"));
        }
    }

    // ── No sender for channel ────────────────────────────────────────────

    @Nested
    class NoSenderTests {

        @Test
        void approveNoSender_logsWarnAndStops() {
            ChannelUser user = createUser();
            ChannelMessage msg = new ChannelMessage.CliMessage(user, "/approve", "sid", Instant.now());
            MessageContext ctx = createCtx(user, msg);
            ctx.setSessionId("s1");

            stubNoChannelPlugin();
            stubReflexResult("/approve", new ReflexResult(Optional.of("Done"), "/approve"));

            assertEquals(HandleResult.STOP, handler.handle(ctx));
            verify(sender, never()).sendRaw(any(), any());
        }
    }
}
