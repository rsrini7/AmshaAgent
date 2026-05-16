package com.arcclaw.core.domain.model;

import com.arcclaw.persistence.api.SecretDto;

public final class EncryptedSecretRecord implements EncryptedSecret {

    private String sessionId;
    private String keyName;
    private int encryptionKeyVersion;
    private byte[] ciphertext;
    private byte[] nonce;

    public EncryptedSecretRecord() {}

    public static EncryptedSecretRecord fromDto(SecretDto dto) {
        EncryptedSecretRecord r = new EncryptedSecretRecord();
        r.sessionId = dto.sessionId();
        r.keyName = dto.keyName();
        r.encryptionKeyVersion = dto.encryptionKeyVersion();
        r.ciphertext = dto.ciphertext();
        r.nonce = dto.nonce();
        return r;
    }

    @Override public String getSessionId() { return sessionId; }
    @Override public void setSessionId(String sessionId) { this.sessionId = sessionId; }
    @Override public String getKeyName() { return keyName; }
    @Override public void setKeyName(String keyName) { this.keyName = keyName; }
    @Override public int getEncryptionKeyVersion() { return encryptionKeyVersion; }
    @Override public void setEncryptionKeyVersion(int encryptionKeyVersion) { this.encryptionKeyVersion = encryptionKeyVersion; }
    @Override public byte[] getCiphertext() { return ciphertext; }
    @Override public void setCiphertext(byte[] ciphertext) { this.ciphertext = ciphertext; }
    @Override public byte[] getNonce() { return nonce; }
    @Override public void setNonce(byte[] nonce) { this.nonce = nonce; }
}
