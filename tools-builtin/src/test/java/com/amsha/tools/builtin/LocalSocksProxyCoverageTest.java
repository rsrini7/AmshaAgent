package com.amsha.tools.builtin;

import static org.junit.jupiter.api.Assertions.*;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

class LocalSocksProxyCoverageTest {

    /**
     * Performs the no-auth handshake, sends {@code request} as the CONNECT frame, and returns
     * the 10-byte reply. Every target used here is deliberately rejected before any outbound
     * connect, so these stay hermetic and fast.
     */
    private static byte[] connectAndReadReply(int port, byte[] request) throws Exception {
        try (Socket socket = new Socket(InetAddress.getLoopbackAddress(), port)) {
            socket.setSoTimeout(10_000);
            OutputStream out = socket.getOutputStream();
            InputStream in = socket.getInputStream();

            out.write(new byte[] {5, 1, 0});
            out.flush();
            assertEquals(5, in.read());
            assertEquals(0, in.read());

            out.write(request);
            out.flush();

            return in.readNBytes(10);
        }
    }

    @Test
    void testLifecycle() throws Exception {
        LocalSocksProxy proxy = new LocalSocksProxy();
        int port = proxy.getPort();
        assertTrue(port > 0);

        // Closing should succeed without throwing
        assertDoesNotThrow(proxy::close);
    }

    @Test
    void testHandshakeRejectionUnsupportedMethod() throws Exception {
        try (LocalSocksProxy proxy = new LocalSocksProxy()) {
            try (Socket socket = new Socket(InetAddress.getLoopbackAddress(), proxy.getPort());
                    OutputStream out = socket.getOutputStream();
                    InputStream in = socket.getInputStream()) {

                // Send SOCKS5 handshake without method 0 (e.g. method 2 = GSSAPI only)
                out.write(new byte[] {5, 1, 2});
                out.flush();

                byte[] reply = in.readNBytes(2);
                assertEquals(2, reply.length);
                assertEquals(5, reply[0]);
                assertEquals((byte) 0xFF, reply[1]); // NO ACCEPTABLE METHODS
            }
        }
    }

    @Test
    void testHandshakeRejectionInvalidVersion() throws Exception {
        try (LocalSocksProxy proxy = new LocalSocksProxy()) {
            try (Socket socket = new Socket(InetAddress.getLoopbackAddress(), proxy.getPort());
                    OutputStream out = socket.getOutputStream();
                    InputStream in = socket.getInputStream()) {

                // Send non-SOCKS5 version
                out.write(new byte[] {4, 1, 0});
                out.flush();

                int read = in.read();
                assertEquals(-1, read); // EOF because connection is closed
            }
        }
    }

    @Test
    void testNonConnectCommandRejection() throws Exception {
        try (LocalSocksProxy proxy = new LocalSocksProxy()) {
            try (Socket socket = new Socket(InetAddress.getLoopbackAddress(), proxy.getPort());
                    OutputStream out = socket.getOutputStream();
                    InputStream in = socket.getInputStream()) {

                // Handshake
                out.write(new byte[] {5, 1, 0});
                out.flush();
                assertEquals(5, in.read());
                assertEquals(0, in.read());

                // Send BIND command (cmd=2) instead of CONNECT (cmd=1)
                out.write(new byte[] {5, 2, 0, 1, 127, 0, 0, 1, 0, 80});
                out.flush();

                byte[] reply = in.readNBytes(10);
                assertEquals(10, reply.length);
                assertEquals(5, reply[0]);
                assertEquals(7, reply[1]); // COMMAND NOT SUPPORTED
            }
        }
    }

