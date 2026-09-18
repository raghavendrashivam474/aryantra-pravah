package com.aryntra.pravah.protocol;

import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages protocol state machines and chat semantics for logical peers.
 *
 * <p>This manager is transport-independent and handles peer session states:
 * <ul>
 *   <li>{@code JOIN} -> transitions peer to {@link PeerState#JOINED}</li>
 *   <li>{@code MESSAGE} -> delivered only if peer is in {@link PeerState#JOINED} state</li>
 *   <li>{@code LEAVE} -> transitions peer to {@link PeerState#LEFT}</li>
 * </ul>
 *
 * <p>State transitions are strictly deterministic. Invalid sequences (e.g. MESSAGE before JOIN,
 * duplicate JOIN, or LEAVE when not joined) result in a {@link ProtocolException}.</p>
 */
public class ProtocolSessionManager {

    private final ConcurrentHashMap<String, PeerState> peerStates = new ConcurrentHashMap<>();
    private volatile ProtocolListener listener;

    public ProtocolSessionManager() {
    }

    public ProtocolSessionManager(ProtocolListener listener) {
        this.listener = listener;
    }

    public void setListener(ProtocolListener listener) {
        this.listener = listener;
    }

    /**
     * Processes an incoming validated {@link Message} according to protocol semantics.
     *
     * @param message the message to process
     * @throws NullPointerException if message is null
     * @throws ProtocolException if the message violates protocol state sequence
     */
    public synchronized void processMessage(Message message) {
        Objects.requireNonNull(message, "message must not be null");

        String peerId = message.senderId();
        PeerState currentState = peerStates.get(peerId);

        switch (message.type()) {
            case JOIN -> handleJoin(peerId, currentState, message);
            case MESSAGE -> handleMessage(peerId, currentState, message);
            case LEAVE -> handleLeave(peerId, currentState, message);
            default -> throw new ProtocolException("Unsupported message type: " + message.type());
        }
    }

    private void handleJoin(String peerId, PeerState currentState, Message message) {
        if (currentState == PeerState.JOINED) {
            throw new ProtocolException("Peer '" + peerId + "' is already in JOINED state");
        }

        peerStates.put(peerId, PeerState.JOINED);
        ProtocolListener currentListener = this.listener;
        if (currentListener != null) {
            currentListener.onPeerJoined(peerId, message);
        }
    }

    private void handleMessage(String peerId, PeerState currentState, Message message) {
        if (currentState == null) {
            throw new ProtocolException("Peer '" + peerId + "' cannot send MESSAGE before JOIN");
        }
        if (currentState == PeerState.LEFT) {
            throw new ProtocolException("Peer '" + peerId + "' cannot send MESSAGE after LEAVE");
        }

        ProtocolListener currentListener = this.listener;
        if (currentListener != null) {
            currentListener.onMessageReceived(peerId, message);
        }
    }

    private void handleLeave(String peerId, PeerState currentState, Message message) {
        if (currentState == null) {
            throw new ProtocolException("Peer '" + peerId + "' cannot LEAVE without prior JOIN");
        }
        if (currentState == PeerState.LEFT) {
            throw new ProtocolException("Peer '" + peerId + "' is already in LEFT state");
        }

        peerStates.put(peerId, PeerState.LEFT);
        ProtocolListener currentListener = this.listener;
        if (currentListener != null) {
            currentListener.onPeerLeft(peerId, message);
        }
    }

    /**
     * Returns the current session state of a logical peer.
     *
     * @param peerId the logical peer identifier
     * @return the {@link PeerState}, or null if the peer has never joined
     */
    public PeerState getPeerState(String peerId) {
        return peerStates.get(peerId);
    }

    /**
     * Returns true if the peer is currently in {@link PeerState#JOINED} state.
     */
    public boolean isPeerJoined(String peerId) {
        return peerStates.get(peerId) == PeerState.JOINED;
    }

    /**
     * Resets state for a specific peer. Useful if transport disconnect occurs.
     */
    public void resetPeer(String peerId) {
        if (peerId != null) {
            peerStates.remove(peerId);
        }
    }

    /**
     * Clears all peer session states.
     */
    public void reset() {
        peerStates.clear();
    }
}