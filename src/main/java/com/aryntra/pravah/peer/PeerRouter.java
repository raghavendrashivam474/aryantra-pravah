package com.aryntra.pravah.peer;

import com.aryntra.pravah.connectivity.ConnectivityPath;
import com.aryntra.pravah.connectivity.PathSelectionPolicy;
import com.aryntra.pravah.connectivity.PeerConnectivity;
import com.aryntra.pravah.connectivity.PeerConnectivityRegistry;
import com.aryntra.pravah.protocol.FrameEncoder;
import com.aryntra.pravah.protocol.Message;
import com.aryntra.pravah.protocol.MessageEncoder;
import com.aryntra.pravah.protocol.MessageType;
import com.aryntra.pravah.transport.Transport;

import java.util.List;
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
 *     ├── 1. Resolve path via PeerConnectivityRegistry &amp; PathSelectionPolicy
 *     │      OR fallback to PeerRegistry -> PeerRecord -> connectionId
 *     ├── 2. Encode Message via MessageEncoder
 *     ├── 3. Frame binary data via FrameEncoder
 *     ▼
 * Transport.send(connectionId, framedBytes)
 * </pre>
 *
 * S3.3 - Phase 3: Peer-to-Peer Routing
 * S6.5 - Phase 6: Deterministic Path Selection (ADR 001)
 * S8.5 - Phase 8: Multi-Path Operation & Path Selection Policies (ADR 015)
 */
public class PeerRouter {

    private final PeerRegistry registry;
    private final Transport transport;
    private final PeerConnectivityRegistry connectivityRegistry; // nullable for backward compat
    private final PathSelectionPolicy selectionPolicy;

    /**
     * Phase 3 legacy constructor — routes via PeerRegistry only.
     */
    public PeerRouter(PeerRegistry registry, Transport transport) {
        this(registry, transport, null, PathSelectionPolicy.defaultPolicy());
    }

    /**
     * S6.5 constructor — enables deterministic path selection from PeerConnectivityRegistry.
     *
     * @param registry             the peer registry (must not be null)
     * @param transport            the transport implementation (must not be null)
     * @param connectivityRegistry the connectivity registry (may be null for fallback mode)
     */
    public PeerRouter(PeerRegistry registry, Transport transport, PeerConnectivityRegistry connectivityRegistry) {
        this(registry, transport, connectivityRegistry, PathSelectionPolicy.defaultPolicy());
    }

    /**
     * S8.5 constructor — enables configurable deterministic path selection policies.
     *
     * @param registry             the peer registry (must not be null)
     * @param transport            the transport implementation (must not be null)
     * @param connectivityRegistry the connectivity registry (may be null for fallback mode)
     * @param selectionPolicy      the path selection policy (must not be null)
     */
    public PeerRouter(PeerRegistry registry, Transport transport, PeerConnectivityRegistry connectivityRegistry, PathSelectionPolicy selectionPolicy) {
        this.registry = Objects.requireNonNull(registry, "registry must not be null");
        this.transport = Objects.requireNonNull(transport, "transport must not be null");
        this.connectivityRegistry = connectivityRegistry;
        this.selectionPolicy = Objects.requireNonNull(selectionPolicy, "selectionPolicy must not be null");
    }

    /**
     * Sends a logical protocol message to a specific peer destination.
     *
     * @param destination the destination PeerId (must not be null)
     * @param message     the protocol message to send (must not be null)
     * @throws PeerRoutingException if the peer has no usable connection, or if transport fails
     * @throws NullPointerException if destination or message is null
     */
    public void send(PeerId destination, Message message) {
        Objects.requireNonNull(destination, "destination must not be null");
        Objects.requireNonNull(message, "message must not be null");

        String connectionId = resolveConnectionId(destination);
        try {
            byte[] encoded = MessageEncoder.encode(message);
            byte[] framed = FrameEncoder.encode(encoded);
            transport.send(connectionId, framed);
        } catch (PeerRoutingException pre) {
            throw pre;
        } catch (Exception ex) {
            throw new PeerRoutingException("Failed to dispatch message to peer " + destination + " via connection " + connectionId + ": " + ex.getMessage(), ex);
        }
    }

    /**
     * Resolves the active transport connection ID for a given destination peer.
     *
     * <p>Selection Policy (ADR 001 / ADR 015):
     * <ol>
     *   <li>If {@code connectivityRegistry} is present, look up active paths for {@code destination}.</li>
     *   <li>Apply configured {@link PathSelectionPolicy} to choose the active path.</li>
     *   <li>Select the active path's {@code connectionId}.</li>
     *   <li>If no active path is found, fall back to legacy {@link PeerRegistry}.</li>
     *   <li>If both fail, throw {@link PeerRoutingException}.</li>
     * </ol>
     * </p>
     *
     * @param destination the destination PeerId
     * @return the resolved connection ID
     * @throws PeerRoutingException if no usable connection exists
     */
    public String resolveConnectionId(PeerId destination) {
        // 1. Try resolving through connectivity registry if available
        if (connectivityRegistry != null) {
            Optional<PeerConnectivity> maybeConnectivity = connectivityRegistry.lookup(destination);
            if (maybeConnectivity.isPresent()) {
                List<ConnectivityPath> activePaths = maybeConnectivity.get().activePaths();
                if (!activePaths.isEmpty()) {
                    Optional<ConnectivityPath> selected = selectionPolicy.selectPath(destination, activePaths);
                    if (selected.isPresent() && selected.get().connectionId() != null && !selected.get().connectionId().isBlank()) {
                        return selected.get().connectionId();
                    }
                }
            }
        }

        // 2. Fallback to legacy PeerRegistry
        Optional<PeerRecord> maybePeer = registry.lookup(destination);
        if (maybePeer.isEmpty()) {
            throw new PeerRoutingException("Cannot route message: peer " + destination + " is not registered");
        }

        PeerRecord record = maybePeer.get();
        if (!record.isConnected()) {
            throw new PeerRoutingException("Cannot route message: peer " + destination + " is disconnected (no active connection)");
        }

        return record.connectionId();
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

    public PeerConnectivityRegistry connectivityRegistry() {
        return connectivityRegistry;
    }

    public PathSelectionPolicy selectionPolicy() {
        return selectionPolicy;
    }
}