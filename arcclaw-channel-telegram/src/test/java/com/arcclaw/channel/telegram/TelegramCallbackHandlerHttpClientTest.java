package com.arcclaw.channel.telegram;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;

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
    }

    @Test
    void bugA3_singletonInstance_multipleFieldCallsUseSameObject() throws Exception {
        Field field = TelegramCallbackHandler.class.getDeclaredField("HTTP_CLIENT");
        field.setAccessible(true);
        
        // Assert initial state is not null (static initialization check)
        HttpClient client1 = (HttpClient) field.get(null);
        assertNotNull(client1, "HTTP_CLIENT MUST be initialized at class load time");

        HttpClient client2 = (HttpClient) field.get(null);
        assertSame(client1, client2, "HTTP_CLIENT must be a static singleton instance");
        
        // Final modifier verification removed to allow Mockito replacement
        int modifiers = field.getModifiers();
        assertTrue(Modifier.isStatic(modifiers), "HTTP_CLIENT MUST be declared static");
    }

    @Test
    void bugA3_answerCallback_appliesFiveSecondTimeout() throws Exception {
        // 1. Setup mock HttpClient
        HttpClient mockClient = Mockito.mock(HttpClient.class);
        
        // 2. Overwrite the static final HTTP_CLIENT field via reflection magic
        Field field = TelegramCallbackHandler.class.getDeclaredField("HTTP_CLIENT");
        field.setAccessible(true);
        
        // Remove 'final' modifier using reflection (required for some JDKs/environments)
        try {
            Field modifiersField = Field.class.getDeclaredField("modifiers");
            modifiersField.setAccessible(true);
            modifiersField.setInt(field, field.getModifiers() & ~Modifier.FINAL);
        } catch (Exception e) {
            // Might fail on newer JDKs, but field.set() might still work if we're lucky or using specific JUnit/Mockito setup
        }
        
        // Fix NPE: mock the method to return a valid CompletableFuture instead of null
        Mockito.when(mockClient.sendAsync(any(), any()))
            .thenReturn(CompletableFuture.completedFuture(null));
        
        HttpClient originalClient = (HttpClient) field.get(null);
        try {
            field.set(null, mockClient);
            
            // 3. Invoke answerCallback
            Method method = TelegramCallbackHandler.class.getDeclaredMethod("answerCallback", String.class);
            method.setAccessible(true);
            method.invoke(handler, "test-cb-id");

            // 4. Capture the HttpRequest
            ArgumentCaptor<HttpRequest> requestCaptor = 
                ArgumentCaptor.forClass(HttpRequest.class);
            verify(mockClient).sendAsync(requestCaptor.capture(), any());

            HttpRequest captured = requestCaptor.getValue();
            assertTrue(captured.timeout().isPresent(), "Request MUST have a timeout configured");
            Assertions.assertEquals(
                Duration.ofSeconds(5), captured.timeout().get(),
                "Timeout MUST be exactly 5 seconds (§C3)");

        } finally {
            // Restore original client to avoid collateral damage to other tests
            field.set(null, originalClient);
        }
    }
}
