package com.aryntra.pravah.transport.bluetooth;

import com.aryntra.pravah.core.PravahException;
import com.aryntra.pravah.transport.Transport;
import com.aryntra.pravah.transport.TransportCapabilities;
import com.aryntra.pravah.transport.TransportListener;

import java.io.IOException;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Robust Bluetooth RFCOMM transport implementation.
 * Uses a thread-safe Virtual Link Broker to simulate RFCOMM physical radio channels
 * on the JVM, allowing 100% isolated unit and integration testing without Android hardware.
 *
 * <p>Identifies connections via the namespace "bt:{MAC}" and maps endpoints using
 * "bluetooth://{MAC}:{channel}" format.</p>
 */
public class BluetoothRfcommTransport implements Transport {
    private static final Logger LOGGER = Logger.getLogger(BluetoothRfcommTransport.class.getName());
    private static final String TRANSPORT_NAME = "bluetooth";

    // Thread-safe registry simulating local RFCOMM physical airspace
    private static final ConcurrentHashMap<String, BluetoothRfcommTransport> AIRSPACE = new ConcurrentHashMap<>();

    private final String localMacAddress;
    private final int localChannel;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final ConcurrentHashMap<String, VirtualBluetoothConnection> connections = new ConcurrentHashMap<>();
    private final TransportCapabilities capabilities;
    
    private ExecutorService ioExecutor;
    private volatile TransportListener listener;

    public BluetoothRfcommTransport(String localMacAddress, int localChannel) {
        this.localMacAddress = validateMacAddress(localMacAddress);
        if (localChannel < 1 || localChannel > 30) {
            throw new IllegalArgumentException("RFCOMM channel must be in range 1-30, got: " + localChannel);
        }
        this.localChannel = localChannel;
        this.capabilities = TransportCapabilities.builder()
                .reliable(true)
                .connectionOriented(true)
                .unicast(true)
                .supportsMultiplexing(false)
                .build();
    }

    @Override
    public String getName() {
        return TRANSPORT_NAME;
    }

    @Override
    public TransportCapabilities getCapabilities() {
        return capabilities;
    }

    public String getLocalMacAddress() {
        return localMacAddress;
    }

    public int getLocalChannel() {
        return localChannel;
    }

    @Override
    public synchronized void start() {
        if (running.compareAndSet(false, true)) {
            ioExecutor = Executors.newCachedThreadPool(r -> {
                Thread t = new Thread(r, "Pravah-Bluetooth-IO-" + localMacAddress);
                t.setDaemon(true);
                return t;
            });
            AIRSPACE.put(localMacAddress, this);
            LOGGER.log(Level.INFO, "Bluetooth RFCOMM Transport started on {0} (Channel {1})", 
                    new Object[]{localMacAddress, localChannel});
        }
    }

    @Override
    public synchronized void stop() {
        if (running.compareAndSet(true, false)) {
            AIRSPACE.remove(localMacAddress);
            for (String connId : connections.keySet()) {
                disconnect(connId);
            }
            if (ioExecutor != null) {
                ioExecutor.shutdownNow();
            }
            LOGGER.log(Level.INFO, "Bluetooth RFCOMM Transport stopped on {0}", localMacAddress);
        }
    }

    @Override
    public boolean isRunning() {
        return running.get();
    }

    @Override
    public void send(String destinationId, byte[] payload) {
        Objects.requireNonNull(destinationId, "destinationId must not be null");
        Objects.requireNonNull(payload, "payload must not be null");

        if (!running.get()) {
            throw new IllegalStateException("Transport is not running");
        }

        VirtualBluetoothConnection connection = connections.get(destinationId);
        if (connection == null) {
            throw new PravahException("No active Bluetooth connection to: " + destinationId);
        }

        try {
            connection.write(payload);
        } catch (IOException e) {
            LOGGER.log(Level.WARNING, "Failed to send payload over Bluetooth to: " + destinationId, e);
            disconnect(destinationId);
            throw new PravahException("Write failed on Bluetooth link", e);
        }
    }

    @Override
    public void setListener(TransportListener listener) {
        this.listener = listener;
    }

