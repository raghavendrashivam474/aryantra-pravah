package com.aryntra.pravah.transport.tcp;

import com.aryntra.pravah.core.PravahException;
import com.aryntra.pravah.transport.Transport;
import com.aryntra.pravah.transport.TransportListener;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.util.Arrays;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * TCP-based transport implementation of {@link Transport}.
 * Uses standard Java networking (ServerSocket and Socket) to provide
 * connection lifecycle and raw byte transmission.
 * Supports multiple concurrent peer connections and failure-safe lifecycle.
 */
public class TcpTransport implements Transport {

    private static final Logger LOGGER = Logger.getLogger(TcpTransport.class.getName());
    private static final String TRANSPORT_NAME = "tcp";
    private static final int BUFFER_SIZE = 4096;

    private final String host;
    private final int configuredPort;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final ConcurrentHashMap<String, TcpConnection> connections = new ConcurrentHashMap<>();

    private ServerSocket serverSocket;
    private Thread acceptThread;

    private volatile TransportListener listener;

    /**
     * Internal container representing an active TCP connection.
     */
    private static class TcpConnection {
        final String id;
        final Socket socket;
        final OutputStream outputStream;
        final Thread readerThread;

        TcpConnection(String id, Socket socket, OutputStream outputStream, Thread readerThread) {
            this.id = id;
            this.socket = socket;
            this.outputStream = outputStream;
            this.readerThread = readerThread;
        }
    }

    public TcpTransport(int port) {
        this("127.0.0.1", port);
    }

    public TcpTransport(String host, int port) {
        if (port < 0 || port > 65535) {
            throw new IllegalArgumentException("Port must be between 0 and 65535: " + port);
        }
        this.host = Objects.requireNonNull(host, "host must not be null");
        this.configuredPort = port;
    }

    @Override
    public String getName() {
        return TRANSPORT_NAME;
    }

    @Override
    public synchronized void start() {
        if (running.get()) {
            return;
        }

        try {
            serverSocket = new ServerSocket();
            serverSocket.setReuseAddress(true);
            serverSocket.bind(new InetSocketAddress(host, configuredPort));
            running.set(true);

            acceptThread = new Thread(this::acceptLoop, "TcpTransport-Accept-" + getBoundPort());
            acceptThread.setDaemon(true);
            acceptThread.start();

            LOGGER.info(() -> "TcpTransport started on " + host + ":" + getBoundPort());
        } catch (IOException e) {
            running.set(false);
            closeQuietly(serverSocket);
            serverSocket = null;
            throw new PravahException("Failed to start TcpTransport on " + host + ":" + configuredPort, e);
        }
    }

    @Override
    public synchronized void stop() {
        if (!running.getAndSet(false)) {
            return;
        }

        LOGGER.info(() -> "Stopping TcpTransport on port " + getBoundPort());

        // Close server socket to unblock accept()
        closeQuietly(serverSocket);
        serverSocket = null;

        // Close and clean up all active connections
        for (String id : java.util.Collections.list(connections.keys())) {
            closeConnection(id);
        }
        connections.clear();

        if (acceptThread != null) {
            acceptThread.interrupt();
            acceptThread = null;
        }

        LOGGER.info("TcpTransport stopped");
    }

    @Override
    public boolean isRunning() {
        return running.get();
    }

    @Override
    public void send(String destinationId, byte[] payload) {
        if (!running.get()) {
            throw new PravahException("Cannot send payload: TcpTransport is not running");
        }
        Objects.requireNonNull(payload, "payload must not be null");

        TcpConnection conn = lookupConnection(destinationId);
        if (conn == null || conn.socket.isClosed()) {
            throw new PravahException("Cannot send payload: No active TCP connection to " + destinationId);
        }

        try {
            synchronized (conn) {
                conn.outputStream.write(payload);
                conn.outputStream.flush();
            }
        } catch (IOException e) {
            closeConnection(conn.id);
            throw new PravahException("Failed to send payload over TCP to " + destinationId, e);
        }
    }

    @Override
    public void setListener(TransportListener listener) {
        this.listener = listener;
    }

    /**
     * Connects to a remote TCP endpoint.
     */
    public synchronized void connect(String remoteHost, int remotePort) {
        if (!running.get()) {
            throw new PravahException("Cannot connect: TcpTransport is not running");
        }

        try {
            Socket socket = new Socket();
            socket.connect(new InetSocketAddress(remoteHost, remotePort), 5000);
            attachActiveSocket(socket);
        } catch (IOException e) {
            throw new PravahException("Failed to connect to " + remoteHost + ":" + remotePort, e);
        }
    }

