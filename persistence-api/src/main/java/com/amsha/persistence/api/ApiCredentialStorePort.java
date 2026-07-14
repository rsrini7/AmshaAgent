package com.amsha.persistence.api;

import java.util.List;
import java.util.Optional;

/**
 * Port for managing API credentials mapped to stable user identities.
 */
public interface ApiCredentialStorePort {
    boolean save(ApiCredentialDto credential);

    Optional<ApiCredentialDto> findById(String credentialId);

    Optional<ApiCredentialDto> findByKeyDigest(String keyDigest);

    List<ApiCredentialDto> listByUserId(String userId);

    int deleteById(String credentialId);
}
