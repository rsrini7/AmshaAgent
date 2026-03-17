package com.arcclaw.mcp.transport;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

/**
 * Utility for writing Newline-Delimited JSON (NDJSON) to an OutputStream.
 *
 * MCP stdio transport uses NDJSON-framed JSON-RPC 2.0.
 * Every outbound message MUST be terminated with '\n' and the stream flushed.
 * Failure to frame causes the remote MCP server to hang indefinitely.
 *
 * This class enforces the contract at the type level — callers cannot
 * write a message without also appending '\n' and flushing.
 *
 * @see Architecture Nuance #20
 */
public final class NdjsonWriter {

    private NdjsonWriter() {
        throw new UnsupportedOperationException("NdjsonWriter is a static utility class");
    }

    /**
     * Writes a single JSON-RPC message as one NDJSON line.
     * Appends '\n' and flushes the stream — both are mandatory per protocol.
     *
     * @param out  the process OutputStream (never null)
     * @param json the serialized JSON string (never null, never contains embedded newlines)
     * @throws IOException if the write or flush fails
     */
    public static void writeLine(OutputStream out, String json) throws IOException {
        Objects.requireNonNull(out,  "OutputStream must not be null");
        Objects.requireNonNull(json, "JSON payload must not be null");
        out.write(json.getBytes(StandardCharsets.UTF_8));
        out.write('\n');
        out.flush();
    }
}
