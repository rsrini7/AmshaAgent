package com.amsha.core.speech;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class SpeechOutputPolicyTest {

    private static final OutputPolicyContext NEUTRAL_TEXT = OutputPolicyContext.of("what is the weather today", false);

    @Test
    void explicitNegativeDirectiveStaysTextOnly() {
        SpeechOutputPolicy policy = new SpeechOutputPolicy("TEXT_ONLY");
        assertEquals(OutputMode.TEXT_ONLY, policy.resolve(OutputPolicyContext.of("do not send audio", false)));
        assertEquals(
                OutputMode.TEXT_ONLY,
                policy.resolve(OutputPolicyContext.of("do not send audio", false, OutputMode.TEXT_AND_AUDIO)));
    }

    @Test
    void explicitPositiveDirectiveResolvesToTextAndAudio() {
        SpeechOutputPolicy policy = new SpeechOutputPolicy("TEXT_ONLY");
        assertEquals(
                OutputMode.TEXT_AND_AUDIO, policy.resolve(OutputPolicyContext.of("please reply with audio", false)));
    }

    @Test
    void specificationPositiveDirectiveReplyAudioResolvesToTextAndAudio() {
        SpeechOutputPolicy policy = new SpeechOutputPolicy("TEXT_ONLY");
        assertEquals(OutputMode.TEXT_AND_AUDIO, policy.resolve(OutputPolicyContext.of("reply audio", false)));
    }

    @Test
    void audioOnlyPhraseResolvesToAudioOnly() {
        SpeechOutputPolicy policy = new SpeechOutputPolicy("TEXT_ONLY");
        assertEquals(OutputMode.AUDIO_ONLY, policy.resolve(OutputPolicyContext.of("audio only please", false)));
    }

    @Test
    void preferredOutputModeAppliesToNeutralText() {
        SpeechOutputPolicy policy = new SpeechOutputPolicy("TEXT_ONLY");
        assertEquals(
                OutputMode.TEXT_AND_AUDIO,
                policy.resolve(OutputPolicyContext.of("hello world", false, OutputMode.TEXT_AND_AUDIO)));
        assertEquals(
                OutputMode.AUDIO_ONLY,
                policy.resolve(OutputPolicyContext.of("hello world", false, OutputMode.AUDIO_ONLY)));
        assertEquals(
                OutputMode.TEXT_ONLY,
                policy.resolve(OutputPolicyContext.of("hello world", false, OutputMode.TEXT_ONLY)));
    }

    @Test
    void explicitTextOnlyPreferenceOverridesSystemAudioDefaultOnInboundAudio() {
        SpeechOutputPolicy textAndAudioDefault = new SpeechOutputPolicy("TEXT_AND_AUDIO");
        assertEquals(
                OutputMode.TEXT_ONLY,
                textAndAudioDefault.resolve(OutputPolicyContext.of("", true, OutputMode.TEXT_ONLY)));

        SpeechOutputPolicy audioOnlyDefault = new SpeechOutputPolicy("AUDIO_ONLY");
        assertEquals(
                OutputMode.TEXT_ONLY, audioOnlyDefault.resolve(OutputPolicyContext.of("", true, OutputMode.TEXT_ONLY)));
    }

    @Test
    void incidentalAudioMentionStaysTextOnly() {
        SpeechOutputPolicy policy = new SpeechOutputPolicy("AUDIO_ONLY");
        assertEquals(OutputMode.TEXT_ONLY, policy.resolve(OutputPolicyContext.of("how do audio codecs work?", false)));
    }

    @Test
    void blankTextFallsBackToTextOnly() {
        SpeechOutputPolicy policy = new SpeechOutputPolicy("AUDIO_ONLY");
        assertEquals(OutputMode.TEXT_ONLY, policy.resolve(null));
        assertEquals(OutputMode.TEXT_ONLY, policy.resolve(OutputPolicyContext.of("", false)));
    }

    @Test
    void blankTextWithAudioInputUsesDefaultMode() {
        SpeechOutputPolicy audioOnlyDefault = new SpeechOutputPolicy("AUDIO_ONLY");
        assertEquals(OutputMode.AUDIO_ONLY, audioOnlyDefault.resolve(OutputPolicyContext.of("", true)));

        SpeechOutputPolicy textAndAudioDefault = new SpeechOutputPolicy("TEXT_AND_AUDIO");
        assertEquals(OutputMode.TEXT_AND_AUDIO, textAndAudioDefault.resolve(OutputPolicyContext.of("", true)));
    }

    @Test
    void audioInputTurnUsesDefaultModeWhenNoDirective() {
        SpeechOutputPolicy audioOnlyDefault = new SpeechOutputPolicy("AUDIO_ONLY");
        assertEquals(
                OutputMode.AUDIO_ONLY,
                audioOnlyDefault.resolve(OutputPolicyContext.of("continue the conversation", true)));

        SpeechOutputPolicy textAndAudioDefault = new SpeechOutputPolicy("TEXT_AND_AUDIO");
        assertEquals(
                OutputMode.TEXT_AND_AUDIO,
                textAndAudioDefault.resolve(OutputPolicyContext.of("continue the conversation", true)));
    }

    @Test
    void audioInputTurnWithoutDefaultStillTextOnly() {
        SpeechOutputPolicy policy = new SpeechOutputPolicy("TEXT_ONLY");
        assertEquals(OutputMode.TEXT_ONLY, policy.resolve(OutputPolicyContext.of("continue the conversation", true)));
    }

    @Test
    void nonAudioInputTurnIgnoresDefaultMode() {
        SpeechOutputPolicy policy = new SpeechOutputPolicy("AUDIO_ONLY");
        assertEquals(OutputMode.TEXT_ONLY, policy.resolve(NEUTRAL_TEXT));
    }

    @Test
    void invalidDefaultModeFallsBackToTextOnly() {
        SpeechOutputPolicy policy = new SpeechOutputPolicy("not-a-mode");
        assertEquals(OutputMode.TEXT_ONLY, policy.resolve(OutputPolicyContext.of("continue the conversation", true)));
    }

    @Test
    void nullDefaultModeFallsBackToTextOnly() {
        SpeechOutputPolicy policy = new SpeechOutputPolicy(null);
        assertEquals(OutputMode.TEXT_ONLY, policy.resolve(OutputPolicyContext.of("continue the conversation", true)));
    }
}
