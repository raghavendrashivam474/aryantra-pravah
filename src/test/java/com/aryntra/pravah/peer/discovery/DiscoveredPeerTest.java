package com.aryntra.pravah.peer.discovery;

import com.aryntra.pravah.peer.PeerId;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("DiscoveredPeer Domain Model Specification")
class DiscoveredPeerTest {

    @Nested
    @DisplayName("Validation")
    class ValidationTests {

        @Test
        @DisplayName("Should create valid DiscoveredPeer record")
        void shouldCreateValid() {
            PeerId id = PeerId.of("node-alpha");
            DiscoveredPeer peer = new DiscoveredPeer(id, "192.168.1.50", 9000);

            assertEquals(id, peer.peerId());
            assertEquals("192.168.1.50", peer.hostAddress());
            assertEquals(9000, peer.port());
        }

        @Test
        @DisplayName("Should create valid DiscoveredPeer via convenience factory")
        void shouldCreateViaFactory() {
            DiscoveredPeer peer = DiscoveredPeer.of("node-beta", "127.0.0.1", 63314);

            assertEquals(PeerId.of("node-beta"), peer.peerId());
            assertEquals("127.0.0.1", peer.hostAddress());
            assertEquals(63314, peer.port());
        }

        @Test
        @DisplayName("Should reject null PeerId")
        void shouldRejectNullPeerId() {
            assertThrows(NullPointerException.class,
                    () -> new DiscoveredPeer(null, "127.0.0.1", 8080));
        }

        @Test
        @DisplayName("Should reject null hostAddress")
        void shouldRejectNullHostAddress() {
            assertThrows(NullPointerException.class,
                    () -> new DiscoveredPeer(PeerId.of("node-1"), null, 8080));
        }

        @ParameterizedTest
        @ValueSource(strings = {"", "   ", "\t\n"})
        @DisplayName("Should reject blank hostAddress")
        void shouldRejectBlankHostAddress(String invalidHost) {
            assertThrows(IllegalArgumentException.class,
                    () -> new DiscoveredPeer(PeerId.of("node-1"), invalidHost, 8080));
        }

        @ParameterizedTest
        @ValueSource(ints = {-1, 0, 65536, 99999})
        @DisplayName("Should reject out-of-range port numbers")
        void shouldRejectInvalidPort(int invalidPort) {
            assertThrows(IllegalArgumentException.class,
                    () -> new DiscoveredPeer(PeerId.of("node-1"), "127.0.0.1", invalidPort));
        }
    }

    @Nested
    @DisplayName("Equality and Immutability")
    class EqualityTests {

        @Test
        @DisplayName("Records with identical values must be equal")
        void shouldBeEqual() {
            DiscoveredPeer p1 = DiscoveredPeer.of("peer-1", "10.0.0.1", 5000);
            DiscoveredPeer p2 = DiscoveredPeer.of("peer-1", "10.0.0.1", 5000);

            assertEquals(p1, p2);
            assertEquals(p1.hashCode(), p2.hashCode());
        }

        @Test
        @DisplayName("Records with different values must not be equal")
        void shouldNotBeEqual() {
            DiscoveredPeer p1 = DiscoveredPeer.of("peer-1", "10.0.0.1", 5000);
            DiscoveredPeer p2 = DiscoveredPeer.of("peer-2", "10.0.0.1", 5000);
            DiscoveredPeer p3 = DiscoveredPeer.of("peer-1", "10.0.0.2", 5000);
            DiscoveredPeer p4 = DiscoveredPeer.of("peer-1", "10.0.0.1", 5001);

            assertNotEquals(p1, p2);
            assertNotEquals(p1, p3);
            assertNotEquals(p1, p4);
        }
    }
}