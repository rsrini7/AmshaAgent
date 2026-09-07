package com.amsha.core.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class CredentialDetectorTest {

    private CredentialDetector detector;

    @BeforeEach
    void setUp() {
        detector = new DefaultCredentialDetector();
    }

    @Test
    void cleanTextPasses() {
        assertFalse(detector.containsCredentials("Tell me a story about a dragon."));
        assertFalse(detector.containsCredentials("What is the capital of France?"));
        assertFalse(detector.containsCredentials(null));
        assertFalse(detector.containsCredentials(""));

        CredentialDetectionResult res = detector.detect("Explain quantum entanglement.");
        assertFalse(res.detected());
        assertEquals("NONE", res.credentialType());
    }

    @Test
    void detectsOpenAiKey() {
        String prompt = "Here is my OpenAI key sk-proj-1234567890abcdef12345678 for testing.";
        assertTrue(detector.containsCredentials(prompt));
        CredentialDetectionResult res = detector.detect(prompt);
        assertTrue(res.detected());
        assertEquals("PROVIDER_KEY", res.credentialType());
    }

    @Test
    void detectsGithubPat() {
        String prompt = "Connect with token ghp_12345678901234567890abcdef.";
        assertTrue(detector.containsCredentials(prompt));
        assertEquals("PROVIDER_KEY", detector.detect(prompt).credentialType());
    }

    @Test
    void detectsAwsKey() {
        String prompt = "Using AWS account with AKIAIOSFODNN7EXAMPLE key.";
        assertTrue(detector.containsCredentials(prompt));
        assertEquals("PROVIDER_KEY", detector.detect(prompt).credentialType());
    }

    @Test
    void detectsGoogleApiKey() {
        String prompt = "AIzaSyD-1234567890abcdefghijklmnopqrstuv";
        assertTrue(detector.containsCredentials(prompt));
        assertEquals("PROVIDER_KEY", detector.detect(prompt).credentialType());
    }

    @Test
    void detectsBearerToken() {
        String prompt = "Request header: Bearer ya29.a0AfH6SM...";
        assertTrue(detector.containsCredentials(prompt));
        assertEquals("BEARER_TOKEN", detector.detect(prompt).credentialType());
    }

    @Test
    void detectsAuthorizationHeader() {
        String prompt = "Authorization: Basic dXNlcjpwYXNz";
        assertTrue(detector.containsCredentials(prompt));
        assertEquals("AUTHORIZATION_HEADER", detector.detect(prompt).credentialType());
    }

    @Test
    void detectsAmshaApiKey() {
        String prompt = "x-amsha-api-key: key-internal-secret-value";
        assertTrue(detector.containsCredentials(prompt));
        assertEquals("API_KEY_HEADER", detector.detect(prompt).credentialType());
    }

    @Test
    void detectsPasswordAndSecret() {
        assertTrue(detector.containsCredentials("password = super_secret_pass"));
        assertTrue(detector.containsCredentials("api_key: abcdef123456"));
        assertTrue(detector.containsCredentials("client_secret = topsecret"));
        assertTrue(detector.containsCredentials("private_key: 1234567890"));
        assertEquals(
                "PASSWORD_OR_SECRET",
                detector.detect("password = super_secret_pass").credentialType());
    }

    @Test
    void detectsSigningKey() {
        String prompt = "hmac_key = 9876543210fedcba";
        assertTrue(detector.containsCredentials(prompt));
        assertEquals("SIGNING_KEY", detector.detect(prompt).credentialType());
    }

    @Test
    void detectsPrivateKeyBlock() {
        String prompt = "-----BEGIN RSA PRIVATE KEY-----\nMIIEowIBAAKCAQEA0...\n-----END RSA PRIVATE KEY-----";
        assertTrue(detector.containsCredentials(prompt));
        assertEquals("PRIVATE_KEY_BLOCK", detector.detect(prompt).credentialType());
    }

    @Test
    void detectsJwtToken() {
        String prompt = "Token: eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJzdWIiOiIxMjM0NTY3ODkwIn0.dozGzN_ce";
        assertTrue(detector.containsCredentials(prompt));
        assertEquals("JWT_TOKEN", detector.detect(prompt).credentialType());
    }

    @Test
    void detectsCloudCredentialAndCustomHeaders() {
        String azurePrompt = "AccountKey=abcdefghijklmnopqrstuvwxyz0123456789+/==;";
        assertTrue(detector.containsCredentials(azurePrompt));
        assertEquals("CLOUD_CREDENTIAL", detector.detect(azurePrompt).credentialType());

        String headerPrompt = "x-api-key: secret-api-key-12345";
        assertTrue(detector.containsCredentials(headerPrompt));
        assertEquals("API_KEY_HEADER", detector.detect(headerPrompt).credentialType());
    }

    @Test
    void legitimateTextDoesNotTriggerFalsePositives() {
        assertFalse(detector.containsCredentials("Please advise the bearer of this note"));
        assertFalse(detector.containsCredentials("Run pwd: it prints cwd"));
        assertFalse(detector.containsCredentials("secret: of my success"));
        assertFalse(detector.containsCredentials("The token count was 450."));
    }

    @Test
    void detectsAdditionalProviderTokensAndTemporaryKeys() {
        assertTrue(detector.containsCredentials("Session token ASIAIOSFODNN7EXAMPLE key"));
        assertEquals("PROVIDER_KEY", detector.detect("ASIAIOSFODNN7EXAMPLE").credentialType());

        assertTrue(detector.containsCredentials("glpat-abcdef1234567890"));
        assertTrue(detector.containsCredentials("npm_abcdef1234567890"));
        assertTrue(detector.containsCredentials("pypi-AgEIcHlwaS5vcmcCJabcdef"));
        assertTrue(detector.containsCredentials("hf_abcdef1234567890"));
        assertTrue(detector.containsCredentials("xapp-abcdef1234567890"));
        assertTrue(detector.containsCredentials("rk_live_abcdef1234567890"));

        assertTrue(detector.containsCredentials("Clone https://user:secretPass123@github.com/repo.git"));
        assertEquals(
                "BASIC_AUTH_URL",
                detector.detect("https://user:secretPass123@github.com/repo.git")
                        .credentialType());

        assertTrue(detector.containsCredentials(
                "-----BEGIN PGP PRIVATE KEY BLOCK-----\nsecret\n-----END PGP PRIVATE KEY BLOCK-----"));
    }

    @Test
    void largePayloadScansWithoutReDoS() {
        String longText = "a".repeat(100_000);
        long start = System.currentTimeMillis();
        CredentialDetectionResult res = detector.detect(longText);
        long duration = System.currentTimeMillis() - start;
        assertFalse(res.detected());
        assertTrue(duration < 500, "Scanning large payload should take < 500ms, took " + duration + "ms");
    }

    @Test
    void compactConstructorEnforcesInvariants() {
        CredentialDetectionResult clean = new CredentialDetectionResult(false, "PROVIDER_KEY");
        assertEquals("NONE", clean.credentialType());
        assertFalse(clean.detected());

        CredentialDetectionResult blankType = new CredentialDetectionResult(true, "  ");
        assertEquals("UNKNOWN_CREDENTIAL", blankType.credentialType());
        assertTrue(blankType.detected());
    }
}