    @Test
    void testIpv4SsrfBlock() throws Exception {
        try (LocalSocksProxy proxy = new LocalSocksProxy()) {
            try (Socket socket = new Socket(InetAddress.getLoopbackAddress(), proxy.getPort());
                    OutputStream out = socket.getOutputStream();
                    InputStream in = socket.getInputStream()) {

                // Handshake
                out.write(new byte[] {5, 1, 0});
                out.flush();
                assertEquals(5, in.read());
                assertEquals(0, in.read());

                // Send CONNECT for IPv4 127.0.0.1 (atyp=1)
                out.write(new byte[] {5, 1, 0, 1, 127, 0, 0, 1, 0, 80});
                out.flush();

                byte[] reply = in.readNBytes(10);
                assertEquals(10, reply.length);
                assertEquals(5, reply[0]);
                assertEquals(2, reply[1]); // CONNECTION NOT ALLOWED BY RULESET
            }
        }
    }

    @Test
    void testInvalidAtypRejection() throws Exception {
        try (LocalSocksProxy proxy = new LocalSocksProxy()) {
            try (Socket socket = new Socket(InetAddress.getLoopbackAddress(), proxy.getPort());
                    OutputStream out = socket.getOutputStream();
                    InputStream in = socket.getInputStream()) {

                // Handshake
                out.write(new byte[] {5, 1, 0});
                out.flush();
                assertEquals(5, in.read());
                assertEquals(0, in.read());

                // Send CONNECT with invalid atyp=9
                out.write(new byte[] {5, 1, 0, 9, 127, 0, 0, 1, 0, 80});
                out.flush();

                byte[] reply = in.readNBytes(10);
                assertEquals(10, reply.length);
                assertEquals(5, reply[0]);
                assertEquals(8, reply[1]); // ADDRESS TYPE NOT SUPPORTED
            }
        }
    }

    // ── SSRF guard: every private/special address class must be refused ───────────────────

    @Test
    void ssrfBlock_linkLocalAddress() throws Exception {
        try (LocalSocksProxy proxy = new LocalSocksProxy()) {
            byte[] reply =
                    connectAndReadReply(proxy.getPort(), new byte[] {5, 1, 0, 1, (byte) 169, (byte) 254, 1, 1, 0, 80});

            assertEquals(2, reply[1], "169.254.0.0/16 is link-local and must be blocked");
        }
    }

    @Test
    void ssrfBlock_siteLocalAddress() throws Exception {
        try (LocalSocksProxy proxy = new LocalSocksProxy()) {
            byte[] reply = connectAndReadReply(proxy.getPort(), new byte[] {5, 1, 0, 1, 10, 0, 0, 1, 0, 80});

            assertEquals(2, reply[1], "10.0.0.0/8 is site-local and must be blocked");
        }
    }

    @Test
    void ssrfBlock_multicastAddress() throws Exception {
        try (LocalSocksProxy proxy = new LocalSocksProxy()) {
            byte[] reply = connectAndReadReply(proxy.getPort(), new byte[] {5, 1, 0, 1, (byte) 224, 0, 0, 1, 0, 80});

            assertEquals(2, reply[1], "224.0.0.0/4 is multicast and must be blocked");
        }
    }

    @Test
    void ssrfBlock_anyLocalAddress() throws Exception {
        try (LocalSocksProxy proxy = new LocalSocksProxy()) {
            byte[] reply = connectAndReadReply(proxy.getPort(), new byte[] {5, 1, 0, 1, 0, 0, 0, 0, 0, 0});

            assertEquals(2, reply[1], "0.0.0.0 is an unspecified address and must be blocked");
        }
    }

    // ── IPv6 (ATYP=4) target parsing ──────────────────────────────────────────────────────

    @Test
    void ipv6TargetIsParsedAndSubjectToTheSameSsrfGuard() throws Exception {
        byte[] request = new byte[22];
        request[0] = 5;
        request[1] = 1; // CONNECT
        request[2] = 0; // RSV
        request[3] = 4; // ATYP = IPv6
        request[4] = (byte) 0xfe;
        request[5] = (byte) 0xc0; // fec0::/10 - IPv6 site-local
        request[21] = 80; // port 80

        try (LocalSocksProxy proxy = new LocalSocksProxy()) {
            byte[] reply = connectAndReadReply(proxy.getPort(), request);

            assertEquals(2, reply[1], "An IPv6 site-local target must be blocked");
        }
    }

    // ── DNS failure handling ──────────────────────────────────────────────────────────────

