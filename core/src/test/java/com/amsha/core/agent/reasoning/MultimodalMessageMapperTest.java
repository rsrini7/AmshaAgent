package com.amsha.core.agent.reasoning;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.langchain4j.data.message.AudioContent;
import dev.langchain4j.data.message.Content;
import dev.langchain4j.data.message.ImageContent;
import dev.langchain4j.data.message.PdfFileContent;
import dev.langchain4j.data.message.TextContent;
import dev.langchain4j.data.message.UserMessage;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import com.amsha.channel.api.AttachmentType;
import com.amsha.channel.api.MediaAttachment;
import com.amsha.llm.api.MultimodalCapabilities;
import com.amsha.llm.api.MultimodalCapability;
import com.amsha.security.api.DownloadedMedia;
import com.amsha.security.api.MediaDownloadPort;

import org.junit.jupiter.api.Test;

class MultimodalMessageMapperTest {

    private static final MediaDownloadPort TEST_DOWNLOAD_PORT = (attachment, _, _) -> {
        if (attachment.inlineData() == null
                && attachment.sourceUri() != null
                && !"http".equalsIgnoreCase(attachment.sourceUri().getScheme())
                && !"https".equalsIgnoreCase(attachment.sourceUri().getScheme())) {
            throw new IllegalStateException("test port rejects non-HTTP media references");
        }
        byte[] data = attachment.inlineData();
        if (data == null) {
            data = switch (attachment.type()) {
                case IMAGE -> new byte[] {(byte) 0x89, 'P', 'N', 'G'};
                case AUDIO -> new byte[] {'I', 'D', '3', 0};
                case DOCUMENT_PDF -> "%PDF-1.7\nBT\n(Quarterly report)\nET".getBytes(StandardCharsets.ISO_8859_1);
                case OTHER -> new byte[] {0};
            };
        }
        return DownloadedMedia.ofInline(
                attachment.attachmentId(), attachment.type(), attachment.mimeType(), attachment.fileName(), data);
    };

    private static UserMessage map(
            String prompt, List<MediaAttachment> attachments, MultimodalCapability capability, String modelName) {
        return MultimodalMessageMapper.mapToUserMessage(
                prompt, attachments, capability, modelName, TEST_DOWNLOAD_PORT, "test-user", true);
    }

    @Test
    void textOnlyMessageWhenAttachmentsNullOrEmpty() {
        UserMessage msg1 = map(
                "Hello, please help.",
                null,
                MultimodalCapabilities.defaultCapabilityForModel("openai", "gpt-4o"),
                "gpt-4o");
        assertNotNull(msg1);
        assertEquals("Hello, please help.", msg1.singleText());

        UserMessage msg2 = map("Hello", List.of(), MultimodalCapability.TEXT_ONLY, "gpt-3.5-turbo");
        assertNotNull(msg2);
        assertEquals("Hello", msg2.singleText());
    }

    @Test
    void mapsImageInlineDataToImageContent() {
        byte[] pngData = new byte[] {(byte) 0x89, 'P', 'N', 'G', 1, 2, 3};
        MediaAttachment attachment = new MediaAttachment(
                "att-1", AttachmentType.IMAGE, "image/png", "diagram.png", pngData.length, null, pngData, Map.of());

        MultimodalCapability gpt4oCap = MultimodalCapabilities.defaultCapabilityForModel("openai", "gpt-4o");
        UserMessage msg = map("Analyze this diagram", List.of(attachment), gpt4oCap, "gpt-4o");

        assertNotNull(msg);
        List<Content> contents = msg.contents();
        assertEquals(2, contents.size());
        assertInstanceOf(TextContent.class, contents.get(0));
        assertEquals("Analyze this diagram", ((TextContent) contents.get(0)).text());

        assertInstanceOf(ImageContent.class, contents.get(1));
        ImageContent imageContent = (ImageContent) contents.get(1);
        assertNotNull(imageContent.image());
        assertNotNull(imageContent.image().base64Data());
        assertEquals("image/png", imageContent.image().mimeType());
    }

    @Test
    void mapsAudioInlineDataToAudioContent() {
        byte[] mp3Data = new byte[] {'I', 'D', '3', 0, 1, 2};
        MediaAttachment attachment = new MediaAttachment(
                "att-2", AttachmentType.AUDIO, "audio/mpeg", "voice.mp3", mp3Data.length, null, mp3Data, Map.of());

        MultimodalCapability geminiCap = MultimodalCapabilities.defaultCapabilityForModel("gemini", "gemini-1.5-pro");
        UserMessage msg = map("Transcribe this audio", List.of(attachment), geminiCap, "gemini-1.5-pro");

        assertNotNull(msg);
        List<Content> contents = msg.contents();
        assertEquals(2, contents.size());
        assertInstanceOf(AudioContent.class, contents.get(1));
        AudioContent audioContent = (AudioContent) contents.get(1);
        assertNotNull(audioContent.audio());
        assertNotNull(audioContent.audio().base64Data());
        assertEquals("audio/mpeg", audioContent.audio().mimeType());
    }

