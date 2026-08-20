package com.amsha.tools.builtin;

import static org.junit.jupiter.api.Assertions.*;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.Socket;

import org.junit.jupiter.api.Test;

class LocalSocksProxyCoverageTest {

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
}
