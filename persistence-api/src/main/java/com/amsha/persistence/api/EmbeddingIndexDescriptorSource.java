package com.amsha.persistence.api;

import java.util.Optional;

/** Supplies the descriptor pinned by the active, compatible embedding-provider slot set. */
public interface EmbeddingIndexDescriptorSource {

    Optional<EmbeddingIndexDescriptor> activeDescriptor();
}
