package com.arcclaw.app.channel.telegram;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import org.apache.camel.ProducerTemplate;
import org.jboss.logging.Logger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.arcclaw.channel.api.ChannelType;
import com.arcclaw.channel.api.ChannelUser;

class TelegramChannelSenderTest {

    private ProducerTemplate producerTemplate;
    private Logger log;
    private TelegramChannelSender sender;

    @BeforeEach
    void setUp() {
        producerTemplate = mock(ProducerTemplate.class);
        log = mock(Logger.class);
        sender = new TelegramChannelSender(producerTemplate, log);
    }

    @Test
    void testSenderEndpointUriIsClean() {
        ChannelUser recipient = new ChannelUser("123456789", "testuser", ChannelType.TELEGRAM);
        sender.sendRaw(recipient, "Hello, world!");

        ArgumentCaptor<String> endpointCaptor = ArgumentCaptor.forClass(String.class);
        verify(producerTemplate).sendBodyAndHeader(
                endpointCaptor.capture(),
                eq("Hello, world!"),
                eq("CamelTelegramChatId"),
                eq("123456789")
        );

        assertEquals("telegram:bots", endpointCaptor.getValue(), "Endpoint URI must be exactly 'telegram:bots'");
    }
}
