package com.arcclaw.core.security;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.Field;
import java.util.Arrays;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class Argon2idKeyDerivationTest {

    private Argon2idKeyDerivation keyDerivation;
    private char[] pepperChars;

    @BeforeEach
    void setUp() {
        keyDerivation = new Argon2idKeyDerivation();
        pepperChars = "secret-pepper".toCharArray();
        keyDerivation.setPepperChars(pepperChars);
    }

    @Test
    void testDeriveSessionKeySuccess() {
        VaultKey key = keyDerivation.deriveSessionKey("test-session");
        assertNotNull(key);
        assertEquals(32, key.get().length);
    }

    @Test
    void pepperCharsPersistAcrossMultipleCalls() throws Exception {
        VaultKey firstKey = keyDerivation.deriveSessionKey("first-session");
        assertNotNull(firstKey);

        Field field = Argon2idKeyDerivation.class.getDeclaredField("pepperChars");
        field.setAccessible(true);
        char[] pepper = (char[]) field.get(keyDerivation);

        assertEquals("secret-pepper", new String(pepper), "pepperChars must persist after deriveSessionKey() returns");

        VaultKey secondKey = keyDerivation.deriveSessionKey("second-session");
        assertNotNull(secondKey);
    }

    @Test
    void sameSessionIdProducesSameKey() {
        VaultKey key1 = keyDerivation.deriveSessionKey("test-session");
        VaultKey key2 = keyDerivation.deriveSessionKey("test-session");
        assertArrayEquals(key1.get(), key2.get());
    }

    @Test
    void differentSessionIdsProduceDifferentKeys() {
        VaultKey key1 = keyDerivation.deriveSessionKey("session-a");
        VaultKey key2 = keyDerivation.deriveSessionKey("session-b");
        assertFalse(Arrays.equals(key1.get(), key2.get()));
    }

    @Test
    void nullSessionIdThrows() {
        assertThrows(IllegalArgumentException.class, () -> keyDerivation.deriveSessionKey(null));
    }

    @Test
    void blankSessionIdThrows() {
        assertThrows(IllegalArgumentException.class, () -> keyDerivation.deriveSessionKey("   "));
    }
}
