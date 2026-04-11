package com.arcclaw.core.reflex;

import jakarta.inject.Inject;

import com.arcclaw.core.pipeline.MessageContext;
import com.arcclaw.core.security.SecretVault;

import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;

@QuarkusTest
public class VaultStoreReflexTest {

    @Inject
    VaultStoreReflex reflex;

    @InjectMock
    SecretVault secretVault;

    @Test
    void testMatchesKnownSecrets() {
        assertTrue(reflex.matches("store GITHUB_TOKEN=abc"));
        assertTrue(reflex.matches("store ARCCLAW_TOOLS_BRAVE_API_KEY=sk-123"));
        assertTrue(reflex.matches("STORE ARCCLAW_TOOLS_BRAVE_API_KEY = xyz"));
    }

    @Test
    void testDoesNotMatchRandomWords() {
        assertFalse(reflex.matches("store some_random_key=value"));
        assertFalse(reflex.matches("remember my name is srini"));
        assertFalse(reflex.matches("help me"));
    }

    @Test
    void testExecuteStoresInVault() {
        String input = "store GITHUB_TOKEN=secret-123";
        com.arcclaw.channel.api.ChannelUser user = new com.arcclaw.channel.api.ChannelUser("user-1", "chat-123", "User 1", com.arcclaw.channel.api.ChannelType.CLI);
        com.arcclaw.channel.api.ChannelMessage message = new com.arcclaw.channel.api.ChannelMessage.CliMessage(user, input, "sess-1", java.time.Instant.now());
        MessageContext context = new MessageContext("chat-123", user, message);
        
        when(secretVault.retrieveSystemKey("GITHUB_TOKEN")).thenReturn(Optional.empty());

        ReflexResult result = reflex.execute("sess-1", "user-1", "chat-123", input, context);
        
        assertNotNull(result);
        assertTrue(result.commandResponse().isPresent());
        assertTrue(result.commandResponse().get().contains("GITHUB_TOKEN"));
        assertTrue(result.commandResponse().get().contains("stored"));
        
        verify(secretVault).storeSystemKey(eq("GITHUB_TOKEN"), ArgumentMatchers.any(byte[].class));
    }

    @Test
    void testOverwriteProtection() {
        String input = "store GITHUB_TOKEN=new-secret";
        com.arcclaw.channel.api.ChannelUser user = new com.arcclaw.channel.api.ChannelUser("user-1", "chat-123", "User 1", com.arcclaw.channel.api.ChannelType.CLI);
        com.arcclaw.channel.api.ChannelMessage message = new com.arcclaw.channel.api.ChannelMessage.CliMessage(user, input, "sess-1", java.time.Instant.now());
        MessageContext context = new MessageContext("chat-123", user, message);
        
        com.arcclaw.core.security.VaultKey mockEntry = mock(com.arcclaw.core.security.VaultKey.class);
        when(secretVault.retrieveSystemKey("GITHUB_TOKEN")).thenReturn(Optional.of(mockEntry));

        ReflexResult result = reflex.execute("sess-1", "user-1", "chat-123", input, context);
        
        assertTrue(result.commandResponse().get().contains("already exists"));
        assertTrue(result.commandResponse().get().contains("--force"));
    }

    @Test
    void testOverwriteWithForce() {
        String input = "store GITHUB_TOKEN=new-secret --force";
        com.arcclaw.channel.api.ChannelUser user = new com.arcclaw.channel.api.ChannelUser("user-1", "chat-123", "User 1", com.arcclaw.channel.api.ChannelType.CLI);
        com.arcclaw.channel.api.ChannelMessage message = new com.arcclaw.channel.api.ChannelMessage.CliMessage(user, input, "sess-1", java.time.Instant.now());
        MessageContext context = new MessageContext("chat-123", user, message);
        
        com.arcclaw.core.security.VaultKey mockEntry = mock(com.arcclaw.core.security.VaultKey.class);
        when(secretVault.retrieveSystemKey("GITHUB_TOKEN")).thenReturn(Optional.of(mockEntry));

        ReflexResult result = reflex.execute("sess-1", "user-1", "chat-123", input, context);
        
        assertTrue(result.commandResponse().get().contains("stored"));
        verify(secretVault).storeSystemKey(eq("GITHUB_TOKEN"), ArgumentMatchers.any(byte[].class));
    }
}
