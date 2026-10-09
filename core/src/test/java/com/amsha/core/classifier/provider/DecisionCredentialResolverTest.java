package com.amsha.core.classifier.provider;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

class DecisionCredentialResolverTest {
    @Test
    void copiesConfiguredKeyIntoCallerOwnedUtf8BytesAndClearsThem() {
        String testKey = "test-only-decision-key";

        byte[] credential = DecisionCredentialResolver.resolve(testKey).orElseThrow();
        assertArrayEquals(testKey.getBytes(StandardCharsets.UTF_8), credential);

        DecisionCredentialResolver.zero(credential);

        assertArrayEquals(new byte[credential.length], credential);
    }

    @Test
    void returnsNoMaterialForMissingOrBlankKey() {
        assertTrue(DecisionCredentialResolver.resolve(null).isEmpty());
        assertTrue(DecisionCredentialResolver.resolve(" \t ").isEmpty());
    }
}