    public int getBoundPort() {
        if (serverSocket != null && serverSocket.isBound()) {
            return serverSocket.getLocalPort();
        }
        return configuredPort;
    }

    public boolean isConnected() {
        for (TcpConnection conn : connections.values()) {
            if (conn.socket != null && !conn.socket.isClosed()) {
                return true;
            }
        }
        return false;
    }

    public int getConnectionCount() {
        return connections.size();
    }

    private void acceptLoop() {
        while (running.get()) {
            try {
                Socket socket = serverSocket.accept();
                synchronized (this) {
                    if (!running.get()) {
                        closeQuietly(socket);
                        break;
                    }
                    attachActiveSocket(socket);
                }
            } catch (SocketException e) {
                // Expected when ServerSocket is closed during stop()
                break;
            } catch (IOException e) {
                if (running.get()) {
                    LOGGER.log(Level.WARNING, "Error accepting TCP connection", e);
                }
                break;
            }
        }
    }

    private synchronized void attachActiveSocket(Socket socket) throws IOException {
        String id = socket.getRemoteSocketAddress() != null
                ? socket.getRemoteSocketAddress().toString()
                : "unknown-" + System.nanoTime();

        // Ensure any existing connection with the identical ID is cleaned up first
        closeConnection(id);

        OutputStream os = socket.getOutputStream();
        Thread rThread = new Thread(() -> readLoop(socket, id), "TcpTransport-Reader-" + socket.getPort());
        rThread.setDaemon(true);

        TcpConnection conn = new TcpConnection(id, socket, os, rThread);
        connections.put(id, conn);
        rThread.start();

        // Notify listener of new connection lifecycle event
        TransportListener currentListener = this.listener;
        if (currentListener != null) {
            try {
                currentListener.onConnectionOpened(id);
            } catch (Exception ex) {
                LOGGER.log(Level.WARNING, "Error in TransportListener.onConnectionOpened callback", ex);
            }
        }
    }

    private void readLoop(Socket socket, String id) {
        byte[] buffer = new byte[BUFFER_SIZE];

        try (InputStream inputStream = socket.getInputStream()) {
            while (running.get() && !socket.isClosed()) {
                int bytesRead = inputStream.read(buffer);
                if (bytesRead == -1) {
                    // End of stream - remote closed connection
                    break;
                }
                if (bytesRead > 0) {
                    byte[] receivedData = Arrays.copyOf(buffer, bytesRead);
                    TransportListener currentListener = this.listener;
                    if (currentListener != null) {
                        try {
                            currentListener.onDataReceived(id, receivedData);
                        } catch (Exception ex) {
                            LOGGER.log(Level.WARNING, "Error in TransportListener callback", ex);
                        }
                    }
                }
            }
        } catch (SocketException e) {
            // Socket closed during stop or disconnect
        } catch (IOException e) {
            if (running.get()) {
                LOGGER.log(Level.FINE, "TCP read error on connection " + id + ": " + e.getMessage());
            }
        } finally {
            closeConnection(id);
        }
    }

    private void closeConnection(String id) {
        if (id == null) return;
        TcpConnection conn = connections.remove(id);
        if (conn != null) {
            closeQuietly(conn.outputStream);
            closeQuietly(conn.socket);
            if (conn.readerThread != null && Thread.currentThread() != conn.readerThread) {
                conn.readerThread.interrupt();
            }

            // Notify listener of connection close lifecycle event
            TransportListener currentListener = this.listener;
            if (currentListener != null) {
                try {
                    currentListener.onConnectionClosed(id);
                } catch (Exception ex) {
                    LOGGER.log(Level.WARNING, "Error in TransportListener.onConnectionClosed callback", ex);
                }
            }
        }
    }

    private TcpConnection lookupConnection(String destinationId) {
        if (destinationId == null) {
            return null;
        }

        // 1. Direct exact match
        TcpConnection conn = connections.get(destinationId);
        if (conn != null) {
            return conn;
        }

        // 2. Normalization match (handle leading slashes or formatting differences)
        String targetNormalized = destinationId.replace("/", "");
        for (String key : connections.keySet()) {
            if (key.replace("/", "").equals(targetNormalized)) {
                return connections.get(key);
            }
        }

        // 3. Fallback: if there is exactly 1 connection in the registry, route to it
        if (connections.size() == 1) {
            return connections.values().iterator().next();
        }

        return null;
    }

    private static void closeQuietly(AutoCloseable closeable) {
        if (closeable != null) {
            try {
                closeable.close();
            } catch (Exception ignored) {
            }
        }
    }
}