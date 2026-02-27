package com.arcclaw.core.sandbox;

import java.io.IOException;
import java.util.List;
import java.util.Map;

/**
 * Strategy interface for secure OS-level delivery of secrets to sandboxed processes.
 * Implementations must ensure that secret material NEVER resides on physical disk
 * and is explicitly zeroed from JVM memory when {@link #close()} is called.
 */
public interface SecretTransport extends AutoCloseable {

    /**
     * Prepares the transport mechanism and returns the specific ProcessBuilder
     * CLI arguments required to inject the secrets (e.g. {@code --env-file <path>}).
     *
     * @param secrets Map of environment variable name to pure raw secret bytes.
     *                Implementation MUST assume ownership of these bytes and zero them
     *                on close or after consumption.
     * @return Ordered list of arguments to insert into the ProcessBuilder command.
     * @throws IOException If the transport OS-level initialization fails.
     */
    List<String> prepare(Map<String, byte[]> secrets) throws IOException;

    /**
     * Completes secret delivery dynamically after the process has started
     * (e.g. writing bytes to a redirected stdin or an FFM pipe).
     * <p>
     * For statically prepared transports (like tmpfs env-files), this is a no-op.
     *
     * @param process The actively running child process reference.
     * @throws IOException If streaming dynamically injection fails.
     */
    void deliver(Process process) throws IOException;

    /**
     * Erases all memory-resident buffers/arrays via zero-fill and cleans up
     * any OS-level resources (tmpfs files, uninherited pipes, etc.).
     */
    @Override
    void close();
}
