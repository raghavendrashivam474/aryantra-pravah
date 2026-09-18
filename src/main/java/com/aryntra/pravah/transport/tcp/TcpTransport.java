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
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * TCP-based transport implementation of {@link Transport}.
 * Uses standard Java networking (ServerSocket and Socket) to provide
 * connection lifecycle and raw byte transmission.
 */
public class TcpTransport implements Transport {

    private static final Logger LOGGER = Logger.getLogger(TcpTransport.class.getName());
    private static final String TRANSPORT_NAME = "tcp";
    private static final int BUFFER_SIZE = 4096;

    private final String host;
    private final int configuredPort;
    private final AtomicBoolean running = new AtomicBoolean(false);

    private ServerSocket serverSocket;
    private Thread acceptThread;
    private Thread readerThread;

    private volatile TransportListener listener;
    private volatile Socket activeSocket;
    private volatile OutputStream activeOutputStream;

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

        // Close active connection socket and streams
        closeActiveConnection();

        if (acceptThread != null) {
            acceptThread.interrupt();
            acceptThread = null;
        }

        if (readerThread != null) {
            readerThread.interrupt();
            readerThread = null;
        }

        LOGGER.info("TcpTransport stopped");
    }

    @Override
    public boolean isRunning() {
        return running.get();
    }

    @Override
    public synchronized void send(String destinationId, byte[] payload) {
        if (!running.get()) {
            throw new PravahException("Cannot send payload: TcpTransport is not running");
        }
        Objects.requireNonNull(payload, "payload must not be null");

        if (activeSocket == null || activeSocket.isClosed() || activeOutputStream == null) {
            throw new PravahException("Cannot send payload: No active TCP connection to " + destinationId);
        }

        try {
            activeOutputStream.write(payload);
            activeOutputStream.flush();
        } catch (IOException e) {
            throw new PravahException("Failed to send payload over TCP", e);
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

    public synchronized boolean isConnected() {
        return activeSocket != null && activeSocket.isConnected() && !activeSocket.isClosed();
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
        closeActiveConnection();
        this.activeSocket = socket;
        this.activeOutputStream = socket.getOutputStream();

        readerThread = new Thread(this::readLoop, "TcpTransport-Reader-" + socket.getPort());
        readerThread.setDaemon(true);
        readerThread.start();
    }

    private void readLoop() {
        Socket socket = this.activeSocket;
        if (socket == null) {
            return;
        }

        byte[] buffer = new byte[BUFFER_SIZE];
        String senderId = socket.getRemoteSocketAddress() != null 
                ? socket.getRemoteSocketAddress().toString() 
                : "unknown";

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
                            currentListener.onDataReceived(senderId, receivedData);
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
                LOGGER.log(Level.FINE, "TCP read error: " + e.getMessage());
            }
        } finally {
            synchronized (this) {
                if (this.activeSocket == socket) {
                    closeActiveConnection();
                }
            }
        }
    }

    private synchronized void closeActiveConnection() {
        if (activeOutputStream != null) {
            try {
                activeOutputStream.close();
            } catch (IOException ignored) {
            }
            activeOutputStream = null;
        }
        if (activeSocket != null) {
            closeQuietly(activeSocket);
            activeSocket = null;
        }
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