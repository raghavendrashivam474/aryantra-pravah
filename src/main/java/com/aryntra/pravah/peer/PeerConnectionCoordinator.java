package com.aryntra.pravah.peer;

import com.aryntra.pravah.peer.presence.PeerPresenceBridge;
import com.aryntra.pravah.protocol.*;
import com.aryntra.pravah.transport.Transport;
import com.aryntra.pravah.transport.TransportListener;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Coordinates transport-level connections, protocol parsing, peer identity mapping,
 * and presence lifecycle transitions.
 *
 * <p>Preserves strict architectural boundaries:
 * <ul>
 *   <li>Transport remains completely unaware of {@link PeerId} or protocol framing.</li>
 *   <li>Presence remains separate from protocol session state.</li>
 *   <li>JOIN messages act as the single source of truth for logical peer identity.</li>
 * </ul>
 */
public class PeerConnectionCoordinator implements TransportListener {

    private static final Logger LOGGER = Logger.getLogger(PeerConnectionCoordinator.class.getName());

    private final Transport transport;
    private final PeerRegistry registry;
    private final PeerPresenceBridge presenceBridge;
    private final ProtocolSessionManager sessionManager;

    private final ConcurrentHashMap<String, FrameDecoder> decoders = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, PeerId> connectionToPeer = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<PeerId, String> peerToConnection = new ConcurrentHashMap<>();

    private volatile ProtocolListener protocolListener;

    public PeerConnectionCoordinator(Transport transport,
                                     PeerRegistry registry,
                                     PeerPresenceBridge presenceBridge) {
        this(transport, registry, presenceBridge, new ProtocolSessionManager());
    }

    public PeerConnectionCoordinator(Transport transport,
                                     PeerRegistry registry,
                                     PeerPresenceBridge presenceBridge,
                                     ProtocolSessionManager sessionManager) {
        this.transport = Objects.requireNonNull(transport, "transport must not be null");
        this.registry = Objects.requireNonNull(registry, "registry must not be null");
        this.presenceBridge = Objects.requireNonNull(presenceBridge, "presenceBridge must not be null");
        this.sessionManager = Objects.requireNonNull(sessionManager, "sessionManager must not be null");

        // Forward internal session events to downstream listener
        this.sessionManager.setListener(new ProtocolListener() {
            @Override
            public void onPeerJoined(String peerIdStr, Message message) {
                ProtocolListener l = protocolListener;
                if (l != null) {
                    l.onPeerJoined(peerIdStr, message);
                }
            }

            @Override
            public void onMessageReceived(String peerIdStr, Message message) {
                ProtocolListener l = protocolListener;
                if (l != null) {
                    l.onMessageReceived(peerIdStr, message);
                }
            }

            @Override
            public void onPeerLeft(String peerIdStr, Message message) {
                ProtocolListener l = protocolListener;
                if (l != null) {
                    l.onPeerLeft(peerIdStr, message);
                }
            }
        });

        this.transport.setListener(this);
    }

    public void setProtocolListener(ProtocolListener listener) {
        this.protocolListener = listener;
    }

    public ProtocolSessionManager getSessionManager() {
        return sessionManager;
    }

    public Optional<PeerId> getPeerIdForConnection(String connectionId) {
        if (connectionId == null) return Optional.empty();
        return Optional.ofNullable(connectionToPeer.get(connectionId));
    }

    public Optional<String> getConnectionIdForPeer(PeerId peerId) {
        if (peerId == null) return Optional.empty();
        return Optional.ofNullable(peerToConnection.get(peerId));
    }

    @Override
    public void onConnectionOpened(String connectionId) {
        LOGGER.fine(() -> "Transport connection opened: " + connectionId);
        decoders.computeIfAbsent(connectionId, k -> new FrameDecoder());
    }

    @Override
    public void onDataReceived(String senderId, byte[] payload) {
        if (senderId == null || payload == null || payload.length == 0) {
            return;
        }

        FrameDecoder decoder = decoders.computeIfAbsent(senderId, k -> new FrameDecoder());
        List<byte[]> frames;
        try {
            frames = decoder.feed(payload);
        } catch (ProtocolException ex) {
            LOGGER.log(Level.WARNING, "Frame decoding error from " + senderId + ": " + ex.getMessage(), ex);
            return;
        }

        for (byte[] frame : frames) {
            try {
                Message message = MessageParser.parse(frame);
                handleInboundMessage(senderId, message);
            } catch (ProtocolException ex) {
                LOGGER.log(Level.WARNING, "Message parsing error from " + senderId + ": " + ex.getMessage(), ex);
            }
        }
    }

    @Override
    public void onConnectionClosed(String connectionId) {
        LOGGER.fine(() -> "Transport connection closed: " + connectionId);
        decoders.remove(connectionId);
        PeerId peerId = connectionToPeer.remove(connectionId);
        if (peerId != null) {
            peerToConnection.remove(peerId, connectionId);
            presenceBridge.handlePeerDisconnected(peerId);
            sessionManager.resetPeer(peerId.value());
            LOGGER.info(() -> "Peer disconnected and unregistered: " + peerId.value());
        }
    }

    private void handleInboundMessage(String connectionId, Message message) {
        if (message.type() == MessageType.JOIN) {
            PeerId peerId = PeerId.of(message.senderId());

            // Bind connection <-> PeerId
            connectionToPeer.put(connectionId, peerId);
            peerToConnection.put(peerId, connectionId);

            // Notify Presence Bridge FIRST (promotes to CONNECTED & updates PeerRegistry)
            presenceBridge.handlePeerConnected(peerId, connectionId);

            // Then process protocol state transition (which notifies downstream listeners)
            sessionManager.processMessage(message);

            LOGGER.info(() -> "Peer successfully authenticated and connected: " + peerId.value());
        } else if (message.type() == MessageType.LEAVE) {
            PeerId peerId = PeerId.of(message.senderId());

            // Unbind and notify presence bridge FIRST
            connectionToPeer.remove(connectionId);
            peerToConnection.remove(peerId, connectionId);
            presenceBridge.handlePeerDisconnected(peerId);

            // Then process protocol state transition (which notifies downstream listeners)
            sessionManager.processMessage(message);

            LOGGER.info(() -> "Peer successfully left session: " + peerId.value());
        } else {
            // Standard MESSAGE or other protocol messages
            sessionManager.processMessage(message);
        }
    }
}