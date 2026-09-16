package com.amsha.channel.telegram;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.amsha.channel.api.ChannelType;
import com.amsha.channel.api.ChannelUser;

import org.apache.camel.component.telegram.model.InlineKeyboardMarkup;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class TelegramJobNotifierTest {

    private TelegramJobNotifier notifier;
    private TelegramChannelSender sender;

    @BeforeEach
    void setUp() {
        notifier = new TelegramJobNotifier();
        sender = mock(TelegramChannelSender.class);
        notifier.sender = sender;
        notifier.markdownRenderer = new TelegramMarkdownV2Renderer();
    }

    @Test
    void test_supports() {
        assertTrue(notifier.supports(ChannelType.TELEGRAM));
        assertFalse(notifier.supports(ChannelType.CLI));
        assertFalse(notifier.supports(ChannelType.REST_API));
        assertFalse(notifier.supports(null));
    }

    @Test
    void test_notifyJobCompleted_valid_recipient() {
        notifier.notifyJobCompleted("123456789", "Job finished successfully", "sess-1");

        ArgumentCaptor<ChannelUser> userCaptor = ArgumentCaptor.forClass(ChannelUser.class);
        ArgumentCaptor<String> msgCaptor = ArgumentCaptor.forClass(String.class);
        verify(sender).sendRaw(userCaptor.capture(), msgCaptor.capture());

        assertEquals("123456789", userCaptor.getValue().channelId());
        assertEquals("Job finished successfully", msgCaptor.getValue());
    }

    @Test
    void test_notifyJobCompleted_invalid_recipient_dropped() {
        notifier.notifyJobCompleted("invalid_id", "message", "sess-1");
        notifier.notifyJobCompleted(null, "message", "sess-1");
        notifier.notifyJobCompleted("", "message", "sess-1");

        verifyNoInteractions(sender);
    }

    @Test
    void test_notifyJobCompleted_handles_sender_exception() {
        doThrow(new RuntimeException("Simulated network failure")).when(sender).sendRaw(any(), any());

        // Must not rethrow
        assertDoesNotThrow(() -> notifier.notifyJobCompleted("123456", "msg", "sess-1"));
    }

    @Test
    void test_notifyApprovalRequired_valid_recipient() {
        notifier.notifyApprovalRequired("123456789", "cron-sess-1", "sandbox_exec", "Please approve execution");

        ArgumentCaptor<ChannelUser> userCaptor = ArgumentCaptor.forClass(ChannelUser.class);
        ArgumentCaptor<String> msgCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<InlineKeyboardMarkup> markupCaptor = ArgumentCaptor.forClass(InlineKeyboardMarkup.class);

        verify(sender).sendWithKeyboard(userCaptor.capture(), msgCaptor.capture(), markupCaptor.capture());
        assertEquals("123456789", userCaptor.getValue().channelId());
        assertNotNull(markupCaptor.getValue());
        assertFalse(markupCaptor.getValue().getInlineKeyboard().isEmpty());
    }

    @Test
    void test_notifyApprovalRequired_invalid_recipient_dropped() {
        notifier.notifyApprovalRequired("non_numeric", "sess-1", "tool", "msg");
        notifier.notifyApprovalRequired(null, "sess-1", "tool", "msg");

        verifyNoInteractions(sender);
    }

    @Test
    void test_notifyApprovalRequired_handles_sender_exception() {
        doThrow(new RuntimeException("Send failure")).when(sender).sendWithKeyboard(any(), any(), any());

        assertDoesNotThrow(() -> notifier.notifyApprovalRequired("123456", "sess-1", "tool", "msg"));
    }

    @Test
    void test_notifyApprovalTimeout_valid_recipient() {
        notifier.notifyApprovalTimeout("123456", "sess-1", "web_search", 15);

        ArgumentCaptor<ChannelUser> userCaptor = ArgumentCaptor.forClass(ChannelUser.class);
        ArgumentCaptor<String> msgCaptor = ArgumentCaptor.forClass(String.class);
        verify(sender).sendRaw(userCaptor.capture(), msgCaptor.capture());

        assertEquals("123456", userCaptor.getValue().channelId());
        assertTrue(msgCaptor.getValue().contains("timed out after 15 minutes"));
    }

    @Test
    void test_notifyApprovalTimeout_null_or_blank_recipient_skipped() {
        notifier.notifyApprovalTimeout(null, "sess-1", "tool", 10);
        notifier.notifyApprovalTimeout("   ", "sess-1", "tool", 10);

        verifyNoInteractions(sender);
    }

    @Test
    void test_notifyApprovalTimeout_null_toolName_falls_back_to_unknown() {
        notifier.notifyApprovalTimeout("123456", "sess-1", null, 5);

        ArgumentCaptor<String> msgCaptor = ArgumentCaptor.forClass(String.class);
        verify(sender).sendRaw(any(), msgCaptor.capture());
        assertTrue(msgCaptor.getValue().contains("unknown"));
    }

    @Test
    void test_notifyApprovalTimeout_handles_exception() {
        doThrow(new RuntimeException("Timeout notify exception")).when(sender).sendRaw(any(), any());

        assertDoesNotThrow(() -> notifier.notifyApprovalTimeout("123456", "sess-1", "tool", 5));
    }
}
