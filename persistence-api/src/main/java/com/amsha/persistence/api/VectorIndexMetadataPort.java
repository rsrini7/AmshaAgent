package com.amsha.persistence.api;

import java.util.Map;
import java.util.Optional;

/** Backend-neutral persistence contract for durable/rebuildable vector-index descriptors. */
public interface VectorIndexMetadataPort {

    VectorIndexCompatibility verifyOrInitialize(
            VectorIndexNamespace namespace, EmbeddingIndexDescriptor activeDescriptor, boolean indexEmpty);

    VectorIndexCompatibility markUnavailable(VectorIndexNamespace namespace, EmbeddingIndexDescriptor activeDescriptor);

    Optional<EmbeddingIndexDescriptor> find(VectorIndexNamespace namespace);

    boolean isVectorEnabled(VectorIndexNamespace namespace);

    Map<VectorIndexNamespace, VectorIndexCompatibility> compatibilitySnapshot();
}
