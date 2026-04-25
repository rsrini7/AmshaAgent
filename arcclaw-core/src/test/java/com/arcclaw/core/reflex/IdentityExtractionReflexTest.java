package com.arcclaw.core.reflex;

import static org.mockito.Mockito.verify;

import java.time.Instant;

import jakarta.inject.Inject;

import com.arcclaw.channel.api.ChannelMessage;
import com.arcclaw.channel.api.ChannelType;
import com.arcclaw.channel.api.ChannelUser;
import com.arcclaw.core.pipeline.MessageContext;
import com.arcclaw.persistence.api.MemoryEntryDto;
import com.arcclaw.persistence.api.MemoryRetrievalPort;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

@QuarkusTest
public class IdentityExtractionReflexTest {

    @Inject
    IdentityExtractionReflex reflex;

    @InjectMock
    MemoryRetrievalPort memoryPort;

    private MessageContext createContext(String input) {
        ChannelUser user = new ChannelUser("user1", "user1", "user1", ChannelType.TELEGRAM);
        ChannelMessage msg = new ChannelMessage.CliMessage(user, input, "test-session", Instant.now());
        return new MessageContext("chat1", user, msg);
    }

    @Test
    void testMatches() {
        Assertions.assertTrue(reflex.matches("Hi, my name is Srini"));
        Assertions.assertTrue(reflex.matches("call me Bob"));
        Assertions.assertTrue(reflex.matches("I prefer dark mode"));
        Assertions.assertTrue(reflex.matches("I work at Acme Corp"));
        Assertions.assertTrue(reflex.matches("I am based in London"));
        Assertions.assertFalse(reflex.matches("What is the weather?"));
    }

    @Test
    void testExecute_Extraction() {
        String input = "Hello, my name is Alice";
        MessageContext context = createContext(input);
        
        ReflexResult result = reflex.execute("session1", "user1", "chat1", input, context);
        
        // It should forward to the LLM (no command response)
        Assertions.assertTrue(result.commandResponse().isEmpty());
        
        // Verify MemoryPort got called (with timeout since it's async)
        ArgumentCaptor<MemoryEntryDto> captor = ArgumentCaptor.forClass(MemoryEntryDto.class);
        verify(memoryPort, Mockito.timeout(5000)).index(captor.capture());
        
        MemoryEntryDto captured = captor.getValue();
        Assertions.assertEquals("The user's name is Alice.", captured.text());
        Assertions.assertEquals("user_name", captured.metadata().get("label"));
    }
}
