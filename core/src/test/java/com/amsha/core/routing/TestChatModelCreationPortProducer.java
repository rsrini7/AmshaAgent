package com.amsha.core.routing;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import dev.langchain4j.model.chat.ChatModel;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;

import com.amsha.llm.api.ChatModelCreationPort;

import io.quarkus.test.Mock;

/**
 * Provides a mock {@link ChatModelCreationPort} for {@code amsha-core} Quarkus test contexts,
 * where concrete provider SDK implementations reside in the sibling {@code amsha-llm-providers} module.
 */
@ApplicationScoped
public class TestChatModelCreationPortProducer {

    @Produces
    @Mock
    @ApplicationScoped
    public ChatModelCreationPort chatModelCreationPort() {
        ChatModelCreationPort port = mock(ChatModelCreationPort.class);
        ChatModel mockModel = mock(ChatModel.class);
        when(port.createForAdapter(any(), any(), any(), any(), any())).thenReturn(mockModel);
        when(port.supportsAdapter(anyString())).thenReturn(true);
        return port;
    }
}
