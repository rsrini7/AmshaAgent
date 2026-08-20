package com.amsha.channel.api;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class AbstractInboundMessagePipelineTest {

    static class SimpleLockManager implements ProcessingLockManager {
        private final Set<String> locked = new HashSet<>();

        @Override
        public boolean tryLock(String chatId) {
            return locked.add(chatId);
        }

        @Override
        public void unlock(String chatId) {
            locked.remove(chatId);
        }

        @Override
        public boolean isLocked(String chatId) {
            return locked.contains(chatId);
        }
    }

    static class SimpleChannelServices implements ChannelServices {
        CompletableFuture<Void> future = CompletableFuture.completedFuture(null);

        @Override
        public String resolveOrCreateSession(String channelType, String channelId, String userId) {
            return "session-1";
        }

        @Override
        public CompletableFuture<Void> processAsync(ChannelMessage message) {
            return future;
        }

        @Override
        public void terminateSession(String sessionId) {}

        @Override
        public void recordHeartbeat(String sessionId) {}

        @Override
        public void bindSessionOwner(String sessionId, String ownerKeyName) {}

        @Override
        public boolean isValidIdentity(String apiKey) {
            return true;
        }
    }

    static class TestPipeline extends AbstractInboundMessagePipeline {
        final List<String> replies = new ArrayList<>();
        final List<String> logs = new ArrayList<>();

        TestPipeline(ChannelServices channelServices, ProcessingLockManager lockManager) {
            super(channelServices, ChannelType.CLI, lockManager);
        }

        @Override
        protected void sendRawReply(ChannelUser user, String text) {
            replies.add(text);
        }

        @Override
        protected void logWarn(String format, Object... args) {
            logs.add("WARN: " + String.format(format, args));
        }

        @Override
        protected void logInfo(String format, Object... args) {
            logs.add("INFO: " + String.format(format, args));
        }

        @Override
        protected void logError(String format, Throwable t) {
            logs.add("ERROR: " + format);
        }
    }

    SimpleChannelServices channelServices;
    SimpleLockManager lockManager;
    TestPipeline pipeline;

    @BeforeEach
    void setUp() {
        channelServices = new SimpleChannelServices();
        lockManager = new SimpleLockManager();
        pipeline = new TestPipeline(channelServices, lockManager);
    }

    @Test
    void testProcessInboundMessageSuccess() {
        ChannelUser user = new ChannelUser("u1", "chat1", "alice", ChannelType.CLI);
        ChannelMessage msg = new ChannelMessage.CliMessage(user, "hello", "sess1", Instant.now());

        pipeline.processInboundMessage("chat1", "hello", user, msg);

        assertTrue(pipeline.replies.contains("⏳"));
        assertFalse(pipeline.isProcessing("chat1"));
    }

    @Test
    void testProcessInboundMessageReflexCommandBypassesLock() {
        ChannelUser user = new ChannelUser("u1", "chat1", "alice", ChannelType.CLI);
        ChannelMessage msg = new ChannelMessage.CliMessage(user, "/help", "sess1", Instant.now());

        pipeline.processInboundMessage("chat1", "/help", user, msg);

        assertFalse(pipeline.replies.contains("⏳"));
    }

    @Test
    void testProcessInboundMessageNullText() {
        ChannelUser user = new ChannelUser("u1", "chat1", "alice", ChannelType.CLI);
        pipeline.processInboundMessage("chat1", null, user, null);

        assertTrue(pipeline.replies.stream().anyMatch(r -> r.contains("only process text")));
    }

    @Test
    void testProcessInboundMessageNullChatId() {
        ChannelUser user = new ChannelUser("u1", "chat1", "alice", ChannelType.CLI);
        pipeline.processInboundMessage(null, "hello", user, null);
        pipeline.processInboundMessage("   ", "hello", user, null);

        assertTrue(pipeline.replies.isEmpty());
    }

    @Test
    void testProcessInboundMessageNullUser() {
        ChannelMessage msg = new ChannelMessage.CliMessage(
                new ChannelUser("u1", "chat1", "alice", ChannelType.CLI), "hello", "sess1", Instant.now());
        pipeline.processInboundMessage("chat1", "hello", null, msg);

        assertTrue(pipeline.replies.isEmpty());
    }

    @Test
    void testProcessInboundMessageLocked() {
        ChannelUser user = new ChannelUser("u1", "chat1", "alice", ChannelType.CLI);
        ChannelMessage msg = new ChannelMessage.CliMessage(user, "hello", "sess1", Instant.now());

        lockManager.tryLock("chat1");

        pipeline.processInboundMessage("chat1", "hello", user, msg);

        assertTrue(pipeline.replies.stream().anyMatch(r -> r.contains("Still processing")));
    }

    @Test
    void testProcessInboundMessageExceptionHandling() {
        ChannelUser user = new ChannelUser("u1", "chat1", "alice", ChannelType.CLI);
        ChannelMessage msg = new ChannelMessage.CliMessage(user, "hello", "sess1", Instant.now());

        CompletableFuture<Void> errorFuture = new CompletableFuture<>();
        errorFuture.completeExceptionally(new RuntimeException("boom"));
        channelServices.future = errorFuture;

        pipeline.processInboundMessage("chat1", "hello", user, msg);

        assertTrue(pipeline.replies.stream().anyMatch(r -> r.contains("internal error")));
    }
}
