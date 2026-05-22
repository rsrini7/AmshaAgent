package com.arcclaw.channel.telegram;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.List;

import com.arcclaw.channel.api.ChannelType;
import com.arcclaw.channel.api.ChannelUser;
import com.arcclaw.channel.api.ContentNode;
import com.arcclaw.channel.api.ReplyNode;
import org.apache.camel.ProducerTemplate;
import org.jboss.logging.Logger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class TelegramChannelSenderTest {

  @Mock ProducerTemplate producerTemplate;
  @Mock TelegramMarkdownV2Renderer renderer;
  @Mock Logger log;
  @InjectMocks TelegramChannelSender sender;  // uses constructor injection

  private final ChannelUser user =
      new ChannelUser("123", "123", "alice", ChannelType.TELEGRAM);

  @BeforeEach
  void setUp() {
      sender.maxLength = 4096;
  }

  @Test
  void testSendWithNodesCallsSendRaw() {
      when(renderer.render(any())).thenReturn("Hello");
      // Use a non-empty list to avoid the immediate fallback
      sender.send(user, List.of(new ContentNode.Text("hi")));
      
      verify(producerTemplate).sendBodyAndHeader(
          eq("telegram:bots"),
          eq("Hello"),
          eq(TelegramConstants.TELEGRAM_CHAT_ID),
          eq("123"));
  }

  @Test
  void testSendWithEmptyNodesDeliversFallback() {
      sender.send(user, List.of());
      verify(producerTemplate).sendBodyAndHeader(
          eq("telegram:bots"),
          eq("(No response generated)"),
          eq(TelegramConstants.TELEGRAM_CHAT_ID),
          eq("123"));
  }

  @Test
  void testSendWithNullNodesDeliversFallback() {
      sender.send(user, null);
      verify(producerTemplate).sendBodyAndHeader(
          eq("telegram:bots"),
          eq("(No response generated)"),
          eq(TelegramConstants.TELEGRAM_CHAT_ID),
          eq("123"));
      // Must not throw NullPointerException
  }

  @Test
  void testSendWithNullRecipientDoesNotThrow() {
      assertDoesNotThrow(() -> sender.send(null, List.of()));
      verifyNoInteractions(producerTemplate);
  }


  @Test
  void testSenderEndpointUriIsClean() {
      when(renderer.render(any())).thenReturn("hi");
      sender.send(user, List.of(new ContentNode.Text("hi")));
      ArgumentCaptor<String> uriCaptor = ArgumentCaptor.forClass(String.class);
      verify(producerTemplate).sendBodyAndHeader(
          uriCaptor.capture(), any(), any(), any());
      assertEquals("telegram:bots", uriCaptor.getValue(),
          "Sender URI must be exactly 'telegram:bots' with no query params");
  }
}
