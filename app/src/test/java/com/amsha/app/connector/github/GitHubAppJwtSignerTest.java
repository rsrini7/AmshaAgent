package com.amsha.app.connector.github;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.time.Instant;
import java.util.Base64;

import org.junit.jupiter.api.Test;

class GitHubAppJwtSignerTest {

    @Test
    void signsShortLivedAppJwtWithoutEmbeddingPrivateKeyMaterial() throws Exception {
        KeyPair keyPair = rsaKeyPair(2048);
        byte[] pem = pem(keyPair);
        Instant issuedAt = Instant.parse("2026-10-29T12:00:00Z");

        String jwt = GitHubAppJwtSigner.createJwt("12345", pem, issuedAt);
        String[] parts = jwt.split("\\.");
        byte[] signingInput = (parts[0] + "." + parts[1]).getBytes(StandardCharsets.US_ASCII);
        Signature verifier = Signature.getInstance("SHA256withRSA");
        verifier.initVerify(keyPair.getPublic());
        verifier.update(signingInput);

        assertTrue(verifier.verify(Base64.getUrlDecoder().decode(parts[2])));
        assertEquals(
                "{\"alg\":\"RS256\",\"typ\":\"JWT\"}",
                new String(Base64.getUrlDecoder().decode(parts[0]), StandardCharsets.US_ASCII));
        String claims = new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.US_ASCII);
        assertTrue(claims.contains("\"iss\":\"12345\""));
        assertTrue(claims.contains("\"iat\":1793275140"));
        assertTrue(claims.contains("\"exp\":1793275500"));
        assertTrue(!jwt.contains(
                Base64.getEncoder().encodeToString(keyPair.getPrivate().getEncoded())));
    }

    @Test
    void rejectsInvalidAndUndersizedRsaKeys() throws Exception {
        assertThrows(
                IllegalArgumentException.class,
                () -> GitHubAppJwtSigner.validatePrivateKey("not a key".getBytes(StandardCharsets.UTF_8)));
        assertThrows(
                IllegalArgumentException.class, () -> GitHubAppJwtSigner.validatePrivateKey(pem(rsaKeyPair(1024))));
    }

    private static KeyPair rsaKeyPair(int bits) throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(bits);
        return generator.generateKeyPair();
    }

    private static byte[] pem(KeyPair keyPair) {
        String encoded = Base64.getMimeEncoder(64, new byte[] {'\n'})
                .encodeToString(keyPair.getPrivate().getEncoded());
        return ("-----BEGIN PRIVATE KEY-----\n" + encoded + "\n-----END PRIVATE KEY-----\n")
                .getBytes(StandardCharsets.US_ASCII);
    }
}
