package com.amsha.core.memory.tiered;

import jakarta.enterprise.util.AnnotationLiteral;

import com.amsha.persistence.api.TieredBackend;

/**
 * Annotation literal for programmatic CDI-qualified selection of a
 * {@code TieredMemoryStorePort} backend.
 *
 * <p>{@code implements TieredBackend} is required by the CDI spec (Jakarta CDI §11.1):
 * {@code AnnotationLiteral<T>} needs the concrete class to implement the annotation
 * interface so the container can call {@code value()} during programmatic lookup.
 * This is the canonical pattern for CDI qualifier literals — do not refactor.
 *
 * <pre>{@code
 *   Instance<TieredMemoryStorePort> stores = ...;
 *   TieredMemoryStorePort sqlite = stores.select(new TieredBackendLiteral("sqlite")).get();
 * }</pre>
 */
@SuppressWarnings({"java:S2176", "java:S1206"})
public final class TieredBackendLiteral extends AnnotationLiteral<TieredBackend> implements TieredBackend {

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