    @Test
    void mapsPdfInlineDataToPdfFileContent() {
        byte[] pdfData = new byte[] {'%', 'P', 'D', 'F', '-', '1', '.', '7'};
        MediaAttachment attachment = new MediaAttachment(
                "att-3",
                AttachmentType.DOCUMENT_PDF,
                "application/pdf",
                "report.pdf",
                pdfData.length,
                null,
                pdfData,
                Map.of());

        MultimodalCapability geminiCap = MultimodalCapabilities.defaultCapabilityForModel("gemini", "gemini-1.5-pro");
        UserMessage msg = map("Summarize report", List.of(attachment), geminiCap, "gemini-1.5-pro");

        assertNotNull(msg);
        List<Content> contents = msg.contents();
        assertEquals(2, contents.size());
        assertInstanceOf(PdfFileContent.class, contents.get(1));
        PdfFileContent pdfContent = (PdfFileContent) contents.get(1);
        assertNotNull(pdfContent.pdfFile());
        assertNotNull(pdfContent.pdfFile().base64Data());
    }

    @Test
    void mapsHttpSourceUris() {
        MediaAttachment imageAtt = new MediaAttachment(
                "att-img",
                AttachmentType.IMAGE,
                "image/png",
                "sample.png",
                1024L,
                URI.create("https://example.com/sample.png"),
                null,
                Map.of());

        MediaAttachment audioAtt = new MediaAttachment(
                "att-aud",
                AttachmentType.AUDIO,
                "audio/mpeg",
                "sample.mp3",
                2048L,
                URI.create("https://example.com/sample.mp3"),
                null,
                Map.of());

        MediaAttachment pdfAtt = new MediaAttachment(
                "att-doc",
                AttachmentType.DOCUMENT_PDF,
                "application/pdf",
                "sample.pdf",
                4096L,
                URI.create("https://example.com/sample.pdf"),
                null,
                Map.of());

        MultimodalCapability geminiCap = MultimodalCapabilities.defaultCapabilityForModel("gemini", "gemini-1.5-pro");
        UserMessage msg = map("Process all items", List.of(imageAtt, audioAtt, pdfAtt), geminiCap, "gemini-1.5-pro");

        assertNotNull(msg);
        List<Content> contents = msg.contents();
        assertEquals(4, contents.size());
        assertInstanceOf(ImageContent.class, contents.get(1));
        assertInstanceOf(AudioContent.class, contents.get(2));
        assertInstanceOf(PdfFileContent.class, contents.get(3));
    }

    @Test
    void fallsBackSafelyWhenModalityUnsupportedByModel() {
        byte[] audioData = new byte[] {'I', 'D', '3', 0, 1};
        MediaAttachment audioAttachment = new MediaAttachment(
                "att-aud-unsupported",
                AttachmentType.AUDIO,
                "audio/mpeg",
                "podcast.mp3",
                audioData.length,
                null,
                audioData,
                Map.of());

        // GPT-4o only supports IMAGE, not AUDIO
        MultimodalCapability gpt4oCap = MultimodalCapabilities.defaultCapabilityForModel("openai", "gpt-4o");
        UserMessage msg = map("Listen to this", List.of(audioAttachment), gpt4oCap, "gpt-4o");

        assertNotNull(msg);
        List<Content> contents = msg.contents();
        assertEquals(1, contents.size());
        assertInstanceOf(TextContent.class, contents.getFirst());
        String fallbackText = ((TextContent) contents.getFirst()).text();
        assertTrue(fallbackText.contains("[Attached Media: podcast.mp3 (audio/mpeg,"));
        assertTrue(fallbackText.contains("does not support AUDIO modality]"));
    }

    @Test
    void fallsBackSafelyWhenModelIsTextOnly() {
        byte[] pngData = new byte[] {(byte) 0x89, 'P', 'N', 'G'};
        MediaAttachment attachment = new MediaAttachment(
                "att-img", AttachmentType.IMAGE, "image/png", "photo.png", pngData.length, null, pngData, Map.of());

        UserMessage msg = map("Describe photo", List.of(attachment), MultimodalCapability.TEXT_ONLY, "gpt-3.5-turbo");

        assertNotNull(msg);
        List<Content> contents = msg.contents();
        assertEquals(1, contents.size());
        assertInstanceOf(TextContent.class, contents.getFirst());
        assertTrue(((TextContent) contents.getFirst()).text().contains("does not support IMAGE modality]"));
    }

