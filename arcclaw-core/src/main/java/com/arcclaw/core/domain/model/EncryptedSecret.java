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
}
