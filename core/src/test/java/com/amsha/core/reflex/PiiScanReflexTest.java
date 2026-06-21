package com.amsha.core.reflex;

import jakarta.inject.Inject;

import com.amsha.core.security.InputSanitizer;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import io.quarkus.test.junit.QuarkusTest;

@QuarkusTest
class PiiScanReflexTest {

    @Inject
    @InputSanitizer.PII
    PiiScanReflex piiScanReflex;

    @Test
    void testEmailRedaction() {
        String input = "Contact me at user@example.com for more info.";
        Assertions.assertTrue(piiScanReflex.detectsPii(input));
        String sanitized = piiScanReflex.sanitize("session1", "user1", input);
        Assertions.assertEquals("Contact me at [REDACTED-EMAIL] for more info.", sanitized);
    }

    @Test
    void testPhoneRedaction() {
        String input = "My Indian phone is +91-9876543210 and US is 123-456-7890. Wait international is +14155552671.";
        Assertions.assertTrue(piiScanReflex.detectsPii(input));
        String sanitized = piiScanReflex.sanitize("s1", "u1", input);
        Assertions.assertTrue(sanitized.contains("[REDACTED-PHONE]"));
        Assertions.assertFalse(sanitized.contains("+91-9876543210"));
        Assertions.assertFalse(sanitized.contains("+14155552671"));
    }

    @Test
    void testSsnRedaction() {
        String input = "My SSN is 123-45-6789. Keep it safe.";
        Assertions.assertTrue(piiScanReflex.detectsPii(input));
        String sanitized = piiScanReflex.sanitize("s1", "u1", input);
        Assertions.assertEquals("My SSN is [REDACTED-SSN]. Keep it safe.", sanitized);
    }

    @Test
    void testAadhaarRedaction() {
        String input = "Aadhaar: 2345 6789 1234 or 234567891234";
        Assertions.assertTrue(piiScanReflex.detectsPii(input));
        String sanitized = piiScanReflex.sanitize("s1", "u1", input);
        Assertions.assertEquals("Aadhaar: [REDACTED-AADHAAR] or [REDACTED-AADHAAR]", sanitized);
    }

    @Test
    void testPanRedaction() {
        String input = "My PAN is ABCDE1234F.";
        Assertions.assertTrue(piiScanReflex.detectsPii(input));
        String sanitized = piiScanReflex.sanitize("s1", "u1", input);
        Assertions.assertEquals("My PAN is [REDACTED-PAN].", sanitized);
    }

    @Test
    void testCreditCardRedaction() {
        String input = "Card: 1234-5678-9012-3456 or 1234 5678 9012 3456";
        Assertions.assertTrue(piiScanReflex.detectsPii(input));
        String sanitized = piiScanReflex.sanitize("s1", "u1", input);
        Assertions.assertEquals("Card: [REDACTED-CARD] or [REDACTED-CARD]", sanitized);
    }

    @Test
    void testIpv4Redaction() {
        String input = "Server IP is 192.168.1.100.";
        Assertions.assertTrue(piiScanReflex.detectsPii(input));
        String sanitized = piiScanReflex.sanitize("s1", "u1", input);
        Assertions.assertEquals("Server IP is [REDACTED-IP].", sanitized);
    }

    @Test
    void testJwtRedaction() {
        String input =
                "Token: eyJhbGciOiJIUzI1NiIsInR5cCI.eyJzdWIiOiIxMjM0NTY3ODkw.SflKxwRJSMeKKF2QT4fwpMeJf36POk6yJV_adQssw5c";
        Assertions.assertTrue(piiScanReflex.detectsPii(input));
        String sanitized = piiScanReflex.sanitize("s1", "u1", input);
        Assertions.assertEquals("Token: [REDACTED-JWT]", sanitized);
    }

    @Test
    void testNoPiiPassesUnchanged() {
        String input = "Hello, world! What is the weather like today in Seattle?";
        Assertions.assertFalse(piiScanReflex.matches(input));
        String sanitized = piiScanReflex.sanitize("s1", "u1", input);
        Assertions.assertEquals(input, sanitized);
    }