    @Test
    void unresolvableDomainIsReportedAsHostUnreachable() throws Exception {
        byte[] host = "nonexistent-host.invalid".getBytes(StandardCharsets.UTF_8);
        byte[] request = new byte[4 + 1 + host.length + 2];
        request[0] = 5;
        request[1] = 1; // CONNECT
        request[2] = 0; // RSV
        request[3] = 3; // ATYP = domain name
        request[4] = (byte) host.length;
        System.arraycopy(host, 0, request, 5, host.length);
        request[request.length - 1] = 80;

        try (LocalSocksProxy proxy = new LocalSocksProxy()) {
            byte[] reply = connectAndReadReply(proxy.getPort(), request);

            assertEquals(4, reply[1], "A DNS failure must map to HOST UNREACHABLE");
        }
    }

    // ── CONNECT tunnel ────────────────────────────────────────────────────────────────────

    /**
     * Exercises the full CONNECT tunnel, including the bidirectional pipe threads.
     *
     * <p>The request uses a documentation-range IPv4 address. The injected connector maps it to
     * a local echo socket only after the production SSRF checks accept the target, so the test
     * remains deterministic without external network access or a wildcard-bound listener.
     */
    @Test
    void connectTunnelsBidirectionally() throws Exception {
        byte[] payload = "hello-socks".getBytes(StandardCharsets.UTF_8);
        InetAddress expectedTarget = InetAddress.getByAddress(new byte[] {(byte) 203, 0, 113, 1});
        AtomicReference<byte[]> receivedByDestination = new AtomicReference<>(new byte[0]);

        try (ServerSocket destination = new ServerSocket(0, 5, InetAddress.getLoopbackAddress())) {
            destination.setSoTimeout(15_000);
            Thread echoServer = Thread.ofVirtual().start(() -> {
                try (Socket s = destination.accept();
                        InputStream din = s.getInputStream();
                        OutputStream dout = s.getOutputStream()) {
                    byte[] got = din.readNBytes(payload.length);
                    receivedByDestination.set(got);
                    dout.write(got);
                    dout.flush();
                } catch (Exception _) {
                    // Teardown races are not interesting here.
                }
            });

            byte[] echoed = new byte[0];
            try (LocalSocksProxy proxy = new LocalSocksProxy((targetIp, targetPort) -> {
                        assertEquals(expectedTarget, targetIp);
                        assertEquals(destination.getLocalPort(), targetPort);
                        return new Socket(InetAddress.getLoopbackAddress(), targetPort);
                    });
                    Socket client = new Socket(InetAddress.getLoopbackAddress(), proxy.getPort())) {
                client.setSoTimeout(15_000);
                OutputStream out = client.getOutputStream();
                InputStream in = client.getInputStream();

                out.write(new byte[] {5, 1, 0});
                out.flush();
                assertEquals(5, in.read());
                assertEquals(0, in.read());

                int port = destination.getLocalPort();
                out.write(new byte[] {5, 1, 0, 1, (byte) 203, 0, 113, 1, (byte) (port >> 8), (byte) port});
                out.flush();

                byte[] reply = in.readNBytes(10);
                assertEquals(10, reply.length);
                assertEquals(5, reply[0]);
                assertEquals(0, reply[1], "CONNECT must be granted for a permitted target");

                out.write(payload);
                out.flush();

                echoed = in.readNBytes(payload.length);
            }

            echoServer.join(5_000);

            assertArrayEquals(payload, receivedByDestination.get(), "the destination must receive the client payload");
            assertArrayEquals(payload, echoed, "the echoed payload must come back through the tunnel");
        }
    }

    // ── close() interrupt handling ────────────────────────────────────────────────────────

    @Test
    void closePreservesInterruptStatus() throws Exception {
        LocalSocksProxy proxy = new LocalSocksProxy();
        try {
            Thread.currentThread().interrupt();
            proxy.close();
        } finally {
            assertTrue(Thread.interrupted(), "close() must not swallow the calling thread's interrupt");
        }
    }
}
