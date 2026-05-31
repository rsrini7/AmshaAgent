package com.arcclaw.core.reflex;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import jakarta.inject.Inject;

import java.time.Instant;
import java.util.Optional;

import com.arcclaw.channel.api.ChannelMessage;
import com.arcclaw.channel.api.ChannelType;
import com.arcclaw.channel.api.ChannelUser;
import com.arcclaw.core.pipeline.MessageContext;
import com.arcclaw.core.security.SecretVault;
import com.arcclaw.core.security.VaultKey;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;

import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;

@QuarkusTest
@SuppressWarnings("java:S1192")
public class VaultStoreReflexTest {

    @Inject
    VaultStoreReflex reflex;

    @InjectMock
    SecretVault secretVault;

    @Test
    void testMatchesAnyVaultKeyPattern() {
        assertTrue(reflex.matches("vault ARCCLAW_TOOLS_GITHUB_TOKEN=abc"));
        assertTrue(reflex.matches("vault ARCCLAW_TOOLS_BRAVE_API_KEY=sk-123"));
        assertTrue(reflex.matches("VAULT some_random_key=value"), "Should match any key pattern to provide help");
    }

    @Test
    void testDoesNotMatchNonVaultPhrases() {
        assertFalse(reflex.matches("remember my name is srini"));
        assertFalse(reflex.matches("help me"));
    }

    @Test
    void testExecuteHandlesUnknownKeyHelpfully() {
        String input = "vault MY_BAD_KEY=secret-123";
        ChannelUser user = new ChannelUser("user-1", "chat-123", "User 1", ChannelType.CLI);
        ChannelMessage message = new ChannelMessage.CliMessage(user, input, "sess-1", Instant.now());
        MessageContext context = new MessageContext("chat-123", user, message);

        ReflexResult result = reflex.execute("sess-1", "user-1", "chat-123", input, context);

        assertTrue(
                result.commandResponse().get().contains("Unknown vault key"),
                "Should return helpful error for unknown key");
        assertTrue(
                result.commandResponse().get().contains("ARCCLAW_TOOLS_GITHUB_TOKEN"),
                "Error message should list known keys");
    }

    @Test
    void testExecuteStoresInVault() {
        String input = "vault ARCCLAW_TOOLS_GITHUB_TOKEN=secret-123";
        ChannelUser user = new ChannelUser("user-1", "chat-123", "User 1", ChannelType.CLI);
        ChannelMessage message = new ChannelMessage.CliMessage(user, input, "sess-1", Instant.now());
        MessageContext context = new MessageContext("chat-123", user, message);

        when(secretVault.retrieveSystemKey("ARCCLAW_TOOLS_GITHUB_TOKEN")).thenReturn(Optional.empty());

        ReflexResult result = reflex.execute("sess-1", "user-1", "chat-123", input, context);

        assertNotNull(result);
        assertTrue(result.commandResponse().isPresent());
        assertTrue(result.commandResponse().get().contains("ARCCLAW_TOOLS_GITHUB_TOKEN"));
        assertTrue(result.commandResponse().get().contains("stored"));

        verify(secretVault).storeSystemKey(eq("ARCCLAW_TOOLS_GITHUB_TOKEN"), ArgumentMatchers.any(byte[].class));
    }

    @Test
    void testOverwriteProtection() {
        String input = "vault ARCCLAW_TOOLS_GITHUB_TOKEN=new-secret";
        ChannelUser user = new ChannelUser("user-1", "chat-123", "User 1", ChannelType.CLI);
        ChannelMessage message = new ChannelMessage.CliMessage(user, input, "sess-1", Instant.now());
        MessageContext context = new MessageContext("chat-123", user, message);

        VaultKey mockEntry = mock(VaultKey.class);
        when(secretVault.retrieveSystemKey("ARCCLAW_TOOLS_GITHUB_TOKEN")).thenReturn(Optional.of(mockEntry));

        ReflexResult result = reflex.execute("sess-1", "user-1", "chat-123", input, context);

        assertTrue(result.commandResponse().get().contains("already exists"));
        assertTrue(result.commandResponse().get().contains("--force"));
    }

    @Test
    void testOverwriteWithForce() {
        String input = "vault ARCCLAW_TOOLS_GITHUB_TOKEN=new-secret --force";
        ChannelUser user = new ChannelUser("user-1", "chat-123", "User 1", ChannelType.CLI);
        ChannelMessage message = new ChannelMessage.CliMessage(user, input, "sess-1", Instant.now());
        MessageContext context = new MessageContext("chat-123", user, message);

        VaultKey mockEntry = mock(VaultKey.class);
        when(secretVault.retrieveSystemKey("ARCCLAW_TOOLS_GITHUB_TOKEN")).thenReturn(Optional.of(mockEntry));

        ReflexResult result = reflex.execute("sess-1", "user-1", "chat-123", input, context);

        assertTrue(result.commandResponse().get().contains("stored"));
        verify(secretVault).storeSystemKey(eq("ARCCLAW_TOOLS_GITHUB_TOKEN"), ArgumentMatchers.any(byte[].class));
    }
}
