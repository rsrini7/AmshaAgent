package com.amsha.llm.providers.audio;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class GnaniApiContractTest {
    @Test
    void normalizesOnlySupportedRequiredSttLanguages() {
        assertEquals("hi-IN", GnaniApiContract.normalizeSttLanguage("hi"));
        assertEquals("ta-IN", GnaniApiContract.normalizeSttLanguage("TA-in"));
        assertNull(GnaniApiContract.normalizeSttLanguage("auto"));
        assertNull(GnaniApiContract.normalizeSttLanguage("hi_US"));
        assertNull(GnaniApiContract.normalizeSttLanguage("fr-FR"));
    }

    @Test
    void acceptsOnlyDocumentedAudioMimeTypes() {
        assertTrue(GnaniApiContract.isSupportedAudioMime("audio/ogg; codecs=opus"));
        assertTrue(GnaniApiContract.isSupportedAudioMime("audio/m4a"));
        assertFalse(GnaniApiContract.isSupportedAudioMime("video/webm"));
        assertFalse(GnaniApiContract.isSupportedAudioMime("image/png"));
    }

    @Test
    void detectsOnlyUnambiguousTtsScriptsAndFallsBackToAuto() {
        assertEquals("ta-IN", GnaniSpeechSynthesisProvider.detectLanguage("வணக்கம்"));
        assertEquals("gu-IN", GnaniSpeechSynthesisProvider.detectLanguage("નમસ્તે"));
        assertEquals("hi-IN", GnaniSpeechSynthesisProvider.detectLanguage("नमस्ते"));
        assertEquals("hi-en", GnaniSpeechSynthesisProvider.detectLanguage("Hello नमस्ते"));
        assertEquals("auto", GnaniSpeechSynthesisProvider.detectLanguage("Hello"));
        assertEquals("auto", GnaniSpeechSynthesisProvider.detectLanguage("அহ"));
        assertEquals("auto", GnaniSpeechSynthesisProvider.detectLanguage("வన"));
    }

    @Test
    void rejectsApiKeyValuesThatCouldBreakAnHttpHeader() {
        assertTrue(GnaniApiContract.isValidApiKeyHeaderValue("test-key-123"));
        assertFalse(GnaniApiContract.isValidApiKeyHeaderValue("test\nkey"));
        assertFalse(GnaniApiContract.isValidApiKeyHeaderValue("téšt-key"));
    }
}
