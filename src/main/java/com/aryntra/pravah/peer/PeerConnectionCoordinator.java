package com.aryntra.pravah.peer;

import com.aryntra.pravah.peer.presence.PeerPresenceBridge;
import com.aryntra.pravah.protocol.*;
import com.aryntra.pravah.security.authentication.AuthWireCodec;
import com.aryntra.pravah.security.authentication.AuthenticationChallenge;
import com.aryntra.pravah.security.authentication.AuthenticationProof;
import com.aryntra.pravah.security.authentication.AuthenticationResult;
import com.aryntra.pravah.security.identity.CryptographicIdentity;
import com.aryntra.pravah.security.identity.IdentityKeyPair;
import com.aryntra.pravah.security.trust.PeerTrustManager;
import com.aryntra.pravah.security.trust.TrustState;
import com.aryntra.pravah.transport.Transport;
import com.aryntra.pravah.transport.TransportListener;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Coordinates transport-level connections, protocol parsing, peer identity mapping,
 * presence lifecycle transitions, and trust-aware authentication handshakes.
 *
 * <p>Preserves strict architectural boundaries:
 * <ul>
 *   <li>Transport remains completely unaware of {@link PeerId} or protocol framing.</li>
 *   <li>Presence tracks physical connection state.</li>
 *   <li>Trust tracks cryptographic identity authentication and policy decisions.</li>
 *   <li>JOIN messages announce presence; AUTH_CHALLENGE and AUTH_PROOF verify identity.</li>
 * </ul>
 *
 * <p>SX.4 — Trust-Aware Connection Lifecycle</p>
 */
public class PeerConnectionCoordinator implements TransportListener {

    private static final Logger LOGGER = Logger.getLogger(PeerConnectionCoordinator.class.getName());

    private final Transport transport;
    private final PeerRegistry registry;
    private final PeerPresenceBridge presenceBridge;
    private final ProtocolSessionManager sessionManager;
    private final PeerTrustManager trustManager;
    private final IdentityKeyPair localKeyPair;
    private final CryptographicIdentity localIdentity;

    private final ConcurrentHashMap<String, FrameDecoder> decoders = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, PeerId> connectionToPeer = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<PeerId, String> peerToConnection = new ConcurrentHashMap<>();

    private volatile ProtocolListener protocolListener;

    public PeerConnectionCoordinator(Transport transport,
                                     PeerRegistry registry,
                                     PeerPresenceBridge presenceBridge) {
        this(transport, registry, presenceBridge, new ProtocolSessionManager(), null, null, null);
    }

    public PeerConnectionCoordinator(Transport transport,
                                     PeerRegistry registry,
                                     PeerPresenceBridge presenceBridge,
                                     ProtocolSessionManager sessionManager) {
        this(transport, registry, presenceBridge, sessionManager, null, null, null);
    }

    public PeerConnectionCoordinator(Transport transport,
                                     PeerRegistry registry,
                                     PeerPresenceBridge presenceBridge,
                                     ProtocolSessionManager sessionManager,
                                     PeerTrustManager trustManager,
                                     IdentityKeyPair localKeyPair,
                                     CryptographicIdentity localIdentity) {
        this.transport = Objects.requireNonNull(transport, "transport must not be null");
        this.registry = Objects.requireNonNull(registry, "registry must not be null");
        this.presenceBridge = Objects.requireNonNull(presenceBridge, "presenceBridge must not be null");
        this.sessionManager = Objects.requireNonNull(sessionManager, "sessionManager must not be null");
        this.trustManager = trustManager;
        this.localKeyPair = localKeyPair;
        this.localIdentity = localIdentity;

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

    public PeerTrustManager getTrustManager() {
        return trustManager;
    }

    public Optional<PeerId> getPeerIdForConnection(String connectionId) {
        if (connectionId == null) return Optional.empty();
        return Optional.ofNullable(connectionToPeer.get(connectionId));
    }

    public Optional<String> getConnectionIdForPeer(PeerId peerId) {
        if (peerId == null) return Optional.empty();
        return Optional.ofNullable(peerToConnection.get(peerId));
    }

    /**
     * Issues an authentication challenge to the specified remote peer over a given connection path.
     */
    public boolean initiateAuthentication(PeerId peerId, String connectionId) {
        if (trustManager == null || connectionId == null) {
            return false;
        }
        try {
            AuthenticationChallenge challenge = trustManager.issueChallengeForPeer(peerId);
            byte[] payload = AuthWireCodec.encodeChallenge(challenge);
            String localSender = localIdentity != null ? localIdentity.peerId().value() : "local";
            Message challengeMsg = new Message(MessageType.AUTH_CHALLENGE, localSender, "chal-" + UUID.randomUUID(), payload);
            byte[] framed = FrameEncoder.encode(MessageEncoder.encode(challengeMsg));
            transport.send(connectionId, framed);
            LOGGER.info(() -> "Dispatched AUTH_CHALLENGE " + challenge.challengeId() + " to peer " + peerId.value() + " via " + connectionId);
            return true;
        } catch (Exception ex) {
            LOGGER.log(Level.WARNING, "Failed to dispatch AUTH_CHALLENGE to " + peerId.value(), ex);
            return false;
        }
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

            boolean hasOtherConnections = connectionToPeer.containsValue(peerId);
            if (hasOtherConnections) {
                for (var entry : connectionToPeer.entrySet()) {
                    if (entry.getValue().equals(peerId)) {
                        peerToConnection.put(peerId, entry.getKey());
                        break;
                    }
                }
            }

            presenceBridge.handleConnectionClosed(peerId, connectionId);

            if (!hasOtherConnections) {
                sessionManager.resetPeer(peerId.value());
                LOGGER.info(() -> "Peer disconnected and unregistered: " + peerId.value());
            } else {
                LOGGER.info(() -> "Closed path " + connectionId + " for peer " + peerId.value() + " (other paths remain active)");
            }
        }
    }

