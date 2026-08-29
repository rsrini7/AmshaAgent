package com.amsha.channel.telegram;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.List;

import com.amsha.channel.api.ApprovalPrompt;
import com.amsha.channel.api.ChannelType;
import com.amsha.channel.api.ChannelUser;
import com.amsha.channel.api.ContentNode;

import org.apache.camel.ProducerTemplate;
import org.apache.camel.component.telegram.model.InlineKeyboardMarkup;
import org.apache.camel.component.telegram.model.OutgoingTextMessage;
import org.jboss.logging.Logger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class TelegramChannelSenderTest {

    @Mock
    ProducerTemplate producerTemplate;

    @Mock
    TelegramMarkdownV2Renderer renderer;

    @Mock
    Logger log;

    @InjectMocks
    TelegramChannelSender sender;

    private final ChannelUser user = new ChannelUser("123", "123", "alice", ChannelType.TELEGRAM);

    @BeforeEach
    void setUp() {
        sender.maxLength = 4096;
    }

    @Test
    void testSendWithNodesCallsSendRaw() {
        when(renderer.render(any())).thenReturn("Hello");
        sender.send(user, List.of(new ContentNode.Text("hi")));

        verify(producerTemplate)
                .sendBodyAndHeader(eq("telegram:bots"), eq("Hello"), eq(TelegramConstants.TELEGRAM_CHAT_ID), eq("123"));
    }

    @Test
    void testSendWithEmptyNodesDeliversFallback() {
        sender.send(user, List.of());
        verify(producerTemplate)
                .sendBodyAndHeader(
                        eq("telegram:bots"),
                        eq("(No response generated)"),
                        eq(TelegramConstants.TELEGRAM_CHAT_ID),
                        eq("123"));
    }

    @Test
    void testSendWithNullNodesDeliversFallback() {
        sender.send(user, null);
        verify(producerTemplate)
                .sendBodyAndHeader(
                        eq("telegram:bots"),
                        eq("(No response generated)"),
                        eq(TelegramConstants.TELEGRAM_CHAT_ID),
                        eq("123"));
    }

    @Test
    void testSendWithNullRecipientDoesNotThrow() {
        assertDoesNotThrow(() -> sender.send(null, List.of()));
        verifyNoInteractions(producerTemplate);
    }

    @Test
    void testSenderEndpointUriIsClean() {
        when(renderer.render(any())).thenReturn("hi");
        sender.send(user, List.of(new ContentNode.Text("hi")));
        ArgumentCaptor<String> uriCaptor = ArgumentCaptor.forClass(String.class);
        verify(producerTemplate).sendBodyAndHeader(uriCaptor.capture(), any(), any(), any());
        assertEquals(
                "telegram:bots",
                uriCaptor.getValue(),
                "Sender URI must be exactly 'telegram:bots' with no query params");
    }

    @Test
    void testSendApprovalPromptNode() {
        ApprovalPrompt prompt = new ApprovalPrompt("sess1", "tool_a,tool_b", "{}");
        when(renderer.render(any())).thenReturn("Approval Required");

        sender.send(user, List.of(prompt));

        verify(producerTemplate)
                .sendBodyAndHeader(
                        eq("telegram:bots"),
                        any(OutgoingTextMessage.class),
                        eq(TelegramConstants.TELEGRAM_CHAT_ID),
                        eq("123"));
    }

    @Test
    void testSendWithKeyboardNullMarkup() {
        sender.sendWithKeyboard(user, "Keyboard text", null);
        verify(producerTemplate)
                .sendBodyAndHeader(
                        eq("telegram:bots"), eq("Keyboard text"), eq(TelegramConstants.TELEGRAM_CHAT_ID), eq("123"));
    }

    @Test
    void testSendWithKeyboardNonNullMarkup() {
        InlineKeyboardMarkup markup = new InlineKeyboardMarkup();
        sender.sendWithKeyboard(user, "Keyboard text", markup);
        verify(producerTemplate)
                .sendBodyAndHeader(
                        eq("telegram:bots"),
                        any(OutgoingTextMessage.class),
                        eq(TelegramConstants.TELEGRAM_CHAT_ID),
                        eq("123"));
    }

    @Test
    void testLongTextChunking() {
        sender.maxLength = 20;
        sender.sendRaw(user, "This is a very long message that should be chunked into multiple pieces");
        verify(producerTemplate, times(4))
                .sendBodyAndHeader(eq("telegram:bots"), anyString(), eq(TelegramConstants.TELEGRAM_CHAT_ID), eq("123"));
    }

    @Test
    void testStripGarbledLines() {
        String garbled = "???...\nReal Line 1\n.....?????\nReal Line 2";
        String cleaned = TelegramChannelSender.stripGarbledLines(garbled);
        assertEquals("Real Line 1\nReal Line 2", cleaned);
    }

    @Test
    void testStripGarbledLinesPreservesMarkdownCodeFence() {
        String markdown = "```json\n...\n```";

        assertEquals(markdown, TelegramChannelSender.stripGarbledLines(markdown));
    }

    @Test
    void testHtmlArtifactCollapsedInSendRaw() {
        String html = "<!DOCTYPE html><html><head><title>Test</title></head><body><h1>Hello</h1></body></html>";
        sender.sendRaw(user, html);
        verify(producerTemplate)
                .sendBodyAndHeader(
                        eq("telegram:bots"),
                        eq("[HTML artifact generated - open the browser preview to view it.]"),
                        eq(TelegramConstants.TELEGRAM_CHAT_ID),
                        eq("123"));
    }

    @Test
    void nullRecipientIsDroppedWithoutDereferencingText() {
        assertDoesNotThrow(() -> sender.sendRaw(null, null));
        verifyNoInteractions(producerTemplate);
    }

    @Test
    void nullRecipientWithKeyboardIsDroppedBeforeMarkupHandling() {
        assertDoesNotThrow(() -> sender.sendWithKeyboard(null, "text", null));
        verifyNoInteractions(producerTemplate);
    }

    @Test
    void nonPositiveConfiguredLengthFallsBackToTelegramDefault() {
        sender.maxLength = 0;
        String text = "x".repeat(4097);

        sender.sendRaw(user, text);

        verify(producerTemplate, times(2))
                .sendBodyAndHeader(eq("telegram:bots"), anyString(), eq(TelegramConstants.TELEGRAM_CHAT_ID), eq("123"));
    }

    @Test
    void blankRecipientChannelIsDropped() {
        ChannelUser blankChannel = mock(ChannelUser.class);
        when(blankChannel.channelId()).thenReturn("   ");

        assertDoesNotThrow(() -> sender.sendRaw(blankChannel, "text"));
        verifyNoInteractions(producerTemplate);
    }
}
