package com.arcclaw.core.security;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;

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
    void pepperCharsAreZeroedInsideDeriveSessionKey() throws Exception {
        // Call derivation
        keyDerivation.deriveSessionKey("test-session-id");

        // Read pepperChars field via reflection — must be all zeros BEFORE @PreDestroy fires
        Field field = Argon2idKeyDerivation.class.getDeclaredField("pepperChars");
        field.setAccessible(true);
        char[] pepper = (char[]) field.get(keyDerivation);

        for (int i = 0; i < pepper.length; i++) {
            assertEquals('\0', pepper[i],
                    "pepperChars[" + i + "] must be zeroed after deriveSessionKey() returns, " +
                    "not only in @PreDestroy");
        }
    }
}