    @Test
    void fallsBackSafelyWhenOversized() {
        byte[] data = new byte[] {1, 2, 3, 4};
        MediaAttachment attachment = new MediaAttachment(
                "att-big",
                AttachmentType.IMAGE,
                "image/png",
                "giant.png",
                100_000_000L, // 100MB
                null,
                data,
                Map.of());

        MultimodalCapability cap = new MultimodalCapability(
                Set.of(AttachmentType.IMAGE),
                Set.of("image/png"),
                10_000_000L, // 10MB limit
                5,
                true,
                true);

        UserMessage msg = map("Look at this", List.of(attachment), cap, "test-vision");

        List<Content> contents = msg.contents();
        assertEquals(1, contents.size());
        assertInstanceOf(TextContent.class, contents.getFirst());
        String text = ((TextContent) contents.getFirst()).text();
        assertTrue(text.contains("Exceeds model maximum supported size"));
    }

    @Test
    void fallsBackSafelyWhenCountExceeded() {
        byte[] data = new byte[] {1, 2};
        MediaAttachment att1 = new MediaAttachment(
                "a1", AttachmentType.IMAGE, "image/png", "1.png", data.length, null, data, Map.of());
        MediaAttachment att2 = new MediaAttachment(
                "a2", AttachmentType.IMAGE, "image/png", "2.png", data.length, null, data, Map.of());
        MediaAttachment att3 = new MediaAttachment(
                "a3", AttachmentType.IMAGE, "image/png", "3.png", data.length, null, data, Map.of());

        MultimodalCapability cap = new MultimodalCapability(
                Set.of(AttachmentType.IMAGE),
                Set.of("image/png"),
                10_000_000L,
                2, // Max 2
                true,
                true);

        UserMessage msg = map("Three pictures", List.of(att1, att2, att3), cap, "test-vision");

        List<Content> contents = msg.contents();
        assertEquals(4, contents.size());
        assertInstanceOf(ImageContent.class, contents.get(1));
        assertInstanceOf(ImageContent.class, contents.get(2));
        assertInstanceOf(TextContent.class, contents.get(3));
        assertTrue(((TextContent) contents.get(3)).text().contains("Exceeds maximum batch media limit of 2 items"));
    }

    @Test
    void fallsBackSafelyForOtherAttachmentType() {
        MediaAttachment otherAtt = new MediaAttachment(
                "other-1",
                AttachmentType.OTHER,
                "application/octet-stream",
                "archive.tar",
                1024L,
                URI.create("https://example.com/archive.tar"),
                null,
                Map.of());

        MultimodalCapability geminiCap = MultimodalCapabilities.defaultCapabilityForModel("gemini", "gemini-1.5-pro");
        UserMessage msg = map("Analyze file", List.of(otherAtt), geminiCap, "gemini-1.5-pro");

        assertNotNull(msg);
        List<Content> contents = msg.contents();
        assertEquals(1, contents.size());
        assertInstanceOf(TextContent.class, contents.getFirst());
        assertTrue(((TextContent) contents.getFirst()).text().contains("does not support OTHER modality"));
    }

    @Test
    void fallsBackSafelyForNonHttpUris() {
        MediaAttachment fileAtt = new MediaAttachment(
                "file-1",
                AttachmentType.IMAGE,
                "image/png",
                "local.png",
                1024L,
                URI.create("telegram://file_id_999"),
                null,
                Map.of());

        MultimodalCapability gpt4oCap = MultimodalCapabilities.defaultCapabilityForModel("openai", "gpt-4o");
        UserMessage msg = map("Check file", List.of(fileAtt), gpt4oCap, "gpt-4o");

        List<Content> contents = msg.contents();
        assertEquals(1, contents.size());
        assertInstanceOf(TextContent.class, contents.getFirst());
        assertTrue(((TextContent) contents.getFirst())
                .text()
                .contains("Governed media could not be downloaded or validated"));
    }

    @Test
    void fallsBackWhenCumulativeTurnPayloadExceedsBudget() {
        byte[] chunk1 = new byte[30 * 1024 * 1024]; // 30 MB
        byte[] chunk2 = new byte[25 * 1024 * 1024]; // 25 MB -> Total 55 MB > 50 MB limit

        MediaAttachment att1 = new MediaAttachment(
                "att-big-1", AttachmentType.IMAGE, "image/png", "big1.png", chunk1.length, null, chunk1, Map.of());
        MediaAttachment att2 = new MediaAttachment(
                "att-big-2", AttachmentType.IMAGE, "image/png", "big2.png", chunk2.length, null, chunk2, Map.of());

        MultimodalCapability cap =
                new MultimodalCapability(Set.of(AttachmentType.IMAGE), Set.of("image/png"), 40_000_000L, 5, true, true);

        UserMessage msg = map("Analyze two huge files", List.of(att1, att2), cap, "huge-model");

        List<Content> contents = msg.contents();
        assertEquals(3, contents.size());
        assertInstanceOf(ImageContent.class, contents.get(1));
        assertInstanceOf(TextContent.class, contents.get(2));
        assertTrue(((TextContent) contents.get(2)).text().contains("Cumulative turn media payload exceeded limit"));
    }

