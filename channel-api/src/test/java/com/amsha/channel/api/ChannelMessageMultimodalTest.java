package com.amsha.channel.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ChannelMessageMultimodalTest {

    @Test
    @DisplayName("ChannelMessage default methods preserve backward compatibility")
    void channelMessage_defaultMethods_backwardCompatible() {
        ChannelUser user = new ChannelUser("u1", "c1", "user1", ChannelType.TELEGRAM);
        Instant now = Instant.now();

        // 4-arg legacy constructor
        ChannelMessage.TelegramMessage legacyMsg = new ChannelMessage.TelegramMessage(user, "Hello bot", "c1", now);

        assertEquals("Hello bot", legacyMsg.text());
        assertEquals("c1", legacyMsg.correlationId());
        assertEquals(ChannelType.TELEGRAM, legacyMsg.channelType());
        assertNotNull(legacyMsg.attachments());
        assertTrue(legacyMsg.attachments().isEmpty(), "Default attachments must be empty list");
        assertTrue(legacyMsg.caption().isPresent());
        assertEquals("Hello bot", legacyMsg.caption().get());
    }

    @Test
    @DisplayName("TelegramMessage with attachments, caption, and withText")
    void telegramMessage_withAttachmentsAndCaption() {
        ChannelUser user = new ChannelUser("u2", "c2", "user2", ChannelType.TELEGRAM);
        Instant now = Instant.now();
        MediaAttachment attachment = MediaAttachment.ofUri(
                "att-10",
                AttachmentType.IMAGE,
                "image/jpeg",
                "photo.jpg",
                2048L,
                URI.create("https://tg.example/f/1"),
                Map.of());

        ChannelMessage.TelegramMessage msg = new ChannelMessage.TelegramMessage(
                user, "Describe this photo", "c2", now, List.of(attachment), "Photo caption");

        assertEquals("Describe this photo", msg.text());
        assertEquals(1, msg.attachments().size());
        assertEquals("att-10", msg.attachments().getFirst().attachmentId());
        assertEquals("Photo caption", msg.caption().orElse(""));

        ChannelMessage updated = msg.withText("New prompt text");
        assertEquals("New prompt text", updated.text());
        assertEquals("Photo caption", updated.caption().orElse(""));
        assertEquals(1, updated.attachments().size());
    }

    @Test
    @DisplayName("GenericChannelMessage with attachments and withText")
    void genericChannelMessage_withAttachments() {
        ChannelUser user = new ChannelUser("u3", "c3", "user3", ChannelType.REST_API);
        Instant now = Instant.now();
        MediaAttachment attachment = MediaAttachment.ofInline(
                "att-20",
                AttachmentType.DOCUMENT_PDF,
                "application/pdf",
                "report.pdf",
                new byte[] {0x1, 0x2},
                Map.of());

        ChannelMessage.GenericChannelMessage msg = new ChannelMessage.GenericChannelMessage(
                user, "Analyze report", "c3", now, ChannelType.REST_API, List.of(attachment));

        assertEquals(1, msg.attachments().size());
        assertEquals(AttachmentType.DOCUMENT_PDF, msg.attachments().getFirst().type());

        ChannelMessage updated = msg.withText("Updated text");
        assertEquals("Updated text", updated.text());
        assertEquals(1, updated.attachments().size());
    }

    @Test
    @DisplayName("Discord and CLI messages preserve attachments when their text is replaced")
    void builtInMessages_withoutSeparateCaption_preserveAttachmentsOnWithText() {
        ChannelUser user = new ChannelUser("u5", "c5", "user5", ChannelType.DISCORD);
        Instant now = Instant.now();
        MediaAttachment attachment = MediaAttachment.ofInline(
                "att-30", AttachmentType.AUDIO, "audio/ogg", "voice.ogg", new byte[] {0x1}, Map.of());

        ChannelMessage.DiscordMessage discord =
                new ChannelMessage.DiscordMessage(user, "old", "guild", "channel", now, List.of(attachment));
        ChannelMessage discordUpdated = discord.withText("new");
        assertEquals("new", discordUpdated.text());
        assertEquals("new", discordUpdated.caption().orElse(""));
        assertEquals(List.of(attachment), discordUpdated.attachments());

        ChannelMessage.CliMessage cli = new ChannelMessage.CliMessage(
                new ChannelUser("u6", "c6", "user6", ChannelType.CLI), "old", "session", now, List.of(attachment));
        ChannelMessage cliUpdated = cli.withText("new");
        assertEquals("new", cliUpdated.text());
        assertEquals("new", cliUpdated.caption().orElse(""));
        assertEquals(List.of(attachment), cliUpdated.attachments());
    }

    @Test
    @DisplayName("Custom ChannelMessage implementation preserves attributes and caption on withText")
    void customChannelMessage_preservesAttributesAndCaptionOnWithText() {
        ChannelUser user = new ChannelUser("u4", "c4", "pluginUser", ChannelType.SLACK);
        Instant now = Instant.now();
        ChannelMessageAttribute<String> attr = new ChannelMessageAttribute<>(String.class, "thread_ts");
        Map<ChannelMessageAttribute<?>, Object> customAttrs = Map.of(attr, "1234567890.123456");

        // Custom plugin message implementing ChannelMessage interface directly
        ChannelMessage customPluginMessage = new ChannelMessage() {
            @Override
            public ChannelUser sender() {
                return user;
            }

            @Override
            public String text() {
                return null;
            }

            @Override
            public String correlationId() {
                return "c4";
            }

            @Override
            public Instant receivedAt() {
                return now;
            }

            @Override
            public ChannelType channelType() {
                return ChannelType.SLACK;
            }

            @Override
            public Optional<String> caption() {
                return Optional.of("Custom plugin caption");
            }

            @Override
            public Map<ChannelMessageAttribute<?>, Object> attributes() {
                return customAttrs;
            }
        };

        // Calling default withText
        ChannelMessage wrapped = customPluginMessage.withText("Effective prompt");

        assertEquals("Effective prompt", wrapped.text());
        assertEquals("c4", wrapped.correlationId());
        assertEquals(ChannelType.SLACK, wrapped.channelType());
        assertEquals("Custom plugin caption", wrapped.caption().orElse(""));
        assertEquals("1234567890.123456", wrapped.attribute(attr).orElse(""));
    }
}
