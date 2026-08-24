package com.amsha.llm.providers.error;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.TimeoutException;

import com.amsha.llm.api.error.LlmContentFilterException;
import com.amsha.llm.api.error.LlmProviderException;

import org.junit.jupiter.api.Test;

/**
 * Provider-specific classification for Gemini quirk failures (empty/blocked responses
 * surface as NPEs over "parts"/"Part"). Moved here from core with the classifier SPI
 * (Issue 03); matrix preserved from the former core {@code LlmExceptionClassifierTest}.
 */
class GeminiExceptionClassifierTest {

    private final GeminiExceptionClassifier classifier = new GeminiExceptionClassifier();

    @Test
    void geminiSafetyBlockedNpeMapsToContentFilter() {
        NullPointerException npe = new NullPointerException("Something about parts list being null or Part");
        LlmProviderException classified = classifier.classify(npe);
        assertTrue(classified instanceof LlmContentFilterException);
        assertTrue(classified.getMessage().contains("safety filters"));
    }

    @Test
    void nestedCauseIsInspectedForPartsNullPointer() {
        RuntimeException wrapped = new RuntimeException("transport failed", new NullPointerException("parts was null"));
        LlmProviderException classified = classifier.classify(wrapped);
        assertTrue(classified instanceof LlmContentFilterException);
    }

    @Test
    void unrelatedThrowablesDelegateAsUnrecognized() {
        assertNull(classifier.classify(new TimeoutException("timed out")));
        assertNull(classifier.classify(new IllegalStateException("no match")));
        assertEquals(0, classifier.order(), "provider-specific classifiers run first");
    }
}
