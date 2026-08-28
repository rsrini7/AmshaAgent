package com.amsha.llm.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.langchain4j.data.image.Image;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.Content;
import dev.langchain4j.data.message.ImageContent;
import dev.langchain4j.data.message.PdfFileContent;
import dev.langchain4j.data.message.TextContent;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.data.pdf.PdfFile;
import dev.langchain4j.model.chat.request.ChatRequest;

import java.net.URI;
import java.util.Base64;
import java.util.List;
import java.util.Set;

import com.amsha.channel.api.AttachmentType;

import org.junit.jupiter.api.Test;

class MultimodalRequestsTest {

    @Test
    void hasMultimodalContentReturnsFalseForTextOnlyRequests() {
        ChatRequest req1 = ChatRequest.builder()
                .messages(List.of(UserMessage.from("hello world")))
                .build();
        assertFalse(MultimodalRequests.hasMultimodalContent(req1));

        assertFalse(MultimodalRequests.hasMultimodalContent(null));
    }

    @Test
    void hasMultimodalContentReturnsTrueWhenMediaContentPresent() {
        UserMessage multiMsg = UserMessage.from(
                TextContent.from("look at this"),
                ImageContent.from(Image.builder()
                        .mimeType("image/png")
                        .url(URI.create("https://example.com/test.png"))
                        .build()));
        ChatRequest req = ChatRequest.builder().messages(List.of(multiMsg)).build();
        assertTrue(MultimodalRequests.hasMultimodalContent(req));
    }

    @Test
    void forCandidateReturnsOriginalRequestWhenCandidateSupportsAllModalities() {
        UserMessage multiMsg = UserMessage.from(
                TextContent.from("look at this"),
                ImageContent.from(Image.builder()
                        .mimeType("image/png")
                        .base64Data(Base64.getEncoder().encodeToString(new byte[] {1, 2, 3}))
                        .build()));
        ChatRequest req = ChatRequest.builder().messages(List.of(multiMsg)).build();

        MultimodalCapability visionCap = new MultimodalCapability(
                Set.of(AttachmentType.IMAGE), Set.of("image/png"), 10_000_000L, 10, true, true);

        ChatRequest result = MultimodalRequests.forCandidate(req, visionCap, "gpt-4o");
        assertSame(req, result);
    }

    @Test
    void forCandidateDegradesMalformedBase64Content() {
        UserMessage multiMsg = UserMessage.from(
                TextContent.from("look at this"),
                ImageContent.from(Image.builder()
                        .mimeType("image/png")
                        .base64Data("not-valid-base64$")
                        .build()));
        ChatRequest req = ChatRequest.builder().messages(List.of(multiMsg)).build();
        MultimodalCapability visionCap =
                new MultimodalCapability(Set.of(AttachmentType.IMAGE), Set.of("image/png"), 10_000L, 4, true, false);

        UserMessage rewritten = (UserMessage) MultimodalRequests.forCandidate(req, visionCap, "vision-model")
                .messages()
                .getFirst();

        assertTrue(rewritten.hasSingleText());
        assertTrue(rewritten.singleText().contains("does not support vision modality"));
    }

    @Test
    void forCandidateAcceptsUnpaddedValidBase64Content() {
        UserMessage multiMsg = UserMessage.from(
                TextContent.from("look at this"),
                ImageContent.from(
                        Image.builder().mimeType("image/png").base64Data("AQI").build()));
        ChatRequest req = ChatRequest.builder().messages(List.of(multiMsg)).build();
        MultimodalCapability visionCap =
                new MultimodalCapability(Set.of(AttachmentType.IMAGE), Set.of("image/png"), 3L, 4, true, false);

        assertSame(req, MultimodalRequests.forCandidate(req, visionCap, "vision-model"));
    }

