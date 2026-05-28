package com.arcclaw.persistence.common.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import java.io.Serializable;
import java.util.Objects;

import com.arcclaw.core.domain.model.EncryptedSecret;

/**
 * Encrypted secret keys stored in SQL databases, e.g. OpenAI/Telegram keys.
 */
@Entity
@Table(name = "encrypted_secrets")
public class EncryptedSecretEntity implements EncryptedSecret {

    @EmbeddedId
    private EncryptedSecretId id;

    @Column(name = "encryption_key_version", nullable = false)
    private int encryptionKeyVersion;

    @Column(nullable = false)
    private byte[] ciphertext;

    @Column(nullable = false)
    private byte[] nonce;

    public EncryptedSecretEntity() {
        this.id = new EncryptedSecretId();
    }

    public EncryptedSecretEntity(String sessionId, String keyName, int version, byte[] ciphertext, byte[] nonce) {
        this.id = new EncryptedSecretId(sessionId, keyName);
        this.encryptionKeyVersion = version;
        this.ciphertext = ciphertext;
        this.nonce = nonce;
    }

    @Override
    public String getSessionId() {
        return id.sessionId;
    }

    public void setSessionId(String sessionId) {
        id.sessionId = sessionId;
    }

    @Override
    public String getKeyName() {
        return id.keyName;
    }

    public void setKeyName(String keyName) {
        id.keyName = keyName;
    }

    @Override
    public int getEncryptionKeyVersion() {
        return encryptionKeyVersion;
    }

    public void setEncryptionKeyVersion(int version) {
        this.encryptionKeyVersion = version;
    }

    @Override
    public byte[] getCiphertext() {
        return ciphertext;
    }

    public void setCiphertext(byte[] ciphertext) {
        this.ciphertext = ciphertext;
    }

    @Override
    public byte[] getNonce() {
        return nonce;
    }

    public void setNonce(byte[] nonce) {
        this.nonce = nonce;
    }

    @Embeddable
    public static class EncryptedSecretId implements Serializable {
        @Column(name = "session_id", nullable = false)
        private String sessionId;

        @Column(name = "key_name", nullable = false)
        private String keyName;

        public EncryptedSecretId() {}

        public EncryptedSecretId(String sessionId, String keyName) {
            this.sessionId = sessionId;
            this.keyName = keyName;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof EncryptedSecretId that)) return false;
            return Objects.equals(sessionId, that.sessionId) && Objects.equals(keyName, that.keyName);
        }

        @Override
        public int hashCode() {
            return Objects.hash(sessionId, keyName);
        }
    }
}