    @Test
    void testMixedPii() {
        String input = "Email me at test@foo.com or call +91-9000000000. IP: 10.0.0.1";
        Assertions.assertTrue(piiScanReflex.detectsPii(input));
        String sanitized = piiScanReflex.sanitize("s1", "u1", input);
        Assertions.assertEquals("Email me at [REDACTED-EMAIL] or call [REDACTED-PHONE]. IP: [REDACTED-IP]", sanitized);
    }

    @Test
    void testIdempotency() {
        String input = "Email: test@example.com";
        String first = piiScanReflex.sanitize("s1", "u1", input);
        String second = piiScanReflex.sanitize("s1", "u1", first);
        Assertions.assertEquals("Email: [REDACTED-EMAIL]", first);
        Assertions.assertEquals("Email: [REDACTED-EMAIL]", second);
    }

    @Test
    void testPhoneIntlExact() {
        String input = "US number +14155552671";
        String sanitized = piiScanReflex.sanitize("s1", "u1", input);
        Assertions.assertEquals("US number [REDACTED-PHONE]", sanitized);
    }

    @Test
    void testPhoneIndiaExact() {
        // Compact format: +919876543210
        Assertions.assertEquals(
                "Call me at [REDACTED-PHONE]", piiScanReflex.sanitize("s1", "u1", "Call me at +919876543210"));
    }

    @Test
    void testPhoneIndiaSpacedExact() {
        // Spaced format: +91-9876543210
        Assertions.assertEquals(
                "Call me at [REDACTED-PHONE]", piiScanReflex.sanitize("s1", "u1", "Call me at +91-9876543210"));
    }

    @Test
    void testAadhaarCompactDoesNotMatchPhoneNumber() {
        // +919876543210 must be PHONE, not AADHAAR
        String input = "Call +919876543210";
        String sanitized = piiScanReflex.sanitize("s1", "u1", input);
        Assertions.assertTrue(
                sanitized.contains("[REDACTED-PHONE]"), "Indian phone with +91 prefix must be PHONE not AADHAAR");
        Assertions.assertFalse(
                sanitized.contains("[REDACTED-AADHAAR]"), "Aadhaar redaction must not fire on phone numbers");
    }

    @Test
    void testMatchesIsFalseByDefault() {
        // piiMode defaults to REDACT_AND_FORWARD, so matches should be false (redact mode doesn't block)
        Assertions.assertFalse(piiScanReflex.matches("Email is test@example.com"));
    }

    @Test
    void testIndianPII() {
        // GSTIN
        Assertions.assertTrue(piiScanReflex
                .sanitize("test", "user", "My GST is 22AAAAA0000A1Z5")
                .contains("[REDACTED-GSTIN]"));

        // Passport
        Assertions.assertTrue(
                piiScanReflex.sanitize("test", "user", "Passport: Z1234567").contains("[REDACTED-PASSPORT]"));

        // DOB
        Assertions.assertTrue(
                piiScanReflex.sanitize("test", "user", "DOB: 1990-01-01").contains("[REDACTED-DOB]"));
    }

    @Test
    void testGlobalPII() {
        // IPv6
        Assertions.assertTrue(piiScanReflex
                .sanitize("test", "user", "Address: 2001:0db8:85a3:0000:0000:8a2e:0370:7334")
                .contains("[REDACTED-IP]"));
    }

    @Test
    void testUpiVpaRedaction() {
        // UPI VPA should be redacted
        Assertions.assertTrue(
                piiScanReflex.sanitize("test", "user", "Pay me at srini@okaxis").contains("[REDACTED-UPI]"));

        // BUT valid email TLDs should NOT be caught by UPI (redundant since email regex runs first, but negative
        // lookahead verifies logic)
        String emailRes = piiScanReflex.sanitize("test", "user", "Contact me at srini@google.com");
        // It's already redacted as EMAIL by the existing PiiScanReflex logic
        Assertions.assertTrue(emailRes.contains("[REDACTED-EMAIL]"));
    }
}
