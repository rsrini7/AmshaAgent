package com.arcclaw.core.security;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

class Argon2idKeyDerivationTest {

    private Argon2idKeyDerivation keyDerivation;

    @BeforeEach
    void setUp() throws Exception {
        keyDerivation = new Argon2idKeyDerivation();
        
        // Inject char[] pepper using reflection
        Field pepperField = Argon2idKeyDerivation.class.getDeclaredField("pepperChars");
        pepperField.setAccessible(true);
        pepperField.set(keyDerivation, "secret-pepper".toCharArray());
    }

    @Test
    void testDeriveSessionKeySuccess() {
        VaultKey key = keyDerivation.deriveSessionKey("test-session");
        assertNotNull(key);
        assertEquals(32, key.get().length);
    }

    @Test
    void testPepperIsZeroedAfterUse() throws Exception {
        VaultKey key = keyDerivation.deriveSessionKey("test-session");
        assertNotNull(key);

        Field pepperField = Argon2idKeyDerivation.class.getDeclaredField("pepperChars");
        pepperField.setAccessible(true);
        char[] pepperChars = (char[]) pepperField.get(keyDerivation);
        
        char[] expectedZeroed = new char["secret-pepper".length()];
        Arrays.fill(expectedZeroed, '\0');
        
        assertArrayEquals(expectedZeroed, pepperChars, "Pepper characters must be securely wiped after use");
    }
}
