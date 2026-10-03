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

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Resolves logical peer destinations into active transport connections and dispatches framed messages.
 *
 * <p>PeerRouter serves as the bridge between application-level peer-oriented communication
 * and connection-oriented transport delivery.</p>
 */
public class PeerRouter {

    private final PeerRegistry registry;
    private final Transport transport;
    private final PeerConnectivityRegistry connectivityRegistry; // nullable for backward compat
    private final PathSelectionPolicy selectionPolicy;

    public PeerRouter(PeerRegistry registry, Transport transport) {
        this(registry, transport, null, PathSelectionPolicy.defaultPolicy());
    }

    public PeerRouter(PeerRegistry registry, Transport transport, PeerConnectivityRegistry connectivityRegistry) {
        this(registry, transport, connectivityRegistry, PathSelectionPolicy.defaultPolicy());
    }

    public PeerRouter(PeerRegistry registry, Transport transport, PeerConnectivityRegistry connectivityRegistry, PathSelectionPolicy selectionPolicy) {
        this.registry = Objects.requireNonNull(registry, "registry must not be null");
        this.transport = Objects.requireNonNull(transport, "transport must not be null");
        this.connectivityRegistry = connectivityRegistry;
        this.selectionPolicy = Objects.requireNonNull(selectionPolicy, "selectionPolicy must not be null");
    }

    /**
     * Sends a logical protocol message to a specific peer destination with automatic multi-path failover.
     *
     * @param destination the destination PeerId (must not be null)
     * @param message     the protocol message to send (must not be null)
     * @throws PeerRoutingException if no path succeeds or peer is not reachable
     */
    public void send(PeerId destination, Message message) {
        Objects.requireNonNull(destination, "destination must not be null");
        Objects.requireNonNull(message, "message must not be null");

        byte[] encoded = MessageEncoder.encode(message);
        byte[] framed = FrameEncoder.encode(encoded);

        // 1. Multi-path resolution and automatic failover dispatch
        if (connectivityRegistry != null) {
            Optional<PeerConnectivity> maybeConnectivity = connectivityRegistry.lookup(destination);
            if (maybeConnectivity.isPresent()) {
                PeerConnectivity peerConn = maybeConnectivity.get();
                List<ConnectivityPath> availablePaths = new ArrayList<>(peerConn.activePaths());

                while (!availablePaths.isEmpty()) {
                    Optional<ConnectivityPath> selected = selectionPolicy.selectPath(destination, availablePaths);
                    if (selected.isEmpty()) {
                        break;
                    }
                    ConnectivityPath path = selected.get();
                    String connectionId = path.connectionId();
                    if (connectionId != null && !connectionId.isBlank()) {
                        try {
                            transport.send(connectionId, framed);
                            return; // Successfully sent
                        } catch (Exception ex) {
                            // Transport write failed on this path: deactivate path in registry so future routing excludes it
                            peerConn.addPath(path.deactivate());
                            // Remove from remaining candidates for this send attempt
                            availablePaths.remove(path);
                        }
                    } else {
                        availablePaths.remove(path);
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

        String connectionId = record.connectionId();
        try {
            transport.send(connectionId, framed);
        } catch (PeerRoutingException pre) {
            throw pre;
        } catch (Exception ex) {
            throw new PeerRoutingException("Failed to dispatch message to peer " + destination + " via connection " + connectionId + ": " + ex.getMessage(), ex);
        }
    }

    public String resolveConnectionId(PeerId destination) {
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

    public void send(PeerId sender, PeerId destination, String messageId, byte[] payload) {
        Objects.requireNonNull(sender, "sender must not be null");
        Message message = new Message(MessageType.MESSAGE, sender.value(), messageId, payload);
        send(destination, message);
    }

    public PeerRegistry registry() { return registry; }
    public Transport transport() { return transport; }
    public PeerConnectivityRegistry connectivityRegistry() { return connectivityRegistry; }
    public PathSelectionPolicy selectionPolicy() { return selectionPolicy; }
}