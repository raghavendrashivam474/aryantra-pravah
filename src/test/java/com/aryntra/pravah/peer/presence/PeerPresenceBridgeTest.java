package com.aryntra.pravah.peer.presence;

import com.aryntra.pravah.peer.PeerId;
import com.aryntra.pravah.peer.PeerRecord;
import com.aryntra.pravah.peer.PeerRegistry;
import com.aryntra.pravah.peer.discovery.DiscoveredPeer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("PeerPresenceBridge Integration Specification")
class PeerPresenceBridgeTest {

    private PeerRegistry registry;
    private PeerPresenceManager presenceManager;
    private PeerPresenceBridge bridge;

    @BeforeEach
    void setUp() {
        registry = new PeerRegistry();
        presenceManager = new PeerPresenceManager(5000); // 5s TTL
        bridge = new PeerPresenceBridge(registry, presenceManager);
    }

    @Test
    @DisplayName("Should register discovered peer in registry as disconnected, and mark as AVAILABLE in presence")
    void testDiscoveryFlow() {
        PeerId id = PeerId.of("discovered-node");
        DiscoveredPeer peer = new DiscoveredPeer(id, "192.168.1.10", 8080);

        bridge.onPeerDiscovered(peer);

        // Verify registry contains peer with NO active connection ID
        assertTrue(registry.contains(id));
        PeerRecord record = registry.lookup(id).orElseThrow();
        assertFalse(record.isConnected());
        assertNull(record.connectionId());

        // Verify presence transitions to AVAILABLE
        PeerPresence presence = presenceManager.getPresence(id);
        assertEquals(PeerPresenceState.AVAILABLE, presence.state());
        assertEquals("192.168.1.10", presence.hostAddress());
        assertEquals(8080, presence.port());
    }

    @Test
    @DisplayName("Should successfully coordinate active connection state across registry and presence")
    void testConnectionFlow() {
        PeerId id = PeerId.of("connected-node");

        bridge.handlePeerConnected(id, "conn-999");

        // Verify registry reflects active connection ID
        assertTrue(registry.contains(id));
        PeerRecord record = registry.lookup(id).orElseThrow();
        assertTrue(record.isConnected());
        assertEquals("conn-999", record.connectionId());

        // Verify presence transitions to CONNECTED
        assertEquals(PeerPresenceState.CONNECTED, presenceManager.getPresence(id).state());
    }

    @Test
    @DisplayName("Should update active connection back to disconnected while preserving last seen metadata")
    void testDisconnectionFlow() {
        PeerId id = PeerId.of("node-disconnect");
        DiscoveredPeer peer = new DiscoveredPeer(id, "10.0.0.5", 7000);

        // 1. Discover
        bridge.onPeerDiscovered(peer);
        // 2. Connect
        bridge.handlePeerConnected(id, "conn-abc");
        // 3. Disconnect
        bridge.handlePeerDisconnected(id);

        // Registry should show disconnected (null connectionId)
        PeerRecord record = registry.lookup(id).orElseThrow();
        assertFalse(record.isConnected());
        assertNull(record.connectionId());

        // Presence transitions back to AVAILABLE because we are within TTL
        PeerPresence presence = presenceManager.getPresence(id);
        assertEquals(PeerPresenceState.AVAILABLE, presence.state());
        assertEquals("10.0.0.5", presence.hostAddress());
        assertEquals(7000, presence.port());
    }
}