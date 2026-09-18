package com.aryntra.pravah.peer.discovery;

import com.aryntra.pravah.peer.PeerId;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("LanPeerDiscovery Lifecycle & Network Integration Specification")
class LanPeerDiscoveryTest {

    private static final int TEST_DISCOVERY_PORT = 49152; // Ephemeral range UDP port for safety

    @Nested
    @DisplayName("Constructor Parameters Validation")
    class ConstructorValidation {

        @Test
        @DisplayName("Should validate parameters strictly")
        void shouldValidateParams() {
            PeerId id = PeerId.of("node-test");
            assertThrows(IllegalArgumentException.class, () -> new LanPeerDiscovery(null, 8080, TEST_DISCOVERY_PORT, 1000));
            assertThrows(IllegalArgumentException.class, () -> new LanPeerDiscovery(id, 0, TEST_DISCOVERY_PORT, 1000));
            assertThrows(IllegalArgumentException.class, () -> new LanPeerDiscovery(id, 65536, TEST_DISCOVERY_PORT, 1000));
            assertThrows(IllegalArgumentException.class, () -> new LanPeerDiscovery(id, 8080, 0, 1000));
            assertThrows(IllegalArgumentException.class, () -> new LanPeerDiscovery(id, 8080, TEST_DISCOVERY_PORT, 0));
            assertThrows(IllegalArgumentException.class, () -> new LanPeerDiscovery(id, 8080, TEST_DISCOVERY_PORT, -50));
        }
    }

    @Nested
    @DisplayName("Idempotent Lifecycle Management")
    class LifecycleTests {

        @Test
        @DisplayName("Should successfully start and stop, transitioning state correctly")
        void testStartStopTransitions() {
            PeerId localId = PeerId.of("lifecycle-node");
            LanPeerDiscovery discovery = new LanPeerDiscovery(localId, 9000, TEST_DISCOVERY_PORT, 1000);

            assertFalse(discovery.isRunning());

            discovery.start();
            assertTrue(discovery.isRunning());

            // Repeated start should be a safe no-op
            discovery.start();
            assertTrue(discovery.isRunning());

            discovery.stop();
            assertFalse(discovery.isRunning());

            // Repeated stop should be a safe no-op
            discovery.stop();
            assertFalse(discovery.isRunning());
        }
    }

    @Nested
    @DisplayName("LAN Multi-Node Integration")
    class NetworkIntegrationTests {

        @Test
        @DisplayName("Two nodes should mutually discover each other and apply self-exclusion")
        void testMutualDiscovery() throws Exception {
            PeerId idA = PeerId.of("node-alpha");
            PeerId idB = PeerId.of("node-beta");

            // Setup discovery ports
            int discoveryPort = 49155;

            LanPeerDiscovery nodeA = new LanPeerDiscovery(idA, 6001, discoveryPort, 50);
            LanPeerDiscovery nodeB = new LanPeerDiscovery(idB, 6002, discoveryPort, 50);

            CopyOnWriteArrayList<DiscoveredPeer> nodeADiscoveries = new CopyOnWriteArrayList<>();
            CopyOnWriteArrayList<DiscoveredPeer> nodeBDiscoveries = new CopyOnWriteArrayList<>();

            nodeA.registerListener(nodeADiscoveries::add);
            nodeB.registerListener(nodeBDiscoveries::add);

            try {
                nodeA.start();
                nodeB.start();

                // Wait for scheduled broadcasts to occur (broadcast interval 50ms)
                long start = System.currentTimeMillis();
                while ((nodeADiscoveries.isEmpty() || nodeBDiscoveries.isEmpty()) &&
                       (System.currentTimeMillis() - start < 3000)) {
                    Thread.sleep(10);
                }

                // Verify self-exclusion (Node A should never discover node-alpha, Node B should never discover node-beta)
                for (DiscoveredPeer peer : nodeADiscoveries) {
                    assertNotEquals(idA, peer.peerId(), "Node A discovered itself!");
                }
                for (DiscoveredPeer peer : nodeBDiscoveries) {
                    assertNotEquals(idB, peer.peerId(), "Node B discovered itself!");
                }

                // Verify mutual discovery
                assertFalse(nodeADiscoveries.isEmpty(), "Node A did not discover anything!");
                assertFalse(nodeBDiscoveries.isEmpty(), "Node B did not discover anything!");

                DiscoveredPeer discoveredByA = nodeADiscoveries.get(0);
                assertEquals(idB, discoveredByA.peerId());
                assertEquals(6002, discoveredByA.port());

                DiscoveredPeer discoveredByB = nodeBDiscoveries.get(0);
                assertEquals(idA, discoveredByB.peerId());
                assertEquals(6001, discoveredByB.port());

            } finally {
                nodeA.stop();
                nodeB.stop();
            }
        }
    }
}