package com.amsha.persistence.sqlite.jdbi;

import org.jdbi.v3.core.argument.ArgumentFactory;

/**
 * Test-scope bridge to {@link JdbiProducer}'s package-private argument factories.
 *
 * <p>The factories are package-private because only the producer registers them, so a test in a
 * sibling package cannot name them. Exposing them here lets such a test build a plain
 * {@link org.jdbi.v3.core.Jdbi} that binds the same types production does, instead of
 * reimplementing the encodings and quietly testing the copy.
 */
public final class TestJdbiArguments {

    private TestJdbiArguments() {}

    /** The production {@code float[]} factory — little-endian blob, as sqlite-vec expects. */
    public static ArgumentFactory floatArray() {
        return new JdbiProducer.FloatArrayArgumentFactory();
    }
}
