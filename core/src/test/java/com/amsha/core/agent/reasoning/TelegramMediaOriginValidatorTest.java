package com.amsha.core.agent.reasoning;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.util.List;
import java.util.Map;

import com.amsha.channel.api.AttachmentType;
import com.amsha.channel.api.MediaAttachment;
import com.amsha.core.agent.loop.AgentExecutionContext;
import com.amsha.llm.api.StructuredOutputMode;
import com.amsha.security.api.AutonomyLevel;

import org.junit.jupiter.api.Test;

class TelegramMediaOriginValidatorTest {

    private AgentExecutionContext createExecutionContext(
            String channelType, String userId, List<MediaAttachment> attachments) {
        return new AgentExecutionContext(
                "sess-1",
                userId,
                "chat-1",
                "researcher",
                "goal",
                AutonomyLevel.FULL,
                StructuredOutputMode.NATIVE,
                10,
                0,
                channelType,
                "123",
                null,
                Map.of(),
                attachments);
    }

    private MediaAttachment telegramAttachment(String uriString) {
        return MediaAttachment.ofUri(
                "att-1", AttachmentType.IMAGE, "image/png", "photo.png", 1024L, URI.create(uriString), Map.of());
    }

    @Test
    void happyPathValidTelegramFileUri() {
        AgentExecutionContext ctx = createExecutionContext(
                "TELEGRAM",
                "user-123",
                List.of(telegramAttachment("telegram://file/AgACAgIAAxkBAAI_valid_file_123-abc")));

        assertTrue(TelegramMediaOriginValidator.hasTrustedTelegramMediaOrigin(ctx));
    }

    @Test
    void nonTelegramChannelReturnsFalse() {
        AgentExecutionContext ctx = createExecutionContext(
                "WEB", "user-123", List.of(telegramAttachment("telegram://file/AgACAgIAAxkBAAI_valid_file_123-abc")));

        assertFalse(TelegramMediaOriginValidator.hasTrustedTelegramMediaOrigin(ctx));
    }

    @Test
    void genericUrlReturnsFalse() {
        AgentExecutionContext ctx = createExecutionContext(
                "TELEGRAM", "user-123", List.of(telegramAttachment("https://api.telegram.org/file/bot123/photo.jpg")));

        assertFalse(TelegramMediaOriginValidator.hasTrustedTelegramMediaOrigin(ctx));
    }

    @Test
    void mixedBatchFailsClosed() {
        MediaAttachment validTg = telegramAttachment("telegram://file/AgACAgIAAxkBAAI_valid");
        MediaAttachment genericUrl = telegramAttachment("https://evil.com/payload.png");

        AgentExecutionContext ctx = createExecutionContext("TELEGRAM", "user-123", List.of(validTg, genericUrl));

        assertFalse(
                TelegramMediaOriginValidator.hasTrustedTelegramMediaOrigin(ctx),
                "A mixed batch must fail closed to prevent privilege inheritance");
    }

    @Test
    void malformedTelegramUrisReturnFalse() {
        String[] malformedUris = {
            "telegram://file/", // empty file id
            "telegram://file", // no path
            "telegram://file/sub/path", // nested path
            "telegram://file/file$with*special", // invalid characters
            "telegram://file/file?param=1", // query present
            "telegram://file/file#section", // fragment present
            "telegram://file:8080/file1", // port present
            "http://file/file1", // invalid scheme
            "telegram://not-file/file1", // invalid host
            "telegram:file/file1" // opaque URI
        };

        for (String uri : malformedUris) {
            AgentExecutionContext ctx =
                    createExecutionContext("TELEGRAM", "user-123", List.of(telegramAttachment(uri)));
            assertFalse(
                    TelegramMediaOriginValidator.hasTrustedTelegramMediaOrigin(ctx),
                    "URI '" + uri + "' should be rejected as non-canonical");
        }
    }

    @Test
    void missingOrBlankUserIdReturnsFalse() {
        AgentExecutionContext nullUserCtx =
                createExecutionContext("TELEGRAM", null, List.of(telegramAttachment("telegram://file/valid_file")));
        assertFalse(TelegramMediaOriginValidator.hasTrustedTelegramMediaOrigin(nullUserCtx));

        AgentExecutionContext blankUserCtx =
                createExecutionContext("TELEGRAM", "   ", List.of(telegramAttachment("telegram://file/valid_file")));
        assertFalse(TelegramMediaOriginValidator.hasTrustedTelegramMediaOrigin(blankUserCtx));
    }

    @Test
    void nullContextOrEmptyAttachmentsReturnsFalse() {
        assertFalse(TelegramMediaOriginValidator.hasTrustedTelegramMediaOrigin(null));

        AgentExecutionContext emptyAttCtx = createExecutionContext("TELEGRAM", "user-123", List.of());
        assertFalse(TelegramMediaOriginValidator.hasTrustedTelegramMediaOrigin(emptyAttCtx));

        AgentExecutionContext nullAttCtx = createExecutionContext("TELEGRAM", "user-123", null);
        assertFalse(TelegramMediaOriginValidator.hasTrustedTelegramMediaOrigin(nullAttCtx));
    }

    @Test
    void inlineOnlyAttachmentWithoutSourceUriReturnsFalse() {
        MediaAttachment inlineAtt = MediaAttachment.ofInline(
                "inline-1", AttachmentType.IMAGE, "image/png", "photo.png", new byte[] {1, 2, 3}, null);

        AgentExecutionContext ctx = createExecutionContext("TELEGRAM", "user-123", List.of(inlineAtt));

        // Inline attachments without any telegram:// reference do not carry channel-origin pre-approval
        assertFalse(TelegramMediaOriginValidator.hasTrustedTelegramMediaOrigin(ctx));
    }
}