    private void handleInboundMessage(String connectionId, Message message) {
        if (message.type() == MessageType.JOIN) {
            PeerId peerId = PeerId.of(message.senderId());
            connectionToPeer.put(connectionId, peerId);
            peerToConnection.put(peerId, connectionId);
            presenceBridge.handlePeerConnected(peerId, connectionId);

            if (!sessionManager.isPeerJoined(peerId.value())) {
                sessionManager.processMessage(message);
                LOGGER.info(() -> "Peer presence registered via JOIN: " + peerId.value());
            } else {
                LOGGER.info(() -> "Secondary path connected for existing peer session: " + peerId.value() + " via " + connectionId);
            }
        } else if (message.type() == MessageType.AUTH_CHALLENGE) {
            handleInboundAuthChallenge(connectionId, message);
        } else if (message.type() == MessageType.AUTH_PROOF) {
            handleInboundAuthProof(connectionId, message);
        } else if (message.type() == MessageType.LEAVE) {
            PeerId peerId = PeerId.of(message.senderId());
            connectionToPeer.remove(connectionId);
            peerToConnection.remove(peerId, connectionId);
            presenceBridge.handlePeerDisconnected(peerId);
            sessionManager.processMessage(message);
            LOGGER.info(() -> "Peer successfully left session: " + peerId.value());
        } else {
            sessionManager.processMessage(message);
        }
    }

    private void handleInboundAuthChallenge(String connectionId, Message message) {
        if (localKeyPair == null || localIdentity == null) {
            LOGGER.fine("Received AUTH_CHALLENGE but no local identity configured; ignoring");
            return;
        }
        try {
            AuthenticationChallenge challenge = AuthWireCodec.decodeChallenge(message.payload());
            String domain = trustManager != null ? trustManager.authService().domain() : AuthenticationProof.DEFAULT_DOMAIN;
            AuthenticationProof proof = AuthenticationProof.generate(challenge, localIdentity, localKeyPair, domain);
            byte[] proofPayload = AuthWireCodec.encodeProof(proof);

            Message proofMsg = new Message(MessageType.AUTH_PROOF, localIdentity.peerId().value(), "proof-" + UUID.randomUUID(), proofPayload);
            byte[] framed = FrameEncoder.encode(MessageEncoder.encode(proofMsg));
            transport.send(connectionId, framed);
            LOGGER.info(() -> "Responded to AUTH_CHALLENGE " + challenge.challengeId() + " with AUTH_PROOF via " + connectionId);
        } catch (Exception ex) {
            LOGGER.log(Level.WARNING, "Error responding to AUTH_CHALLENGE from " + connectionId + ": " + ex.getMessage(), ex);
        }
    }

    private void handleInboundAuthProof(String connectionId, Message message) {
        if (trustManager == null) {
            LOGGER.fine("Received AUTH_PROOF but no PeerTrustManager configured; ignoring");
            return;
        }
        try {
            AuthenticationProof proof = AuthWireCodec.decodeProof(message.payload());
            PeerId peerId = PeerId.of(message.senderId());
            AuthenticationResult result = trustManager.evaluateProof(peerId, proof);
            LOGGER.info(() -> "Processed AUTH_PROOF from " + peerId.value() + " with result: " + result);
        } catch (Exception ex) {
            LOGGER.log(Level.WARNING, "Error processing AUTH_PROOF from " + connectionId + ": " + ex.getMessage(), ex);
            PeerId peerId = connectionToPeer.get(connectionId);
            if (peerId != null) {
                trustManager.transitionState(peerId, TrustState.REJECTED);
            }
        }
    }
}
