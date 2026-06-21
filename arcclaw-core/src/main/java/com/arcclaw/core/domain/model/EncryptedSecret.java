package com.arcclaw.core.domain.model;

public interface EncryptedSecret {

    String getSessionId();

    void setSessionId(String sessionId);

    String getKeyName();

    void setKeyName(String keyName);

    int getEncryptionKeyVersion();

    void setEncryptionKeyVersion(int encryptionKeyVersion);

    byte[] getCiphertext();

    void setCiphertext(byte[] ciphertext);

    byte[] getNonce();

    void setNonce(byte[] nonce);

    // ── Wither methods ────────────────────────────────────────────────────

    default EncryptedSecret withSessionId(String s) {
        setSessionId(s);
        return this;
    }

    default EncryptedSecret withKeyName(String k) {
        setKeyName(k);
        return this;
    }

    default EncryptedSecret withEncryptionKeyVersion(int v) {
        setEncryptionKeyVersion(v);
        return this;
    }

    default EncryptedSecret withCiphertext(byte[] c) {
        setCiphertext(c);
        return this;
    }

    default EncryptedSecret withNonce(byte[] n) {
        setNonce(n);
        return this;
    }
}
