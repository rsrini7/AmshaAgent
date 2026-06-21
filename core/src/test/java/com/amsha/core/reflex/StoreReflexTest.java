package com.amsha.core.reflex;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Instant;

import com.amsha.channel.api.ChannelMessage;
import com.amsha.channel.api.ChannelType;
import com.amsha.channel.api.ChannelUser;
import com.amsha.core.pipeline.MessageContext;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class StoreReflexTest {

    @InjectMocks
    StoreReflex reflex;

    private MessageContext context(String input) {
        ChannelUser user = new ChannelUser("user1", "chat1", "User 1", ChannelType.CLI);
        ChannelMessage msg = new ChannelMessage.CliMessage(user, input, "sess-1", Instant.now());
        return new MessageContext("chat1", user, msg);
    }

    @Test
    void matchesRecognizesStoreCommand() {
        assertTrue(reflex.matches("store FOO=bar"));
        assertTrue(reflex.matches("STORE key=value"));
        assertTrue(reflex.matches("Store Key=Value"));
    }

    @Test
    void matchesRecognizesRememberCommand() {
        assertTrue(reflex.matches("remember my name is Alice"));
        assertTrue(reflex.matches("REMEMBER something important"));
    }

    @Test
    void rejectsUnrelatedInput() {
        assertFalse(reflex.matches("/status"));
        assertFalse(reflex.matches("recall something"));
        assertFalse(reflex.matches(null));
    }

    @Test
    void executeParsesKeyValueAndFormatsStructuredGoal() {
        String input = "store API_KEY=sk-12345";
        ReflexResult result = reflex.execute("sess-1", "user1", "chat1", input, context(input));

        assertFalse(result.commandResponse().isPresent());
        String goal = result.sanitizedInput();
        assertTrue(goal.contains("API_KEY"), "Should contain the label");
        assertTrue(goal.contains("sk-12345"), "Should contain the value");
        assertTrue(goal.contains("Store the following"), "Should use structured format");
    }

    @Test
    void executeHandlesRememberWithoutEquals() {
        String input = "remember my favorite color is blue";
        ReflexResult result = reflex.execute("sess-1", "user1", "chat1", input, context(input));

        assertFalse(result.commandResponse().isPresent());
        String goal = result.sanitizedInput();
        assertTrue(goal.startsWith("Remember this information:"));
        assertTrue(goal.contains("my favorite color is blue"));
    }

    @Test
    void executeHandlesValueWithMultipleEquals() {
        String input = "store JWT=eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxMjM0NTY3ODkwIn0";
        ReflexResult result = reflex.execute("sess-1", "user1", "chat1", input, context(input));

        String goal = result.sanitizedInput();
        assertTrue(goal.contains("JWT"), "Should extract label before first equals");
        assertTrue(goal.contains("eyJhbGciOiJIUzI1NiJ9"), "Should preserve full value");
    }
}
