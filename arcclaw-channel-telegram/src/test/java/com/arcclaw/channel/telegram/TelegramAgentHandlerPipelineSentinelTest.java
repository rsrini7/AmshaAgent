package com.arcclaw.channel.telegram;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

import jakarta.inject.Inject;

import com.arcclaw.channel.api.ChannelMessage;
import com.arcclaw.channel.api.ChannelServices;
import com.arcclaw.channel.api.ChannelType;
import com.arcclaw.channel.api.ChannelUser;
import com.arcclaw.channel.api.ProcessingLockManager;
import io.quarkus.test.InjectMock;
import io.quarkus.test.component.QuarkusComponentTest;
import org.apache.camel.Exchange;
import org.apache.camel.Message;
import org.apache.camel.component.telegram.model.IncomingMessage;
import org.apache.camel.component.telegram.model.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/**
 * Verifies that the Telegram channel correctly notifies the user when the
 * core processing pipeline fails (Bug A.10 regression guard).
 */
@QuarkusComponentTest
class TelegramAgentHandlerPipelineSentinelTest {

    @Inject
    TelegramAgentHandler handler;

    @InjectMock
    ChannelServices channelServices;

    @InjectMock
    TelegramChannelSender sender;

    @InjectMock
    TelegramMessageMapper mapper;

    @InjectMock
    ProcessingLockManager lockManager;

    private final Map<String, Boolean> locks = new ConcurrentHashMap<>();

    @BeforeEach
    void setup() {
        locks.clear();
        when(lockManager.tryLock(any())).thenAnswer(invocation ->
                locks.putIfAbsent(invocation.getArgument(0), Boolean.TRUE) == null);
        when(lockManager.isLocked(any())).thenAnswer(invocation ->
                locks.containsKey(invocation.getArgument(0)));
        Mockito.lenient().doAnswer(invocation ->
                locks.remove(invocation.getArgument(0))
        ).when(lockManager).unlock(any());
    }

    @Test
    void bugA10_pipelineCrash_notifiesUser() {
        String chatId = "12345";
        // Use numeric ID to avoid NumberFormatException when simulating Telegram's Long field
        String userId = "45678";
        Exchange exchange = createExchange(chatId, userId, "alice", "hello");
        
        ChannelUser user = new ChannelUser(userId, chatId, "alice", ChannelType.TELEGRAM);
        ChannelMessage msg = new ChannelMessage.TelegramMessage(user, "hello", chatId, Instant.now());
        when(mapper.map(any(), any(), any())).thenReturn(msg);

        // Simulate a crash in the core pipeline (returned via SPI)
        CompletableFuture<Void> failed = new CompletableFuture<>();
        failed.completeExceptionally(new RuntimeException("Core pipeline exploded"));
        when(channelServices.processAsync(any())).thenReturn(failed);

        // Suppress expected stack traces in the test logs
        var julLogger = Logger.getLogger(TelegramAgentHandler.class.getName());
        julLogger.setLevel(Level.OFF);
        try {
            handler.receiveMessage(exchange);
        } finally {
            julLogger.setLevel(null); // Restore to parent logging level configuration
        }

        // Verify user IS notified of the internal error
        verify(sender).sendRaw(argThat(u -> chatId.equals(u.channelId())), contains("internal error occurred"));
    }

    private Exchange createExchange(String chatId, String userId, String username, String text) {
        Exchange exchange = mock(Exchange.class);
        Message in = mock(Message.class);
        when(exchange.getIn()).thenReturn(in);
        when(in.getHeader("CamelTelegramChatId", String.class)).thenReturn(chatId);

        IncomingMessage msg = new IncomingMessage();
        User srcUser = new User();
        srcUser.setId(Long.parseLong(userId)); // This was the failure point
        srcUser.setUsername(username);
        msg.setFrom(srcUser);
        msg.setText(text);

        when(in.getBody(IncomingMessage.class)).thenReturn(msg);
        return exchange;
    }
}
