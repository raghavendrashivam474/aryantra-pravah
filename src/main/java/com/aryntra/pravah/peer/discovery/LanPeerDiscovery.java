package com.aryntra.pravah.peer.discovery;

import com.aryntra.pravah.peer.PeerId;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketException;
import java.util.List;
import java.util.concurrent.*;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Highly robust, standard-library UDP broadcast implementation of LAN Peer Discovery.
 *
 * <p>Spawns a receiver thread to listen for incoming packets, and a scheduler
 * to periodically announce this node's presence on the local network.</p>
 *
 * S3.4 - Phase 3: LAN Peer Discovery
 */
public final class LanPeerDiscovery {

    private static final Logger logger = Logger.getLogger(LanPeerDiscovery.class.getName());
    private static final int BUFFER_SIZE = 1024;

    private final PeerId localPeerId;
    private final int localTcpPort;
    private final int discoveryPort;
    private final long broadcastIntervalMs;

    private final List<PeerDiscoveryListener> listeners = new CopyOnWriteArrayList<>();
    private final Object stateLock = new Object();

    // Active lifecycle resources
    private DatagramSocket rxSocket;
    private DatagramSocket txSocket;
    private Thread receiverThread;
    private ScheduledExecutorService broadcasterExecutor;
    private boolean running = false;

    /**
     * @param localPeerId logical identity of this node
     * @param localTcpPort the TCP port this node is listening on
     * @param discoveryPort the shared UDP port used for LAN broadcasts
     * @param broadcastIntervalMs frequency of presence announcements
     */
    public LanPeerDiscovery(PeerId localPeerId, int localTcpPort, int discoveryPort, long broadcastIntervalMs) {
        if (localPeerId == null) {
            throw new IllegalArgumentException("localPeerId must not be null");
        }
        if (localTcpPort < 1 || localTcpPort > 65535) {
            throw new IllegalArgumentException("localTcpPort must be in range 1-65535");
        }
        if (discoveryPort < 1 || discoveryPort > 65535) {
            throw new IllegalArgumentException("discoveryPort must be in range 1-65535");
        }
        if (broadcastIntervalMs <= 0) {
            throw new IllegalArgumentException("broadcastIntervalMs must be positive");
        }
        this.localPeerId = localPeerId;
        this.localTcpPort = localTcpPort;
        this.discoveryPort = discoveryPort;
        this.broadcastIntervalMs = broadcastIntervalMs;
    }

    public void registerListener(PeerDiscoveryListener listener) {
        if (listener != null) {
            listeners.add(listener);
        }
    }

    public void unregisterListener(PeerDiscoveryListener listener) {
        if (listener != null) {
            listeners.remove(listener);
        }
    }

    public boolean isRunning() {
        synchronized (stateLock) {
            return running;
        }
    }

