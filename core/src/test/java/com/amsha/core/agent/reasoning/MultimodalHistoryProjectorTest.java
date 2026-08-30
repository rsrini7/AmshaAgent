package com.amsha.core.agent.reasoning;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.langchain4j.data.audio.Audio;
import dev.langchain4j.data.image.Image;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.AudioContent;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ImageContent;
import dev.langchain4j.data.message.PdfFileContent;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.TextContent;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.data.pdf.PdfFile;
import dev.langchain4j.model.chat.ChatModel;

import java.util.Base64;
import java.util.List;
import java.util.Set;

import com.amsha.channel.api.AttachmentType;
import com.amsha.core.routing.ModelRoute;
import com.amsha.llm.api.MultimodalCapability;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class MultimodalHistoryProjectorTest {

    private MultimodalHistoryProjector projector;

    private static final String TINY_PNG_BASE64 =
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNk+M9QDwADhgGAWjR9awAAAABJRU5ErkJggg==";
    private static final String TINY_AUDIO_BASE64 =
            Base64.getEncoder().encodeToString(new byte[] {0x52, 0x49, 0x46, 0x46, 0x24, 0x00, 0x00, 0x00});
    private static final String TINY_PDF_BASE64 = Base64.getEncoder().encodeToString("%PDF-1.4\n%EOF".getBytes());

    @BeforeEach
    void setUp() {
        projector = new MultimodalHistoryProjector();
    }

    private ModelRoute createRoute(String id, MultimodalCapability capability) {
        return new ModelRoute(id, "provider", id, null, Mockito.mock(ChatModel.class), capability, List.of(), 1);
    }

    @Test
    void imageHistoryPreservedWhenTargetSupportsImage() {
        MultimodalCapability visionCap =
                new MultimodalCapability(Set.of(AttachmentType.IMAGE), Set.of("image/png"), 10_000_000L, 5, true, true);
        ModelRoute visionRoute = createRoute("qwen-vl", visionCap);

        UserMessage userMsg = UserMessage.from(
                TextContent.from("Look at this chart:"),
                ImageContent.from(Image.builder()
                        .mimeType("image/png")
                        .base64Data(TINY_PNG_BASE64)
                        .build()));

        List<ChatMessage> projected = projector.projectHistory(List.of(userMsg), visionRoute);

        assertEquals(1, projected.size());
        assertInstanceOf(UserMessage.class, projected.getFirst());
        UserMessage result = (UserMessage) projected.getFirst();
        assertEquals(2, result.contents().size());
        assertInstanceOf(TextContent.class, result.contents().get(0));
        assertInstanceOf(ImageContent.class, result.contents().get(1));
    }

    @Test
    void imageHistoryProjectedToPlaceholderWhenTargetIsTextOnly() {
        ModelRoute textRoute = createRoute("ornith-text", MultimodalCapability.TEXT_ONLY);

        UserMessage userMsg = UserMessage.from(
                TextContent.from("Look at this chart:"),
                ImageContent.from(Image.builder()
                        .mimeType("image/png")
                        .base64Data(TINY_PNG_BASE64)
                        .build()));

        List<ChatMessage> projected = projector.projectHistory(List.of(userMsg), textRoute);

        assertEquals(1, projected.size());
        assertInstanceOf(UserMessage.class, projected.getFirst());
        UserMessage result = (UserMessage) projected.getFirst();
        assertTrue(result.contents().stream().allMatch(c -> c instanceof TextContent));
        String text = result.singleText();
        assertTrue(text.contains("Look at this chart:"));
        assertTrue(text.contains(
                "[Attached Media: Image (image/png) - Model ornith-text does not support vision modality]"));
    }

    @Test
    void audioHistoryPreservedWhenTargetSupportsAudio() {
        MultimodalCapability audioCap =
                new MultimodalCapability(Set.of(AttachmentType.AUDIO), Set.of("audio/wav"), 10_000_000L, 5, true, true);
        ModelRoute audioRoute = createRoute("nemotron-voice", audioCap);

        UserMessage userMsg = UserMessage.from(
                TextContent.from("Listen to this recording:"),
                AudioContent.from(Audio.builder()
                        .mimeType("audio/wav")
                        .base64Data(TINY_AUDIO_BASE64)
                        .build()));

        List<ChatMessage> projected = projector.projectHistory(List.of(userMsg), audioRoute);

        assertEquals(1, projected.size());
        UserMessage result = (UserMessage) projected.getFirst();
        assertEquals(2, result.contents().size());
        assertInstanceOf(AudioContent.class, result.contents().get(1));
    }

    @Test
    void audioHistoryProjectedToPlaceholderWhenTargetIsTextOnly() {
        ModelRoute textRoute = createRoute("text-model", MultimodalCapability.TEXT_ONLY);

        UserMessage userMsg = UserMessage.from(
                TextContent.from("Voice query:"),
                AudioContent.from(Audio.builder()
                        .mimeType("audio/wav")
                        .base64Data(TINY_AUDIO_BASE64)
                        .build()));

        List<ChatMessage> projected = projector.projectHistory(List.of(userMsg), textRoute);

        assertEquals(1, projected.size());
        UserMessage result = (UserMessage) projected.getFirst();
        assertTrue(result.singleText()
                .contains("[Attached Media: Audio (audio/wav) - Model text-model does not support audio modality]"));
    }

    @Test
    void pdfHistoryPreservedWhenTargetSupportsPdf() {
        MultimodalCapability pdfCap = new MultimodalCapability(
                Set.of(AttachmentType.DOCUMENT_PDF), Set.of("application/pdf"), 25_000_000L, 2, true, true);
        ModelRoute pdfRoute = createRoute("gemini-pdf", pdfCap);

        UserMessage userMsg = UserMessage.from(
                TextContent.from("Document:"),
                PdfFileContent.from(PdfFile.builder()
                        .mimeType("application/pdf")
                        .base64Data(TINY_PDF_BASE64)
                        .build()));

        List<ChatMessage> projected = projector.projectHistory(List.of(userMsg), pdfRoute);

        assertEquals(1, projected.size());
        UserMessage result = (UserMessage) projected.getFirst();
        assertEquals(2, result.contents().size());
        assertInstanceOf(PdfFileContent.class, result.contents().get(1));
    }

    @Test
    void pdfHistoryProjectedToPlaceholderWhenTargetIsTextOnly() {
        ModelRoute textRoute = createRoute("text-model", MultimodalCapability.TEXT_ONLY);

        UserMessage userMsg = UserMessage.from(
                TextContent.from("Document:"),
                PdfFileContent.from(PdfFile.builder()
                        .mimeType("application/pdf")
                        .base64Data(TINY_PDF_BASE64)
                        .build()));

        List<ChatMessage> projected = projector.projectHistory(List.of(userMsg), textRoute);

        assertEquals(1, projected.size());
        UserMessage result = (UserMessage) projected.getFirst();
        assertTrue(
                result.singleText()
                        .contains(
                                "[Attached Document: PDF (application/pdf) - Model text-model does not support native PDF modality]"));
    }

    @Test
    void mixedHistoryPartiallyProjectedWhenTargetSupportsSubsetOfModalities() {
        // Target is Vision-only (supports IMAGE, but NOT AUDIO)
        MultimodalCapability visionCap =
                new MultimodalCapability(Set.of(AttachmentType.IMAGE), Set.of("image/png"), 10_000_000L, 5, true, true);
        ModelRoute visionRoute = createRoute("qwen-vl", visionCap);

        UserMessage mixedMsg = UserMessage.from(
                TextContent.from("Mixed turn:"),
                ImageContent.from(Image.builder()
                        .mimeType("image/png")
                        .base64Data(TINY_PNG_BASE64)
                        .build()),
                AudioContent.from(Audio.builder()
                        .mimeType("audio/wav")
                        .base64Data(TINY_AUDIO_BASE64)
                        .build()));

        List<ChatMessage> projected = projector.projectHistory(List.of(mixedMsg), visionRoute);

        assertEquals(1, projected.size());
        UserMessage result = (UserMessage) projected.getFirst();
        assertEquals(3, result.contents().size());
        assertInstanceOf(TextContent.class, result.contents().get(0));
        assertInstanceOf(ImageContent.class, result.contents().get(1)); // Preserved
        assertInstanceOf(TextContent.class, result.contents().get(2)); // Audio projected to placeholder
        assertTrue(((TextContent) result.contents().get(2))
                .text()
                .contains("[Attached Media: Audio (audio/wav) - Model qwen-vl does not support audio modality]"));
    }

    @Test
    void mediaOnlyTurnPreservedOrProjectedWithoutCrashing() {
        UserMessage imageOnly = UserMessage.from(ImageContent.from(Image.builder()
                .mimeType("image/png")
                .base64Data(TINY_PNG_BASE64)
                .build()));

        // When target is text-only
        List<ChatMessage> projectedText =
                projector.projectHistory(List.of(imageOnly), MultimodalCapability.TEXT_ONLY, "text-model");
        assertEquals(1, projectedText.size());
        assertInstanceOf(UserMessage.class, projectedText.getFirst());
        UserMessage textResult = (UserMessage) projectedText.getFirst();
        assertTrue(textResult
                .singleText()
                .contains("[Attached Media: Image (image/png) - Model text-model does not support vision modality]"));

        // When target is vision
        MultimodalCapability visionCap =
                new MultimodalCapability(Set.of(AttachmentType.IMAGE), Set.of("image/png"), 10_000_000L, 5, true, true);
        List<ChatMessage> projectedVision = projector.projectHistory(List.of(imageOnly), visionCap, "qwen-vl");
        assertEquals(1, projectedVision.size());
        assertInstanceOf(
                ImageContent.class,
                ((UserMessage) projectedVision.getFirst()).contents().getFirst());
    }

    @Test
    void textOnlyTurnPreservedWithoutModification() {
        UserMessage textMsg = UserMessage.userMessage("Simple text question");
        List<ChatMessage> projected =
                projector.projectHistory(List.of(textMsg), MultimodalCapability.TEXT_ONLY, "any-model");

        assertEquals(1, projected.size());
        assertEquals("Simple text question", ((UserMessage) projected.getFirst()).singleText());
    }

    @Test
    void nonUserMessagesPreservedAsIs() {
        ChatMessage sys = SystemMessage.systemMessage("System directive");
        ChatMessage ai = AiMessage.aiMessage("AI response");

        List<ChatMessage> projected =
                projector.projectHistory(List.of(sys, ai), MultimodalCapability.TEXT_ONLY, "any-model");

        assertEquals(2, projected.size());
        assertEquals(sys, projected.get(0));
        assertEquals(ai, projected.get(1));
    }

    @Test
    void nullAndEmptyHistoryHandledSafely() {
        assertTrue(projector.projectHistory(null, (ModelRoute) null).isEmpty());
        assertTrue(projector.projectHistory(List.of(), (ModelRoute) null).isEmpty());
        assertEquals(null, projector.projectMessage(null, MultimodalCapability.TEXT_ONLY, "model"));
    }
}