    @Test
    void extractsPdfTextOnFallbackWhenModelIsTextOnly() {
        String samplePdf =
                "%PDF-1.7\n1 0 obj\n<< /Length 44 >>\nstream\nBT\n/F1 12 Tf\n(Quarterly revenue reached ten million dollars) Tj\nET\nendstream\nendobj\nxref\ntrailer\n<< /Root 1 0 R >>\n%%EOF";
        byte[] pdfBytes = samplePdf.getBytes(StandardCharsets.ISO_8859_1);

        MediaAttachment pdfAtt = new MediaAttachment(
                "pdf-text",
                AttachmentType.DOCUMENT_PDF,
                "application/pdf",
                "financial_report.pdf",
                pdfBytes.length,
                null,
                pdfBytes,
                Map.of());

        MultimodalCapability textOnlyCap = MultimodalCapability.TEXT_ONLY;
        UserMessage msg = map("Review report", List.of(pdfAtt), textOnlyCap, "gpt-3.5-turbo");

        List<Content> contents = msg.contents();
        assertEquals(1, contents.size());
        assertInstanceOf(TextContent.class, contents.getFirst());
        String text = ((TextContent) contents.getFirst()).text();
        assertTrue(text.contains("financial_report.pdf - Extracted Text:"));
        assertTrue(text.contains("Quarterly revenue reached ten million dollars"));
    }

    @Test
    void mapsRemoteUriThroughMediaDownloadPort() {
        byte[] downloadedData = new byte[] {(byte) 0x89, 'P', 'N', 'G', 9, 8, 7};
        MediaDownloadPort mockPort = (att, userId, preApproved) -> DownloadedMedia.ofInline(
                att.attachmentId(), att.type(), att.mimeType(), att.fileName(), downloadedData);

        MediaAttachment remoteAtt = new MediaAttachment(
                "remote-1",
                AttachmentType.IMAGE,
                "image/png",
                "chart.png",
                100L,
                URI.create("https://example.com/chart.png"),
                null,
                Map.of());

        MultimodalCapability visionCap = MultimodalCapabilities.defaultCapabilityForModel("openai", "gpt-4o");
        UserMessage msg = MultimodalMessageMapper.mapToUserMessage(
                "Look at chart", List.of(remoteAtt), visionCap, "gpt-4o", mockPort, "user-1", true);

        List<Content> contents = msg.contents();
        assertEquals(2, contents.size());
        assertInstanceOf(ImageContent.class, contents.get(1));
        ImageContent imgContent = (ImageContent) contents.get(1);
        assertNotNull(imgContent.image());
        assertNotNull(imgContent.image().base64Data());
    }

    @Test
    void batchesUnknownSizedRemoteAttachmentsThroughDownloadAll() {
        AtomicInteger batchCalls = new AtomicInteger();
        byte[] downloadedData = new byte[] {(byte) 0x89, 'P', 'N', 'G', 9, 8, 7};
        MediaDownloadPort batchPort = new MediaDownloadPort() {
            @Override
            public DownloadedMedia download(MediaAttachment attachment, String actorUserId, boolean preApproved) {
                throw new AssertionError("mapper must use the batch download operation");
            }

            @Override
            public List<DownloadedMedia> downloadAll(
                    List<MediaAttachment> attachments, String actorUserId, boolean preApproved) {
                batchCalls.incrementAndGet();
                return attachments.stream()
                        .map(attachment -> DownloadedMedia.ofInline(
                                attachment.attachmentId(),
                                attachment.type(),
                                attachment.mimeType(),
                                attachment.fileName(),
                                downloadedData))
                        .toList();
            }
        };
        MultimodalCapability capability = MultimodalCapabilities.defaultCapabilityForModel("openai", "gpt-4o");
        MediaAttachment first = MediaAttachment.ofUri(
                "remote-batch-1",
                AttachmentType.IMAGE,
                "image/png",
                "first.png",
                0L,
                URI.create("https://example.com/first.png"),
                Map.of());
        MediaAttachment second = MediaAttachment.ofUri(
                "remote-batch-2",
                AttachmentType.IMAGE,
                "image/png",
                "second.png",
                0L,
                URI.create("https://example.com/second.png"),
                Map.of());

        UserMessage message = MultimodalMessageMapper.mapToUserMessage(
                "Inspect both", List.of(first, second), capability, "gpt-4o", batchPort, "user-1", true);

        assertEquals(1, batchCalls.get());
        assertEquals(3, message.contents().size());
        assertInstanceOf(ImageContent.class, message.contents().get(1));
        assertInstanceOf(ImageContent.class, message.contents().get(2));
    }
}
