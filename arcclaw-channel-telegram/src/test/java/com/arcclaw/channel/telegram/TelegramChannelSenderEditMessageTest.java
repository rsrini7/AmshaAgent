package com.arcclaw.channel.telegram;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;

import org.apache.camel.ProducerTemplate;
import org.apache.camel.component.telegram.model.EditMessageTextMessage;
import org.apache.camel.component.telegram.model.InlineKeyboardMarkup;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class TelegramChannelSenderEditMessageTest {

    @Mock
    ProducerTemplate producerTemplate;

    @Mock
    TelegramMarkdownV2Renderer renderer;

    @InjectMocks
    TelegramChannelSender sender;

    @BeforeEach
    void setUp() {
        sender.maxLength = 4096;
    }

    @Test
    void testEditMessage_sendsEditMessageTextMessage() {
        InlineKeyboardMarkup markup = new InlineKeyboardMarkup();
        sender.editMessage("123", 456, "new text", markup);

        ArgumentCaptor<EditMessageTextMessage> captor = ArgumentCaptor.forClass(EditMessageTextMessage.class);
        verify(producerTemplate)
                .sendBodyAndHeader(
                        eq("telegram:bots"),
                        captor.capture(),
                        eq("CamelTelegramChatId"), // matches TelegramConstants.TELEGRAM_CHAT_ID value
                        eq("123"));

        EditMessageTextMessage edit = captor.getValue();
        assertEquals("123", edit.getChatId());
        assertEquals(456, (int) edit.getMessageId());
        assertEquals("new text", edit.getText());
        assertEquals(markup, edit.getReplyMarkup());
    }

    @Test
    void testEditMessage_withNullMarkup_defaultsToEmptyMarkup() {
        sender.editMessage("123", 456, "removed", null);

        ArgumentCaptor<EditMessageTextMessage> captor = ArgumentCaptor.forClass(EditMessageTextMessage.class);
        verify(producerTemplate)
                .sendBodyAndHeader(eq("telegram:bots"), captor.capture(), eq("CamelTelegramChatId"), eq("123"));

        EditMessageTextMessage edit = captor.getValue();
        assertNotNull(edit.getReplyMarkup(), "Reply markup should be set even if passed as null");
    }
}
