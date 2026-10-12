package com.amsha.persistence.api;

/**
 * The build-time property that selects the active persistence backend, and the ids it accepts.
 *
 * <p>These are {@code static final String} compile-time constants on purpose: the values are
 * referenced from {@code @IfBuildProperty} and {@code @ConfigProperty} annotations, whose
 * attributes must be constant expressions. Keeping the key and its ids in one place stops a typo in
 * any single annotated bean from silently activating or deactivating it — a missing
 * {@code amsha.persistence.backend} value de-activates the backend's beans, which surfaces as a
 * runtime wiring failure rather than a build error.
 *
 * <p>Because the constants are inlined by the compiler, using them produces bytecode identical to
 * the previous string literals.
 */
public final class PersistenceBackend {

    /** Build-time property that selects which persistence beans are active. */
    public static final String PROPERTY = "amsha.persistence.backend";

    /** SQLite backend; the default when the property is absent. */
    public static final String SQLITE = "sqlite";

    /** PostgreSQL backend. */
    public static final String POSTGRES = "postgres";

    /** Filesystem backend. */
    public static final String FILESYSTEM = "fs";

    /** Historical alias for {@link #FILESYSTEM} still accepted by runtime checks. */
    public static final String FILESYSTEM_ALIAS = "filesystem";

    private PersistenceBackend() {}

    /**
     * @return true when {@code id} names the filesystem backend under either accepted spelling.
     */
    public static boolean isFilesystem(String id) {
        return FILESYSTEM.equalsIgnoreCase(id) || FILESYSTEM_ALIAS.equalsIgnoreCase(id);
    }
}
