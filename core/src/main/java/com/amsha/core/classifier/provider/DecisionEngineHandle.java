package com.amsha.core.classifier.provider;

import java.util.Objects;

import com.amsha.classifier.api.DecisionEngine;

/**
 * An engine built for one evaluation, together with any transport this factory created for it.
 *
 * <p>{@link DecisionEngine} is a functional interface and is not {@link AutoCloseable}, so a caller
 * cannot close an engine generically. Both lane implementations <em>are</em> closeable — they drain
 * in-flight evaluations and then reject new ones — and the self-hosted lane additionally needs a
 * {@link java.net.http.HttpClient} that this factory creates and therefore owns.
 *
 * <p>Closing is best-effort and never throws: a failure to drain must not mask the evaluation outcome
 * the caller already has, and there is nothing a caller can do about a failed drain. Both resources are
 * closed even when the first one fails.
 */
public final class DecisionEngineHandle implements AutoCloseable {

    private final DecisionEngine engine;
    private final AutoCloseable ownedTransport;

    DecisionEngineHandle(DecisionEngine engine, AutoCloseable ownedTransport) {
        this.engine = Objects.requireNonNull(engine, "engine");
        this.ownedTransport = ownedTransport;
    }

    /** The engine to evaluate against. */
    public DecisionEngine engine() {
        return engine;
    }

    @Override
    public void close() {
        closeQuietly(engine);
        closeQuietly(ownedTransport);
    }

    private static void closeQuietly(Object closeable) {
        if (closeable instanceof AutoCloseable resource) {
            try {
                resource.close();
            } catch (Exception _) {
                // Best-effort teardown: swallowing here keeps a drain failure from replacing the
                // evaluation result the caller is about to return.
            }
        }
    }
}
