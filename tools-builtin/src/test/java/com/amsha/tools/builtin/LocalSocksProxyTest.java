package com.amsha.tools.builtin;

import static org.junit.jupiter.api.Assertions.*;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;

import org.junit.jupiter.api.Test;

class LocalSocksProxyTest {

    @Test
    void testProxyStartupPortAndClose() throws Exception {
        try (LocalSocksProxy proxy = new LocalSocksProxy()) {
            assertTrue(proxy.getPort() > 0);
        }
    }

    @Test
    void testSsrfBlockForLocalhostTarget() throws Exception {
        try (LocalSocksProxy proxy = new LocalSocksProxy()) {
            try (Socket socket = new Socket("127.0.0.1", proxy.getPort());
                    OutputStream out = socket.getOutputStream();
                    InputStream in = socket.getInputStream()) {

                // Send SOCKS5 handshake (version 5, 1 method: 0 = NO AUTH)
                out.write(new byte[] {5, 1, 0});
                out.flush();

                int version = in.read();
                int selectedMethod = in.read();
                assertEquals(5, version);
                assertEquals(0, selectedMethod);

                // Send CONNECT request for domain "127.0.0.1" (atyp 3, len 9, "127.0.0.1", port 80)
                byte[] domainBytes = "127.0.0.1".getBytes();
                out.write(new byte[] {5, 1, 0, 3, (byte) domainBytes.length});
                out.write(domainBytes);
                out.write(new byte[] {0, 80});
                out.flush();

                byte[] reply = in.readNBytes(10);
                assertEquals(10, reply.length);
                assertEquals(5, reply[0]);
                // Reply status 2 = connection not allowed by ruleset (SSRF block for loopback/private)
                assertEquals(2, reply[1]);
            }
        }
    }
}
