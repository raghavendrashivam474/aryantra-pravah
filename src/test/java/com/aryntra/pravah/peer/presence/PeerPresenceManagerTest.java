package com.aryntra.pravah.peer.presence;

import com.aryntra.pravah.peer.PeerId;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("PeerPresenceManager Lifecycle & State Specification")
class PeerPresenceManagerTest {

    @Nested
    @DisplayName("Validation and Lifecycle")
    class ValidationAndLifecycle {

        @Test
        @DisplayName("Should reject invalid TTL constructor values")
        void shouldRejectInvalidTtl() {
            assertThrows(IllegalArgumentException.class, () -> new PeerPresenceManager(0));
            assertThrows(IllegalArgumentException.class, () -> new PeerPresenceManager(-100));
        }

        @Test
        @DisplayName("Should cleanly start and stop")
        void testStartStop() {
            PeerPresenceManager manager = new PeerPresenceManager(1000);
            manager.start();
            manager.stop();
        }
    }

    @Nested
    @DisplayName("Presence State Transitions")
    class StateTransitionTests {

        @Test
        @DisplayName("Should return UNKNOWN state for unknown peer queries")
        void testDefaultState() {
            PeerPresenceManager manager = new PeerPresenceManager(1000);
            PeerId peerId = PeerId.of("unknown-peer");

            PeerPresence presence = manager.getPresence(peerId);
            assertEquals(PeerPresenceState.UNKNOWN, presence.state());
            assertEquals(Instant.EPOCH, presence.lastSeen());
        }

        @Test
        @DisplayName("UNKNOWN -> AVAILABLE on discovery")
        void testDiscoveryTransition() {
            PeerPresenceManager manager = new PeerPresenceManager(1000);
            PeerId peerId = PeerId.of("peer-1");

            manager.reportDiscovered(peerId, "192.168.1.100", 8080);

            PeerPresence presence = manager.getPresence(peerId);
            assertEquals(PeerPresenceState.AVAILABLE, presence.state());
            assertEquals("192.168.1.100", presence.hostAddress());
            assertEquals(8080, presence.port());
            assertTrue(presence.lastSeen().isAfter(Instant.EPOCH));
        }

        @Test
        @DisplayName("UNKNOWN -> CONNECTED on transport connection")
        void testConnectionTransition() {
            PeerPresenceManager manager = new PeerPresenceManager(1000);
            PeerId peerId = PeerId.of("peer-1");

            manager.reportConnected(peerId);

            PeerPresence presence = manager.getPresence(peerId);
            assertEquals(PeerPresenceState.CONNECTED, presence.state());
        }

        @Test
        @DisplayName("CONNECTED -> AVAILABLE on drop if within TTL")
        void testDisconnectWithinTtl() {
            PeerPresenceManager manager = new PeerPresenceManager(10000); // 10s TTL
            PeerId peerId = PeerId.of("peer-1");

            manager.reportDiscovered(peerId, "127.0.0.1", 9000);
            manager.reportConnected(peerId);
            manager.reportDisconnected(peerId);

            PeerPresence presence = manager.getPresence(peerId);
            assertEquals(PeerPresenceState.AVAILABLE, presence.state());
        }
    }

    @Nested
    @DisplayName("Listener Notifications")
    class ListenerTests {

        @Test
        @DisplayName("Should notify listeners of legitimate changes and ignore duplicate updates")
        void testListenerTransitions() {
            PeerPresenceManager manager = new PeerPresenceManager(1000);
            PeerId peerId = PeerId.of("peer-1");

            CopyOnWriteArrayList<String> log = new CopyOnWriteArrayList<>();
            manager.registerListener((id, oldP, newP) -> {
                log.add(oldP.state() + "->" + newP.state());
            });

            // 1. UNKNOWN -> AVAILABLE (Should notify)
            manager.reportDiscovered(peerId, "127.0.0.1", 9000);

            // 2. AVAILABLE -> AVAILABLE (Duplicate, should NOT notify)
            manager.reportDiscovered(peerId, "127.0.0.1", 9000);

            // 3. AVAILABLE -> CONNECTED (Should notify)
            manager.reportConnected(peerId);

            assertEquals(2, log.size());
            assertEquals("UNKNOWN->AVAILABLE", log.get(0));
            assertEquals("AVAILABLE->CONNECTED", log.get(1));
        }
    }

    @Nested
    @DisplayName("Presence Expiry (TTL)")
    class ExpiryTests {

        @Test
        @DisplayName("Should evict quiet AVAILABLE peers while preserving CONNECTED peers")
        void testExpiryEviction() throws Exception {
            PeerPresenceManager manager = new PeerPresenceManager(150); // Very short TTL
            PeerId silentPeer = PeerId.of("silent-peer");
            PeerId connectedPeer = PeerId.of("connected-peer");

            CountDownLatch latch = new CountDownLatch(1);
            manager.registerListener((id, oldP, newP) -> {
                if (id.equals(silentPeer) && newP.state() == PeerPresenceState.UNAVAILABLE) {
                    latch.countDown();
                }
            });

            manager.start();
            try {
                // Discover both
                manager.reportDiscovered(silentPeer, "127.0.0.1", 8080);
                manager.reportDiscovered(connectedPeer, "127.0.0.1", 9090);

                // Elevate one to CONNECTED
                manager.reportConnected(connectedPeer);

                // Wait for the silent peer to transition to UNAVAILABLE
                boolean triggered = latch.await(2, TimeUnit.SECONDS);
                assertTrue(triggered, "Silent peer was not expired by manager check");

                assertEquals(PeerPresenceState.UNAVAILABLE, manager.getPresence(silentPeer).state());
                assertEquals(PeerPresenceState.CONNECTED, manager.getPresence(connectedPeer).state(),
                        "Connected peer should never expire even if past the quiet threshold.");

            } finally {
                manager.stop();
            }
        }
    }
}