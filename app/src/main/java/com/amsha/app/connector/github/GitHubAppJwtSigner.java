package com.amsha.app.connector.github;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.interfaces.RSAPrivateKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;

/** Creates short-lived RS256 GitHub App JWTs from vault-owned RSA PEM bytes. */
final class GitHubAppJwtSigner {

    private static final int MAX_PEM_BYTES = 32_768;
    private static final byte[] PKCS8_BEGIN = ascii("-----BEGIN PRIVATE KEY-----");
    private static final byte[] PKCS8_END = ascii("-----END PRIVATE KEY-----");
    private static final byte[] PKCS1_BEGIN = ascii("-----BEGIN RSA PRIVATE KEY-----");
    private static final byte[] PKCS1_END = ascii("-----END RSA PRIVATE KEY-----");
    private static final byte[] RSA_ALGORITHM_IDENTIFIER = {
        0x30, 0x0d, 0x06, 0x09, 0x2a, (byte) 0x86, 0x48, (byte) 0x86, (byte) 0xf7, 0x0d, 0x01, 0x01, 0x01, 0x05, 0x00
    };

    private GitHubAppJwtSigner() {}

    static void validatePrivateKey(byte[] pemBytes) {
        parsePrivateKey(pemBytes);
    }

    static String createJwt(String appId, byte[] pemBytes, Instant now) {
        if (appId == null || !appId.matches("[0-9]{1,20}") || now == null) {
            throw new IllegalArgumentException("GitHub App configuration is invalid");
        }
        PrivateKey key = parsePrivateKey(pemBytes);
        byte[] headerBytes = "{\"alg\":\"RS256\",\"typ\":\"JWT\"}".getBytes(StandardCharsets.US_ASCII);
        byte[] payloadBytes = ("{\"iat\":" + (now.getEpochSecond() - 60) + ",\"exp\":" + (now.getEpochSecond() + 300)
                        + ",\"iss\":\"" + appId + "\"}")
                .getBytes(StandardCharsets.US_ASCII);
        byte[] signingBytes = null;
        byte[] signatureBytes = null;
        try {
            String unsigned = Base64.getUrlEncoder().withoutPadding().encodeToString(headerBytes)
                    + "."
                    + Base64.getUrlEncoder().withoutPadding().encodeToString(payloadBytes);
            signingBytes = unsigned.getBytes(StandardCharsets.US_ASCII);
            Signature signature = Signature.getInstance("SHA256withRSA");
            signature.initSign(key);
            signature.update(signingBytes);
            signatureBytes = signature.sign();
            return unsigned + "." + Base64.getUrlEncoder().withoutPadding().encodeToString(signatureBytes);
        } catch (GeneralSecurityException _) {
            throw new IllegalArgumentException("GitHub App signing failed");
        } finally {
            Arrays.fill(headerBytes, (byte) 0);
            Arrays.fill(payloadBytes, (byte) 0);
            if (signingBytes != null) {
                Arrays.fill(signingBytes, (byte) 0);
            }
            if (signatureBytes != null) {
                Arrays.fill(signatureBytes, (byte) 0);
            }
        }
    }

    private static PrivateKey parsePrivateKey(byte[] pemBytes) {
        if (pemBytes == null || pemBytes.length == 0 || pemBytes.length > MAX_PEM_BYTES) {
            throw new IllegalArgumentException("GitHub App private key is invalid");
        }
        byte[] encoded = null;
        byte[] pkcs8 = null;
        try {
            boolean pkcs1 = contains(pemBytes, PKCS1_BEGIN);
            encoded = decodePem(pemBytes, pkcs1 ? PKCS1_BEGIN : PKCS8_BEGIN, pkcs1 ? PKCS1_END : PKCS8_END);
            pkcs8 = pkcs1 ? wrapPkcs1(encoded) : encoded.clone();
            PrivateKey key = KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(pkcs8));
            if (!(key instanceof RSAPrivateKey rsaKey)
                    || rsaKey.getModulus().bitLength() < 2048
                    || rsaKey.getModulus().bitLength() > 8192) {
                throw new IllegalArgumentException("RSA key size is not supported");
            }
            return key;
        } catch (GeneralSecurityException | IllegalArgumentException _) {
            throw new IllegalArgumentException("GitHub App private key is invalid");
        } finally {
            if (encoded != null) {
                Arrays.fill(encoded, (byte) 0);
            }
            if (pkcs8 != null) {
                Arrays.fill(pkcs8, (byte) 0);
            }
        }
    }

    private static byte[] decodePem(byte[] pem, byte[] begin, byte[] end) {
        int start = indexOf(pem, begin);
        int finish = indexOf(pem, end);
        if (start < 0 || finish <= start) {
            throw new IllegalArgumentException("PEM armor is missing");
        }
        ByteArrayOutputStream compact = new ByteArrayOutputStream(finish - start);
        for (int index = start + begin.length; index < finish; index++) {
            int value = pem[index] & 0xff;
            if (Character.isWhitespace(value)) {
                continue;
            }
            if (!isBase64(value)) {
                throw new IllegalArgumentException("PEM encoding is invalid");
            }
            compact.write(value);
        }
        byte[] encodedText = compact.toByteArray();
        try {
            return Base64.getDecoder().decode(encodedText);
        } finally {
            Arrays.fill(encodedText, (byte) 0);
        }
    }

    private static byte[] wrapPkcs1(byte[] pkcs1) {
        byte[] octetString = der(0x04, pkcs1);
        ByteArrayOutputStream sequence = new ByteArrayOutputStream();
        sequence.writeBytes(new byte[] {0x02, 0x01, 0x00});
        sequence.writeBytes(RSA_ALGORITHM_IDENTIFIER);
        sequence.writeBytes(octetString);
        Arrays.fill(octetString, (byte) 0);
        return der(0x30, sequence.toByteArray());
    }

    private static byte[] der(int tag, byte[] content) {
        ByteArrayOutputStream result = new ByteArrayOutputStream(content.length + 6);
        result.write(tag);
        writeLength(result, content.length);
        result.writeBytes(content);
        byte[] encoded = result.toByteArray();
        Arrays.fill(content, (byte) 0);
        return encoded;
    }

    private static void writeLength(ByteArrayOutputStream output, int length) {
        if (length < 128) {
            output.write(length);
            return;
        }
        int bytes = 0;
        int remaining = length;
        while (remaining > 0) {
            bytes++;
            remaining >>>= 8;
        }
        output.write(0x80 | bytes);
        for (int index = bytes - 1; index >= 0; index--) {
            output.write((length >>> (index * 8)) & 0xff);
        }
    }

    private static boolean contains(byte[] bytes, byte[] pattern) {
        return indexOf(bytes, pattern) >= 0;
    }

    private static int indexOf(byte[] bytes, byte[] pattern) {
        outer:
        for (int start = 0; start <= bytes.length - pattern.length; start++) {
            for (int offset = 0; offset < pattern.length; offset++) {
                if (bytes[start + offset] != pattern[offset]) {
                    continue outer;
                }
            }
            return start;
        }
        return -1;
    }

    private static boolean isBase64(int value) {
        return (value >= 'A' && value <= 'Z')
                || (value >= 'a' && value <= 'z')
                || (value >= '0' && value <= '9')
                || value == '+'
                || value == '/'
                || value == '=';
    }

    private static byte[] ascii(String value) {
        return value.getBytes(StandardCharsets.US_ASCII);
    }
}
