package com.amsha.mcp.transport;

import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;

public final class ResolvedMcpHeaders implements AutoCloseable {

    private final List<ResolvedMcpHeader> headers;

    ResolvedMcpHeaders(List<ResolvedMcpHeader> headers) {
        this.headers = List.copyOf(headers);
    }

    static ResolvedMcpHeaders empty() {
        return new ResolvedMcpHeaders(List.of());
    }

    void applyTo(HttpRequest.Builder req) {
        for (ResolvedMcpHeader header : headers) {
            req.header(header.name(), new String(header.value(), StandardCharsets.UTF_8));
        }
    }

    @Override
    public void close() {
        for (ResolvedMcpHeader header : headers) {
            Arrays.fill(header.value(), (byte) 0);
        }
    }

    static final class ResolvedMcpHeader {

        private final String name;
        private final byte[] value;

        ResolvedMcpHeader(String name, byte[] value) {
            this.name = name;
            this.value = Arrays.copyOf(value, value.length);
        }

        String name() {
            return name;
        }

        byte[] value() {
            return value;
        }
    }
}
