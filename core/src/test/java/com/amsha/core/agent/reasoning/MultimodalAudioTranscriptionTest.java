package com.amsha.core.agent.reasoning;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.langchain4j.data.message.Content;
import dev.langchain4j.data.message.TextContent;
import dev.langchain4j.data.message.UserMessage;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import com.amsha.channel.api.AttachmentType;
import com.amsha.channel.api.MediaAttachment;
import com.amsha.llm.api.MultimodalCapability;
import com.amsha.llm.api.audio.AudioTranscriptionPort;
import com.amsha.llm.api.audio.AudioTranscriptionRequest;
import com.amsha.llm.api.audio.AudioTranscriptionResult;
import com.amsha.security.api.DownloadedMedia;
import com.amsha.security.api.MediaDownloadPort;

import org.junit.jupiter.api.Test;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

class MultimodalAudioTranscriptionTest {

    @Test
    void unsupportedAudioIsGovernedTranscribedAndReturnedAsText() {
        byte[] audio = new byte[] {'I', 'D', '3', 1, 2};
        MediaAttachment attachment =
                MediaAttachment.ofInline("voice-1", AttachmentType.AUDIO, "audio/mpeg", "voice.mp3", audio, Map.of());
        AtomicReference<AudioTranscriptionRequest> requestSeen = new AtomicReference<>();
        MediaDownloadPort downloadPort = new InlineDownloadPort(audio);
        AudioTranscriptionPort transcriptionPort = new AudioTranscriptionPort() {
            @Override
            public boolean supports(String mimeType, long declaredBytes) {
                return "audio/mpeg".equals(mimeType) && declaredBytes == audio.length;
            }

            @Override
            public AudioTranscriptionResult transcribe(AudioTranscriptionRequest request) {
                requestSeen.set(request);
                return AudioTranscriptionResult.transcribed("Please summarize the recording.", "en", Duration.ZERO);
            }
        };

        UserMessage message = MultimodalMessageMapper.mapToUserMessage(
                "What did they say?",
                List.of(attachment),
                MultimodalCapability.TEXT_ONLY,
                "ornith",
                downloadPort,
                "user-1",
                true,
                transcriptionPort,
                null);

        List<Content> contents = message.contents();
        assertEquals(1, contents.size());
        assertTrue(contents.getFirst() instanceof TextContent);
        assertTrue(message.singleText().contains("Please summarize the recording."));
        assertEquals(audio.length, requestSeen.get().declaredBytes());
    }

    @Test
    void failedTranscriptionUsesNeutralFailureMessage() {
        byte[] audio = new byte[] {'I', 'D', '3'};
        MediaAttachment attachment =
                MediaAttachment.ofInline("voice-2", AttachmentType.AUDIO, "audio/mpeg", "voice.mp3", audio, Map.of());
        AudioTranscriptionPort transcriptionPort = new AudioTranscriptionPort() {
            @Override
            public boolean supports(String mimeType, long declaredBytes) {
                return true;
            }

            @Override
            public AudioTranscriptionResult transcribe(AudioTranscriptionRequest request) {
                return AudioTranscriptionResult.failure(
                        AudioTranscriptionResult.Status.FAILED, "provider_error", Duration.ZERO);
            }
        };

        UserMessage message = MultimodalMessageMapper.mapToUserMessage(
                "Listen",
                List.of(attachment),
                MultimodalCapability.TEXT_ONLY,
                "ornith",
                new InlineDownloadPort(audio),
                "user-1",
                true,
                transcriptionPort,
                null);

        assertTrue(message.singleText().contains("Audio transcription could not be completed"));
        assertFalse(message.singleText().contains("Select an audio-capable model"));
    }

    @Test
    void senderDeclaredDurationFlowsIntoTranscriptionRequest() {
        byte[] audio = new byte[] {'I', 'D', '3'};
        MediaAttachment attachment = MediaAttachment.ofInline(
                "voice-duration",
                AttachmentType.AUDIO,
                "audio/mpeg",
                "voice.mp3",
                audio,
                Map.of(MediaAttachment.DECLARED_DURATION_SECONDS, "17"));
        AtomicReference<AudioTranscriptionRequest> requestSeen = new AtomicReference<>();
        AudioTranscriptionPort transcriptionPort = new AudioTranscriptionPort() {
            @Override
            public boolean supports(String mimeType, long declaredBytes) {
                return true;
            }

            @Override
            public AudioTranscriptionResult transcribe(AudioTranscriptionRequest request) {
                requestSeen.set(request);
                return AudioTranscriptionResult.transcribed("Recorded words", "en", Duration.ZERO);
            }
        };

        MultimodalMessageMapper.mapToUserMessage(
                "Transcribe this audio",
                List.of(attachment),
                MultimodalCapability.TEXT_ONLY,
                "ornith",
                new InlineDownloadPort(audio),
                "user-1",
                true,
                transcriptionPort,
                null);

        assertEquals(Duration.ofSeconds(17), requestSeen.get().declaredDuration());
    }

    @Test
    void tooLargeAudioGetsSpecificFallbackText() {
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        byte[] audio = new byte[] {'I', 'D', '3'};
        MediaAttachment attachment = MediaAttachment.ofInline(
                "voice-large", AttachmentType.AUDIO, "audio/mpeg", "voice.mp3", audio, Map.of());
        AudioTranscriptionPort transcriptionPort = new AudioTranscriptionPort() {
            @Override
            public boolean supports(String mimeType, long declaredBytes) {
                return true;
            }

            @Override
            public AudioTranscriptionResult transcribe(AudioTranscriptionRequest request) {
                return AudioTranscriptionResult.failure(
                        AudioTranscriptionResult.Status.TOO_LARGE, "audio_duration_exceeded", Duration.ZERO);
            }
        };

        UserMessage message = MultimodalMessageMapper.mapToUserMessage(
                "Transcribe this audio",
                List.of(attachment),
                MultimodalCapability.TEXT_ONLY,
                "ornith",
                new InlineDownloadPort(audio),
                "user-1",
                true,
                transcriptionPort,
                meterRegistry);

        assertTrue(message.singleText().contains("configured size or duration limit"));
        assertEquals(
                1.0,
                meterRegistry
                        .get(MultimodalMetrics.FALLBACK_TOTAL)
                        .tag("reason", "audio_too_large")
                        .counter()
                        .count());
    }

    private record InlineDownloadPort(byte[] audio) implements MediaDownloadPort {
        @Override
        public DownloadedMedia download(MediaAttachment attachment, String actorUserId, boolean preApproved) {
            return DownloadedMedia.ofInline(
                    attachment.attachmentId(), attachment.type(), attachment.mimeType(), attachment.fileName(), audio);
        }

        @Override
        public List<DownloadedMedia> downloadAll(
                List<MediaAttachment> attachments, String actorUserId, boolean preApproved) {
            return attachments.stream()
                    .map(attachment -> DownloadedMedia.ofInline(
                            attachment.attachmentId(),
                            attachment.type(),
                            attachment.mimeType(),
                            attachment.fileName(),
                            audio))
                    .toList();
        }
    }
}