    /**
     * Start the receiver loop and periodic broadcaster.
     */
    public void start() {
        synchronized (stateLock) {
            if (running) {
                logger.warning("LanPeerDiscovery is already running.");
                return;
            }

            try {
                // Instantiating unbound socket first to allow SO_REUSEADDR setting before bind
                rxSocket = new DatagramSocket(null);
                rxSocket.setReuseAddress(true);
                rxSocket.setBroadcast(true);
                rxSocket.bind(new InetSocketAddress(discoveryPort));

                // Tx socket bound to ephemeral port
                txSocket = new DatagramSocket();
                txSocket.setBroadcast(true);

                running = true;

                // Start receiver thread
                receiverThread = new Thread(this::receiverLoop, "pravah-discovery-rx-" + localPeerId.value());
                receiverThread.setDaemon(true);
                receiverThread.start();

                // Start broadcaster executor
                broadcasterExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
                    Thread t = new Thread(r, "pravah-discovery-tx-" + localPeerId.value());
                    t.setDaemon(true);
                    return t;
                });
                broadcasterExecutor.scheduleAtFixedRate(this::sendBroadcastAnnouncement,
                        0, broadcastIntervalMs, TimeUnit.MILLISECONDS);

                logger.info(String.format("LanPeerDiscovery started. Listening/Broadcasting on UDP port %d (Interval: %dms)",
                        discoveryPort, broadcastIntervalMs));

            } catch (SocketException e) {
                stop();
                throw new RuntimeException("Failed to initialize discovery UDP sockets on port " + discoveryPort, e);
            }
        }
    }

    /**
     * Terminate all background network activity and release UDP sockets.
     */
    public void stop() {
        synchronized (stateLock) {
            if (!running) {
                return;
            }
            running = false;

            logger.info("Stopping LanPeerDiscovery...");

            // 1. Shutdown scheduled broadcaster
            if (broadcasterExecutor != null) {
                broadcasterExecutor.shutdownNow();
                try {
                    if (!broadcasterExecutor.awaitTermination(2, TimeUnit.SECONDS)) {
                        logger.warning("Broadcaster executor did not terminate cleanly.");
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                broadcasterExecutor = null;
            }

            // 2. Close sockets to force break blocking calls
            if (rxSocket != null) {
                rxSocket.close();
                rxSocket = null;
            }
            if (txSocket != null) {
                txSocket.close();
                txSocket = null;
            }

            // 3. Interrupt receiver thread
            if (receiverThread != null) {
                receiverThread.interrupt();
                try {
                    receiverThread.join(2000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                receiverThread = null;
            }

            logger.info("LanPeerDiscovery stopped successfully.");
        }
    }

    private void receiverLoop() {
        byte[] buffer = new byte[BUFFER_SIZE];
        while (isRunning()) {
            try {
                DatagramPacket datagram = new DatagramPacket(buffer, buffer.length);
                rxSocket.receive(datagram);

                byte[] rawData = new byte[datagram.getLength()];
                System.arraycopy(datagram.getData(), datagram.getOffset(), rawData, 0, datagram.getLength());

                processIncomingData(rawData, datagram.getAddress().getHostAddress());

            } catch (SocketException e) {
                // Expected when socket is closed during shutdown
                if (!isRunning()) {
                    break;
                }
                logger.log(Level.SEVERE, "UDP Socket exception during discovery receive", e);
            } catch (IOException e) {
                logger.log(Level.SEVERE, "I/O error during discovery receive loop", e);
            } catch (Exception e) {
                logger.log(Level.SEVERE, "Unexpected error in discovery receiver loop", e);
            }
        }
    }

    private void processIncomingData(byte[] data, String sourceIp) {
        try {
            DiscoveryPacket packet = DiscoveryPacket.deserialize(data);

            // Self-exclusion: Don't discover yourself
            if (localPeerId.value().equals(packet.peerId())) {
                return;
            }

            DiscoveredPeer peer = new DiscoveredPeer(
                    PeerId.of(packet.peerId()),
                    sourceIp,
                    packet.tcpPort()
            );

            // Notify listeners
            for (PeerDiscoveryListener listener : listeners) {
                try {
                    listener.onPeerDiscovered(peer);
                } catch (Exception e) {
                    logger.log(Level.WARNING, "Error executing peer discovery listener callback", e);
                }
            }

        } catch (IllegalArgumentException e) {
            // Silently swallow invalid magic markers (LAN noise) but log unexpected format discrepancies
            if (!e.getMessage().contains("Invalid magic marker")) {
                logger.log(Level.FINE, "Received malformed/unsupported discovery packet", e);
            }
        }
    }

    private void sendBroadcastAnnouncement() {
        if (!isRunning()) return;

        try {
            DiscoveryPacket announcement = new DiscoveryPacket(localPeerId.value(), localTcpPort);
            byte[] payload = announcement.serialize();

            InetAddress broadcastAddr = InetAddress.getByName("255.255.255.255");
            DatagramPacket dPacket = new DatagramPacket(payload, payload.length, broadcastAddr, discoveryPort);

            txSocket.send(dPacket);

        } catch (IOException e) {
            if (isRunning()) {
                logger.log(Level.WARNING, "Failed to broadcast presence announcement", e);
            }
        }
    }
}