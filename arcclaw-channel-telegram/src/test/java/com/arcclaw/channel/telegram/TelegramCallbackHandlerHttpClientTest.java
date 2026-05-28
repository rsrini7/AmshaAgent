package com.arcclaw.channel.telegram;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class TelegramCallbackHandlerHttpClientTest {

    @InjectMocks
    TelegramCallbackHandler handler;

    @BeforeEach
    void setup() throws Exception {
        // Manually set clinical dependencies that are non-mockable via InjectMocks
        Field tokenField = TelegramCallbackHandler.class.getDeclaredField("botToken");
        tokenField.setAccessible(true);
        tokenField.set(handler, "dummy-token");

        Field mapperField = TelegramCallbackHandler.class.getDeclaredField("objectMapper");
        mapperField.setAccessible(true);
        mapperField.set(handler, new ObjectMapper());
    }

    @Test
    void bugA3_singletonInstance_multipleFieldCallsUseSameObject() throws Exception {
        Field field = TelegramCallbackHandler.class.getDeclaredField("httpClient");
        field.setAccessible(true);

        // Assert initial state is not null
        HttpClient client1 = (HttpClient) field.get(handler);
        assertNotNull(client1, "httpClient MUST be initialized");

        HttpClient client2 = (HttpClient) field.get(handler);
        assertSame(client1, client2, "httpClient must be a stable instance on the bean");
    }

    @Test
    void bugA3_answerCallback_appliesFiveSecondTimeout() throws Exception {
        // 1. Setup mock HttpClient
        HttpClient mockClient = Mockito.mock(HttpClient.class);

        // 2. Overwrite the instance httpClient field
        Field field = TelegramCallbackHandler.class.getDeclaredField("httpClient");
        field.setAccessible(true);

        // mock the method to return a valid CompletableFuture instead of null
        Mockito.when(mockClient.sendAsync(any(), any())).thenReturn(CompletableFuture.completedFuture(null));

        HttpClient originalClient = (HttpClient) field.get(handler);
        try {
            field.set(handler, mockClient);

            // 3. Invoke answerCallback
            Method method = TelegramCallbackHandler.class.getDeclaredMethod("answerCallback", String.class);
            method.setAccessible(true);
            method.invoke(handler, "test-cb-id");

            // 4. Capture the HttpRequest
            ArgumentCaptor<HttpRequest> requestCaptor = ArgumentCaptor.forClass(HttpRequest.class);
            verify(mockClient).sendAsync(requestCaptor.capture(), any());

            HttpRequest captured = requestCaptor.getValue();
            assertTrue(captured.timeout().isPresent(), "Request MUST have a timeout configured");
            Assertions.assertEquals(
                    Duration.ofSeconds(5), captured.timeout().get(), "Timeout MUST be exactly 5 seconds (§C3)");

        } finally {
            // Restore original client to avoid collateral damage to other tests
            field.set(handler, originalClient);
        }
    }
}
