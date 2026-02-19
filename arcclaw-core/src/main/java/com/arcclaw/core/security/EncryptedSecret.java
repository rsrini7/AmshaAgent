package com.arcclaw.core.security;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;

import java.io.Serializable;
import java.util.Objects;

@Entity
@Table(name = "encrypted_secrets")
public class EncryptedSecret extends PanacheEntityBase {

    @EmbeddedId
    public EncryptedSecretId id;

    @Lob
    @Column(nullable = false)
    public byte[] ciphertext;

    @Lob
    @Column(nullable = false)
    public byte[] nonce;

    @Column(nullable = false)
    public int version;

    public static EncryptedSecret findByKey(String sessionId, String keyName) {
        return find("id.sessionId = ?1 and id.keyName = ?2", sessionId, keyName).firstResult();
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