    /**
     * Initiates an outbound RFCOMM connection to a remote Bluetooth device.
     */
    public synchronized void connect(String remoteMacAddress) {
        Objects.requireNonNull(remoteMacAddress, "remoteMacAddress must not be null");
        String cleanRemoteMac = validateMacAddress(remoteMacAddress);

        if (!running.get()) {
            throw new IllegalStateException("Local transport is not running");
        }

        String connId = "bt:" + cleanRemoteMac;
        if (connections.containsKey(connId)) {
            return; // Already connected
        }

        BluetoothRfcommTransport remoteTransport = AIRSPACE.get(cleanRemoteMac);
        if (remoteTransport == null || !remoteTransport.isRunning()) {
            throw new PravahException("Bluetooth target device " + cleanRemoteMac + " is unreachable");
        }

        try {
            // Establish bidirectional streaming pipes
            PipedOutputStream localOut = new PipedOutputStream();
            PipedInputStream localIn = new PipedInputStream();
            PipedOutputStream remoteOut = new PipedOutputStream();
            PipedInputStream remoteIn = new PipedInputStream();

            // Interlock pipes
            localIn.connect(remoteOut);
            remoteIn.connect(localOut);

            // Create connection descriptors
            VirtualBluetoothConnection localConn = new VirtualBluetoothConnection(connId, localIn, localOut);
            VirtualBluetoothConnection remoteConn = new VirtualBluetoothConnection("bt:" + this.localMacAddress, remoteIn, remoteOut);

            // Register connection descriptors
            this.connections.put(connId, localConn);
            remoteTransport.connections.put("bt:" + this.localMacAddress, remoteConn);

            // Spawn non-blocking background loop threads for data reading
            ioExecutor.submit(() -> readLoop(localConn));
            remoteTransport.ioExecutor.submit(() -> remoteTransport.readLoop(remoteConn));

            // Fire lifecycle notifications asynchronously to avoid lock contention
            notifyConnectionOpened(connId);
            remoteTransport.notifyConnectionOpened("bt:" + this.localMacAddress);

        } catch (IOException e) {
            throw new PravahException("Failed to establish Bluetooth RFCOMM streaming link", e);
        }
    }

    /**
     * Terminate an active Bluetooth connection.
     */
    public synchronized void disconnect(String connectionId) {
        VirtualBluetoothConnection conn = connections.remove(connectionId);
        if (conn != null) {
            conn.close();
            notifyConnectionClosed(connectionId);

            // Cleanly teardown remote peer connection as well
            if (connectionId.startsWith("bt:")) {
                String remoteMac = connectionId.substring(3);
                BluetoothRfcommTransport remoteTransport = AIRSPACE.get(remoteMac);
                if (remoteTransport != null) {
                    remoteTransport.teardownRemoteLink("bt:" + this.localMacAddress);
                }
            }
        }
    }

    public int getConnectionCount() {
        return connections.size();
    }

    private synchronized void teardownRemoteLink(String connectionId) {
        VirtualBluetoothConnection conn = connections.remove(connectionId);
        if (conn != null) {
            conn.close();
            notifyConnectionClosed(connectionId);
        }
    }

    private void readLoop(VirtualBluetoothConnection conn) {
        byte[] buffer = new byte[4096];
        try {
            while (running.get() && !conn.isClosed()) {
                int bytesRead = conn.read(buffer);
                if (bytesRead == -1) {
                    break; // Stream EOF
                }
                if (bytesRead > 0) {
                    byte[] data = new byte[bytesRead];
                    System.arraycopy(buffer, 0, data, 0, bytesRead);
                    notifyDataReceived(conn.id(), data);
                }
            }
        } catch (IOException e) {
            LOGGER.log(Level.FINE, "Bluetooth link read error or stream closed: {0}", conn.id());
        } finally {
            disconnect(conn.id());
        }
    }

    private void notifyConnectionOpened(String connectionId) {
        TransportListener l = this.listener;
        if (l != null) {
            l.onConnectionOpened(connectionId);
        }
    }

    private void notifyConnectionClosed(String connectionId) {
        TransportListener l = this.listener;
        if (l != null) {
            l.onConnectionClosed(connectionId);
        }
    }

    private void notifyDataReceived(String senderId, byte[] payload) {
        TransportListener l = this.listener;
        if (l != null) {
            l.onDataReceived(senderId, payload);
        }
    }

    private String validateMacAddress(String mac) {
        Objects.requireNonNull(mac, "MAC address cannot be null");
        String upper = mac.trim().toUpperCase();
        if (!upper.matches("^([0-9A-F]{2}[:-]){5}([0-9A-F]{2})$")) {
            throw new IllegalArgumentException("Invalid Bluetooth MAC Address format: " + mac);
        }
        return upper;
    }

    /**
     * Inner helper class representing an active virtual stream link.
     */
    private static class VirtualBluetoothConnection {
        private final String id;
        private final PipedInputStream input;
        private final PipedOutputStream output;
        private final AtomicBoolean closed = new AtomicBoolean(false);

        public VirtualBluetoothConnection(String id, PipedInputStream input, PipedOutputStream output) {
            this.id = id;
            this.input = input;
            this.output = output;
        }

        public String id() {
            return id;
        }

        public int read(byte[] b) throws IOException {
            return input.read(b);
        }

        public synchronized void write(byte[] b) throws IOException {
            if (closed.get()) {
                throw new IOException("Stream connection is closed");
            }
            output.write(b);
            output.flush();
        }

        public boolean isClosed() {
            return closed.get();
        }

        public synchronized void close() {
            if (closed.compareAndSet(false, true)) {
                try { input.close(); } catch (IOException ignored) {}
                try { output.close(); } catch (IOException ignored) {}
            }
        }
    }
}