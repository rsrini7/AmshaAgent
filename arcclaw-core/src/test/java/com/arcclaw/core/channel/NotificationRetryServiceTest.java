package com.arcclaw.core.channel;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.List;
import java.util.Optional;

import com.arcclaw.channel.api.ChannelPlugin;
import com.arcclaw.channel.api.ChannelSender;
import com.arcclaw.channel.api.ChannelType;
import com.arcclaw.channel.api.ChannelUser;
import com.arcclaw.channel.api.ContentNode.Text;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;

class NotificationRetryServiceTest {

    private NotificationRetryService service;
    private ChannelPluginRegistry registry;
    private MeterRegistry meterRegistry;
    private Counter counter;
    private ChannelPlugin mockPlugin;
    private ChannelSender mockSender;
    private ChannelUser recipient;

    @BeforeEach
    void setUp() {
        service = new NotificationRetryService();
        registry = mock(ChannelPluginRegistry.class);
        meterRegistry = mock(MeterRegistry.class);
        counter = mock(Counter.class);
        mockPlugin = mock(ChannelPlugin.class);
        mockSender = mock(ChannelSender.class);
        recipient = new ChannelUser("p1", "telegram", "user", ChannelType.TELEGRAM);

        service.registry = registry;
        service.meterRegistry = meterRegistry;
        service.maxAttempts = 3;
        service.initialDelay = 10; // short delay for tests

        when(meterRegistry.counter(anyString(), anyString(), anyString())).thenReturn(counter);
    }

    @Test
    void testSendRawWithRetrySuccessOnFirstAttempt() {
        when(registry.find("telegram")).thenReturn(Optional.of(mockPlugin));
        when(mockPlugin.sender()).thenReturn(mockSender);

        service.sendRawWithRetry(recipient, "Hello");

        verify(mockSender).sendRaw(recipient, "Hello");
        verifyNoMoreInteractions(counter);
    }

    @Test
    void testSendRawWithRetrySuccessAfterRetry() {
        when(registry.find("telegram")).thenReturn(Optional.of(mockPlugin));
        when(mockPlugin.sender()).thenReturn(mockSender);
        doThrow(new RuntimeException("First attempt failed"))
                .doNothing()
                .when(mockSender)
                .sendRaw(recipient, "Hello");

        service.sendRawWithRetry(recipient, "Hello");

        verify(mockSender, times(2)).sendRaw(recipient, "Hello");
        verifyNoMoreInteractions(counter);
    }

    @Test
    void testSendRawWithRetryExhaustionIncrementsCounter() {
        when(registry.find("telegram")).thenReturn(Optional.of(mockPlugin));
        when(mockPlugin.sender()).thenReturn(mockSender);
        doThrow(new RuntimeException("Always fails")).when(mockSender).sendRaw(recipient, "Hello");

        service.sendRawWithRetry(recipient, "Hello");

        verify(mockSender, times(3)).sendRaw(recipient, "Hello");
        verify(meterRegistry).counter("arcclaw.notification.retry.exhausted", "channel", "telegram");
        verify(counter).increment();
    }

    @Test
    void testSendRawWithRetryNoSenderReturnsImmediately() {
        when(registry.find("telegram")).thenReturn(Optional.empty());

        service.sendRawWithRetry(recipient, "Hello");

        verify(mockSender, never()).sendRaw(any(), anyString());
        verifyNoMoreInteractions(counter);
    }

    @Test
    void testSendWithRetrySuccessOnFirstAttempt() {
        when(registry.find("telegram")).thenReturn(Optional.of(mockPlugin));
        when(mockPlugin.sender()).thenReturn(mockSender);

        service.sendWithRetry(recipient, List.of(new Text("test")));

        verify(mockSender).send(eq(recipient), anyList());
        verifyNoMoreInteractions(counter);
    }

    @Test
    void testSendWithRetryExhaustionIncrementsCounter() {
        when(registry.find("telegram")).thenReturn(Optional.of(mockPlugin));
        when(mockPlugin.sender()).thenReturn(mockSender);
        doThrow(new RuntimeException("Always fails")).when(mockSender).send(any(), anyList());

        service.sendWithRetry(recipient, List.of(new Text("test")));

        verify(mockSender, times(3)).send(any(), anyList());
        verify(counter).increment();
    }

    @Test
    void testSendWithRetryNoSenderReturnsImmediately() {
        when(registry.find("telegram")).thenReturn(Optional.empty());

        service.sendWithRetry(recipient, List.of(new Text("test")));

        verify(mockSender, never()).send(any(), anyList());
        verifyNoMoreInteractions(counter);
    }

    @Test
    void testSendRawWithRetryInterruptedExceptionDuringSleep() {
        when(registry.find("telegram")).thenReturn(Optional.of(mockPlugin));
        when(mockPlugin.sender()).thenReturn(mockSender);
        doThrow(new RuntimeException("Fail")).when(mockSender).sendRaw(recipient, "Hello");

        service.maxAttempts = 2;
        service.initialDelay = 10000;

        Thread.currentThread().interrupt();
        service.sendRawWithRetry(recipient, "Hello");
        assertTrue(Thread.interrupted());
    }
}
