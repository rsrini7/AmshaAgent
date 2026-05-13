package com.arcclaw.core.security;

import java.io.Serializable;
import java.util.Objects;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import com.arcclaw.core.domain.model.EncryptedSecret;

@Entity
@Table(name = "encrypted_secrets")
public class EncryptedSecretEntity implements EncryptedSecret {

    @EmbeddedId
    private EncryptedSecretId id;

    @Column(name = "encryption_key_version", nullable = false, columnDefinition = "integer default 1")
    private int encryptionKeyVersion = 1;

    @Column(nullable = false)
    private byte[] ciphertext;

    @Column(nullable = false)
    private byte[] nonce;

    @Override
    public String getSessionId() {
        return id != null ? id.sessionId : null;
    }

    @Override
    public void setSessionId(String sessionId) {
        if (id == null) id = new EncryptedSecretId();
        id.sessionId = sessionId;
    }

    @Override
    public String getKeyName() {
        return id != null ? id.keyName : null;
    }

    @Override
    public void setKeyName(String keyName) {
        if (id == null) id = new EncryptedSecretId();
        id.keyName = keyName;
    }

    @Override
    public int getEncryptionKeyVersion() {
        return encryptionKeyVersion;
    }

    @Override
    public void setEncryptionKeyVersion(int encryptionKeyVersion) {
        this.encryptionKeyVersion = encryptionKeyVersion;
    }

    @Override
    public byte[] getCiphertext() {
        return ciphertext;
    }

    @Override
    public void setCiphertext(byte[] ciphertext) {
        this.ciphertext = ciphertext;
    }

    @Override
    public byte[] getNonce() {
        return nonce;
    }

    @Override
    public void setNonce(byte[] nonce) {
        this.nonce = nonce;
    }

    // Composite Key Class
    @Embeddable
    public static class EncryptedSecretId implements Serializable {
        @Column(name = "session_id")
        public String sessionId;

        @Column(name = "key_name")
        public String keyName;

        public EncryptedSecretId() {
        }

        public EncryptedSecretId(String sessionId, String keyName) {
            this.sessionId = sessionId;
            this.keyName = keyName;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o)
                return true;
            if (o == null || getClass() != o.getClass())
                return false;
            EncryptedSecretId that = (EncryptedSecretId) o;
            return Objects.equals(sessionId, that.sessionId) &&
                    Objects.equals(keyName, that.keyName);
        }

        @Override
        public int hashCode() {
            return Objects.hash(sessionId, keyName);
        }
    }
}
