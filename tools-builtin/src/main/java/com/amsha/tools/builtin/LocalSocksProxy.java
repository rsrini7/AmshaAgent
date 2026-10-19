package com.amsha.tools.builtin;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

import org.jboss.logging.Logger;

/**
 * Local SOCKS5 proxy that tunnels outbound HTTP traffic through the
 * Tor network. Runs on loopback with a random ephemeral port.
 * <p>
 * SSRF protection: DNS resolution is performed locally before
 * connecting to the destination, and loopback, unspecified, private,
 * link-local, and multicast addresses are rejected.
 */
public class LocalSocksProxy implements AutoCloseable {
    @FunctionalInterface
    interface DestinationConnector {
        Socket connect(InetAddress address, int port) throws IOException;
    }

    private final DestinationConnector destinationConnector;

    private static final Logger LOG = Logger.getLogger(LocalSocksProxy.class);

    private final ServerSocket serverSocket;
    private final Thread serverThread;
    private volatile boolean closed = false;

    LocalSocksProxy() throws IOException {
        this(LocalSocksProxy::connectDestination);
    }

    LocalSocksProxy(DestinationConnector destinationConnector) throws IOException {
        this.destinationConnector = Objects.requireNonNull(destinationConnector, "destinationConnector");
        this.serverSocket = new ServerSocket();
        this.serverSocket.setReuseAddress(true);
        this.serverSocket.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 50);
        this.serverThread = Thread.ofVirtual().start(this::run);
    }

    private static Socket connectDestination(InetAddress address, int port) throws IOException {
        Socket socket = new Socket();
        try {
            socket.connect(new InetSocketAddress(address, port), 10_000);
            return socket;
        } catch (IOException e) {
            closeQuietly(socket);
            throw e;
        }
    }

    int getPort() {
        return serverSocket.getLocalPort();
    }

    private void run() {
        while (!closed) {
            try {
                Socket clientSocket = serverSocket.accept();
                Thread.ofVirtual().start(() -> handleClient(clientSocket));
            } catch (IOException e) {
                if (closed) break;
            }
        }
    }

    private record SocksTarget(String host, int port) {}

    private boolean performHandshake(InputStream in, OutputStream out) throws IOException {
        int version = in.read();
        if (version != 5) return false;
        int numMethods = in.read();
        if (numMethods <= 0) return false;
        byte[] methods = in.readNBytes(numMethods);
        if (methods.length < numMethods) return false;

        boolean supportNoAuth = false;
        for (byte m : methods) {
            if (m == 0) {
                supportNoAuth = true;
                break;
            }
        }
        if (!supportNoAuth) {
            out.write(new byte[] {5, (byte) 0xFF});
            out.flush();
            return false;
        }
        out.write(new byte[] {5, 0});
        out.flush();
        return true;
    }

    private SocksTarget readConnectTarget(InputStream in, OutputStream out) throws IOException {
        int reqVer = in.read();
        if (reqVer != 5) return null;
        int cmd = in.read();
        if (cmd != 1) { // CONNECT only
            out.write(new byte[] {5, 7, 0, 1, 0, 0, 0, 0, 0, 0});
            out.flush();
            return null;
        }
        in.read(); // RSV

        int atyp = in.read();
        String host;
        if (atyp == 1) { // IPv4
            byte[] ipBytes = in.readNBytes(4);
            if (ipBytes.length < 4) return null;
            host = InetAddress.getByAddress(ipBytes).getHostAddress();
        } else if (atyp == 3) { // Domain name
            int len = in.read();
            if (len < 0) return null;
            byte[] hostBytes = in.readNBytes(len);
            if (hostBytes.length < len) return null;
            host = new String(hostBytes, StandardCharsets.UTF_8);
        } else if (atyp == 4) { // IPv6
            byte[] ipBytes = in.readNBytes(16);
            if (ipBytes.length < 16) return null;
            host = InetAddress.getByAddress(ipBytes).getHostAddress();
        } else {
            out.write(new byte[] {5, 8, 0, 1, 0, 0, 0, 0, 0, 0});
            out.flush();
            return null;
        }

        int p1 = in.read();
        int p2 = in.read();
        if (p1 < 0 || p2 < 0) return null;
        int port = ((p1 & 0xFF) << 8) | (p2 & 0xFF);

        return new SocksTarget(host, port);
    }

    private InetAddress resolveAndValidate(String host, OutputStream out) throws IOException {
        InetAddress[] addresses;
        try {
            addresses = InetAddress.getAllByName(host);
        } catch (Exception e) {
            out.write(new byte[] {5, 4, 0, 1, 0, 0, 0, 0, 0, 0});
            out.flush();
            return null;
        }

        for (InetAddress addr : addresses) {
            if (addr.isAnyLocalAddress()
                    || addr.isLoopbackAddress()
                    || addr.isLinkLocalAddress()
                    || addr.isSiteLocalAddress()
                    || addr.isMulticastAddress()) {
                LOG.warnf(
                        "SSRF block in SOCKS proxy: target host '%s' resolved to private IP '%s'",
                        host, addr.getHostAddress());
                out.write(new byte[] {5, 2, 0, 1, 0, 0, 0, 0, 0, 0});
                out.flush();
                return null;
            }
        }
        return addresses[0];
    }

    private void handleClient(Socket clientSocket) {
        try (clientSocket;
                InputStream in = clientSocket.getInputStream();
                OutputStream out = clientSocket.getOutputStream()) {

            clientSocket.setSoTimeout(25_000); // 25s read timeout for client-side

            if (!performHandshake(in, out)) return;

            SocksTarget target = readConnectTarget(in, out);
            if (target == null) return;

            InetAddress targetIp = resolveAndValidate(target.host(), out);
            if (targetIp == null) return;

            // 4. Connect to remote destination
            try (Socket destSocket = destinationConnector.connect(targetIp, target.port())) {
                destSocket.setSoTimeout(20_000); // 20s read timeout for destination
                try (InputStream destIn = destSocket.getInputStream();
                        OutputStream destOut = destSocket.getOutputStream()) {

                    // Send success reply
                    out.write(new byte[] {5, 0, 0, 1, 0, 0, 0, 0, 0, 0});
                    out.flush();

                    // Pipe streams bidirectionally
                    final Socket cSock = clientSocket;
                    final Socket dSock = destSocket;

                    Thread t1 = Thread.ofVirtual().start(() -> {
                        try {
                            destIn.transferTo(cSock.getOutputStream());
                        } catch (IOException e) {
                            if (!closed) LOG.debugf("SOCKS pipe IOException (dest→client): %s", e.getMessage());
                        } finally {
                            closeQuietly(dSock);
                            closeQuietly(cSock);
                        }
                    });

                    Thread t2 = Thread.ofVirtual().start(() -> {
                        try {
                            cSock.getInputStream().transferTo(destOut);
                        } catch (IOException e) {
                            if (!closed) LOG.debugf("SOCKS pipe IOException (client→dest): %s", e.getMessage());
                        } finally {
                            closeQuietly(dSock);
                            closeQuietly(cSock);
                        }
                    });

                    try {
                        t1.join();
                        t2.join();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
            } catch (Exception e) {
                out.write(new byte[] {5, 5, 0, 1, 0, 0, 0, 0, 0, 0});
                out.flush();
            }
        } catch (IOException e) {
            // Ignore socket errors
        }
    }

    private static void closeQuietly(AutoCloseable c) {
        if (c != null) {
            try {
                c.close();
            } catch (Exception e) {
                // ignore
            }
        }
    }

    @Override
    public void close() throws IOException {
        closed = true;
        serverSocket.close();
        try {
            serverThread.join(2000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
