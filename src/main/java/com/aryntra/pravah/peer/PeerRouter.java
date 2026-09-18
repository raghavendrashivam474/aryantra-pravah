package com.aryntra.pravah.peer;

import com.aryntra.pravah.protocol.FrameEncoder;
import com.aryntra.pravah.protocol.Message;
import com.aryntra.pravah.protocol.MessageEncoder;
import com.aryntra.pravah.protocol.MessageType;
import com.aryntra.pravah.transport.Transport;

import java.util.Objects;
import java.util.Optional;

/**
 * Resolves logical peer destinations into active transport connections and dispatches framed messages.
 *
 * <p>PeerRouter serves as the bridge between application-level peer-oriented communication
 * and connection-oriented transport delivery:</p>
 *
 * <pre>
 * Application
 *     │
 *     │ send(destinationPeerId, message)
 *     ▼
 * PeerRouter
 *     │
 *     ├── 1. Resolve destination in PeerRegistry -> PeerRecord -> connectionId
 *     ├── 2. Encode Message via MessageEncoder
 *     ├── 3. Frame binary data via FrameEncoder
 *     ▼
 * Transport.send(connectionId, framedBytes)
 * </pre>
 *
 * S3.3 - Phase 3: Peer-to-Peer Routing
 */
public class PeerRouter {

    private final PeerRegistry registry;
    private final Transport transport;

    public PeerRouter(PeerRegistry registry, Transport transport) {
        this.registry = Objects.requireNonNull(registry, "registry must not be null");
        this.transport = Objects.requireNonNull(transport, "transport must not be null");
    }

    /**
     * Sends a logical protocol message to a specific peer destination.
     *
     * @param destination the destination PeerId (must not be null)
     * @param message     the protocol message to send (must not be null)
     * @throws PeerRoutingException if the peer is unknown, disconnected, or if transport fails
     * @throws NullPointerException if destination or message is null
     */
    public void send(PeerId destination, Message message) {
        Objects.requireNonNull(destination, "destination must not be null");
        Objects.requireNonNull(message, "message must not be null");

        Optional<PeerRecord> maybePeer = registry.lookup(destination);
        if (maybePeer.isEmpty()) {
            throw new PeerRoutingException("Cannot route message: peer " + destination + " is not registered");
        }

        PeerRecord record = maybePeer.get();
        if (!record.isConnected()) {
            throw new PeerRoutingException("Cannot route message: peer " + destination + " is disconnected (no active connection)");
        }

        String connectionId = record.connectionId();

        try {
            byte[] encoded = MessageEncoder.encode(message);
            byte[] framed = FrameEncoder.encode(encoded);
            transport.send(connectionId, framed);
        } catch (Exception ex) {
            throw new PeerRoutingException("Failed to dispatch message to peer " + destination + " via connection " + connectionId + ": " + ex.getMessage(), ex);
        }
    }

    /**
     * Convenience method to send raw payload text as a MESSAGE type to a destination peer.
     *
     * @param sender      the originating PeerId
     * @param destination the destination PeerId
     * @param messageId   the unique message identifier
     * @param payload     the byte array payload
     */
    public void send(PeerId sender, PeerId destination, String messageId, byte[] payload) {
        Objects.requireNonNull(sender, "sender must not be null");
        Message message = new Message(MessageType.MESSAGE, sender.value(), messageId, payload);
        send(destination, message);
    }

    public PeerRegistry registry() {
        return registry;
    }

    public Transport transport() {
        return transport;
    }
}