package com.amsha.app.speech;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import com.amsha.channel.api.ChannelSender;
import com.amsha.channel.api.ChannelType;
import com.amsha.channel.api.ChannelUser;
import com.amsha.common.config.ConfigKeys;
import com.amsha.core.speech.OutputMode;
import com.amsha.core.speech.SpeechReplyService;
import com.amsha.llm.api.audio.SpeechSynthesisPort;
import com.amsha.llm.api.audio.SpeechSynthesisRequest;
import com.amsha.llm.api.audio.SpeechSynthesisResult;
import com.amsha.llm.providers.audio.GnaniSpeechSynthesisProvider;
import com.amsha.llm.providers.audio.OmlxSpeechSynthesisProvider;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.quarkus.test.junit.QuarkusMock;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;

@QuarkusTest
@TestProfile(SpeechReplyCdiSelectionTest.GnaniTtsProfile.class)
class SpeechReplyCdiSelectionTest {
    private static final ChannelUser USER = new ChannelUser("user-1", "platform-1", "User", ChannelType.REST_API);

    @Inject
    SpeechReplyService speechReplyService;

    @Inject
    Instance<SpeechSynthesisPort> speechSynthesisPorts;

    private GnaniSpeechSynthesisProvider selectedProvider;

    @BeforeEach
    void installSelectedProviderMock() {
        selectedProvider = mock(GnaniSpeechSynthesisProvider.class);
        when(selectedProvider.providerId()).thenReturn(GnaniSpeechSynthesisProvider.PROVIDER_ID);
        when(selectedProvider.supports(any(SpeechSynthesisRequest.class))).thenReturn(true);
        when(selectedProvider.synthesize(any(SpeechSynthesisRequest.class)))
                .thenReturn(SpeechSynthesisResult.synthesized(wavBytes(), "audio/wav", Duration.ZERO));
        QuarkusMock.installMockForType(selectedProvider, GnaniSpeechSynthesisProvider.class);
    }

    @Test
    void cdiResolvesTheConfiguredProviderWhenOmlxAndGnaniBeansArePresent() {
        ChannelSender sender = mock(ChannelSender.class);
        when(sender.sendAudio(any(), any(), any(), any())).thenReturn(true);

        assertTrue(speechReplyService.deliver(sender, USER, OutputMode.AUDIO_ONLY, "A governed speech reply."));

        verify(selectedProvider).synthesize(any(SpeechSynthesisRequest.class));
        verify(sender).sendAudio(USER, wavBytes(), "audio/wav", "amsha-reply.wav");
    }

    @Test
    void bothSynthesisProvidersAreRegistered() {
        Set<String> providerIds = speechSynthesisPorts.stream()
                .map(SpeechSynthesisPort::providerId)
                .collect(Collectors.toSet());

        assertTrue(providerIds.containsAll(
                Set.of(OmlxSpeechSynthesisProvider.PROVIDER_ID, GnaniSpeechSynthesisProvider.PROVIDER_ID)));
    }

    private static byte[] wavBytes() {
        byte[] bytes = new byte[16];
        bytes[0] = 'R';
        bytes[1] = 'I';
        bytes[2] = 'F';
        bytes[3] = 'F';
        bytes[8] = 'W';
        bytes[9] = 'A';
        bytes[10] = 'V';
        bytes[11] = 'E';
        return bytes;
    }

    public static class GnaniTtsProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of(
                    ConfigKeys.TTS_PROVIDER_PROPERTY,
                    "gnani",
                    ConfigKeys.TTS_ENABLED_PROPERTY,
                    "true",
                    ConfigKeys.GNANI_API_KEY,
                    "test-placeholder");
        }
    }
}
