package com.arcclaw.core.memory.tiered;

import jakarta.enterprise.util.AnnotationLiteral;

/**
 * Annotation literal for programmatic CDI-qualified selection of a
 * {@code TieredMemoryStorePort} backend.
 *
 * <pre>{@code
 *   Instance<TieredMemoryStorePort> stores = ...;
 *   TieredMemoryStorePort sqlite = stores.select(new TieredBackendLiteral("sqlite")).get();
 * }</pre>
 */
public final class TieredBackendLiteral extends AnnotationLiteral<TieredBackend>
    implements TieredBackend {

    private static final long serialVersionUID = 1L;

    private final String value;

    public TieredBackendLiteral(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Backend identifier must not be blank");
        }
        this.value = value;
    }

    @Override
    public String value() {
        return value;
    }
}
