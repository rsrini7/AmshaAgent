package com.arcclaw.core.security;

import java.io.Serializable;
import java.util.Objects;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

@Entity
@Table(name = "encrypted_secrets")
public class EncryptedSecret {

    @EmbeddedId
    public EncryptedSecretId id;

    @Column(name = "encryption_key_version", nullable = false, columnDefinition = "integer default 1")
    private int encryptionKeyVersion = 1;

    @Column(nullable = false)
    public byte[] ciphertext;

    @Column(nullable = false)
    public byte[] nonce;

    public int getEncryptionKeyVersion() {
        return encryptionKeyVersion;
    }

    public void setEncryptionKeyVersion(int encryptionKeyVersion) {
        this.encryptionKeyVersion = encryptionKeyVersion;
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
