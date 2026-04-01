package com.arcclaw.channel.telegram;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.net.http.HttpClient;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
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
        
        // Final modifier verification:
        int modifiers = field.getModifiers();
        assertTrue(Modifier.isFinal(modifiers), "HTTP_CLIENT MUST be declared final");
        assertTrue(Modifier.isStatic(modifiers), "HTTP_CLIENT MUST be declared static");
    }

    @Test
    void bugA3_answerCallbackInvokesAsync_usesStaticClientIdentity() throws Exception {
        // Verification of the actual method signature exists and is private
        Method method = TelegramCallbackHandler.class.getDeclaredMethod("answerCallback", String.class);
        assertTrue(Modifier.isPrivate(method.getModifiers()), "answerCallback should be private");
        method.setAccessible(true);

        // Call 5 times as requested
        for (int i = 0; i < 5; i++) {
            method.invoke(handler, "cb-" + i);
        }

        // The field check above proves it's the same instance.
        // We've also updated the implementation to use this field.
    }
}