    @Test
    void forCandidateNeverForwardsUnmaterializedUrl() {
        UserMessage multiMsg = UserMessage.from(
                TextContent.from("look at this"),
                ImageContent.from(Image.builder()
                        .mimeType("image/png")
                        .url(URI.create("https://example.com/test.png"))
                        .build()));
        ChatRequest req = ChatRequest.builder().messages(List.of(multiMsg)).build();
        MultimodalCapability visionCap = new MultimodalCapability(
                Set.of(AttachmentType.IMAGE), Set.of("image/png"), 10_000_000L, 10, true, true);

        ChatRequest result = MultimodalRequests.forCandidate(req, visionCap, "gpt-4o");

        assertNotSame(req, result);
        UserMessage rewritten = (UserMessage) result.messages().getFirst();
        assertTrue(rewritten.hasSingleText());
        assertTrue(rewritten.singleText().contains("does not support vision modality"));
    }

    @Test
    void forCandidateDowngradesMediaBeyondCandidateLimit() {
        String first = Base64.getEncoder().encodeToString(new byte[] {1, 2, 3});
        String second = Base64.getEncoder().encodeToString(new byte[] {4, 5, 6});
        UserMessage multiMsg = UserMessage.from(
                TextContent.from("compare"),
                ImageContent.from(
                        Image.builder().mimeType("image/png").base64Data(first).build()),
                ImageContent.from(
                        Image.builder().mimeType("image/png").base64Data(second).build()));
        ChatRequest req = ChatRequest.builder().messages(List.of(multiMsg)).build();
        MultimodalCapability oneImage = new MultimodalCapability(
                Set.of(AttachmentType.IMAGE), Set.of("image/png"), 10_000_000L, 1, true, false);

        UserMessage rewritten = (UserMessage) MultimodalRequests.forCandidate(req, oneImage, "gpt-4o")
                .messages()
                .getFirst();

        assertEquals(3, rewritten.contents().size());
        assertTrue(((TextContent) rewritten.contents().get(2)).text().contains("media item limit is 1"));
    }

    @Test
    void forCandidateRewritesUnsupportedContentForTextOnlyCandidate() {
        UserMessage multiMsg = UserMessage.from(
                TextContent.from("look at this"),
                ImageContent.from(Image.builder()
                        .mimeType("image/png")
                        .url(URI.create("https://example.com/test.png"))
                        .build()),
                PdfFileContent.from(PdfFile.builder()
                        .url(URI.create("https://example.com/test.pdf"))
                        .build()));
        ChatRequest req = ChatRequest.builder().messages(List.of(multiMsg)).build();

        ChatRequest sanitized = MultimodalRequests.forCandidate(req, MultimodalCapability.TEXT_ONLY, "gpt-3.5-turbo");
        assertNotNull(sanitized);

        List<ChatMessage> messages = sanitized.messages();
        assertEquals(1, messages.size());
        UserMessage userMsg = (UserMessage) messages.get(0);

        List<Content> contents = userMsg.contents();
        assertEquals(1, contents.size());
        String text = ((TextContent) contents.get(0)).text();
        assertTrue(text.contains("look at this"));
        assertTrue(text.contains("Model gpt-3.5-turbo does not support vision modality"));
        assertTrue(text.contains("Model gpt-3.5-turbo does not support native PDF modality"));
    }

    @Test
    void withoutMultimodalContentConvertsAllNonTextToPlaceholders() {
        UserMessage multiMsg = UserMessage.from(
                TextContent.from("analyze"),
                ImageContent.from(Image.builder()
                        .mimeType("image/jpeg")
                        .url(URI.create("https://example.com/test.jpg"))
                        .build()));
        ChatRequest req = ChatRequest.builder().messages(List.of(multiMsg)).build();

        ChatRequest textOnly = MultimodalRequests.withoutMultimodalContent(req, "fallback-model");
        assertNotNull(textOnly);

        UserMessage userMsg = (UserMessage) textOnly.messages().get(0);
        assertEquals(1, userMsg.contents().size());
        assertTrue(((TextContent) userMsg.contents().get(0))
                .text()
                .contains("Model fallback-model does not support vision modality"));
    }
}
