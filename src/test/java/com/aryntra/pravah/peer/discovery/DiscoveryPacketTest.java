package com.aryntra.pravah.peer.discovery;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("DiscoveryPacket Wire Format Specification")
class DiscoveryPacketTest {

    @Nested
    @DisplayName("Constructor Validation")
    class ConstructorValidation {

        @Test
        @DisplayName("Should create valid DiscoveryPacket")
        void shouldCreateValidPacket() {
            DiscoveryPacket packet = new DiscoveryPacket("peer-alpha", 8080);
            assertEquals("peer-alpha", packet.peerId());
            assertEquals(8080, packet.tcpPort());
        }

        @Test
        @DisplayName("Should reject null peerId")
        void shouldRejectNullPeerId() {
            assertThrows(IllegalArgumentException.class, () -> new DiscoveryPacket(null, 8080));
        }

        @ParameterizedTest
        @ValueSource(strings = {"", "   ", "\t\n"})
        @DisplayName("Should reject blank peerId")
        void shouldRejectBlankPeerId(String invalidId) {
            assertThrows(IllegalArgumentException.class, () -> new DiscoveryPacket(invalidId, 8080));
        }

        @ParameterizedTest
        @ValueSource(ints = {-1, 0, 65536, 100000})
        @DisplayName("Should reject out-of-range port numbers")
        void shouldRejectInvalidPort(int invalidPort) {
            assertThrows(IllegalArgumentException.class, () -> new DiscoveryPacket("peer-alpha", invalidPort));
        }

        @ParameterizedTest
        @ValueSource(ints = {1, 80, 443, 8080, 65535})
        @DisplayName("Should accept edge-case valid port numbers")
        void shouldAcceptValidEdgePorts(int validPort) {
            DiscoveryPacket packet = new DiscoveryPacket("peer-alpha", validPort);
            assertEquals(validPort, packet.tcpPort());
        }
    }

    @Nested
    @DisplayName("Serialization & Deserialization")
    class SerializationTests {

        @Test
        @DisplayName("Should round-trip encode and decode discovery packet")
        void shouldRoundTrip() {
            DiscoveryPacket original = new DiscoveryPacket("node-771", 9001);
            byte[] bytes = original.serialize();

            assertNotNull(bytes);
            assertTrue(bytes.length >= DiscoveryPacket.MIN_PACKET_SIZE);

            DiscoveryPacket restored = DiscoveryPacket.deserialize(bytes);
            assertEquals(original.peerId(), restored.peerId());
            assertEquals(original.tcpPort(), restored.tcpPort());
        }

        @Test
        @DisplayName("Should round-trip with UUID peer ID")
        void shouldRoundTripWithUuid() {
            String uuid = "550e8400-e29b-41d4-a716-446655440000";
            DiscoveryPacket original = new DiscoveryPacket(uuid, 63314);
            byte[] bytes = original.serialize();

            DiscoveryPacket restored = DiscoveryPacket.deserialize(bytes);
            assertEquals(uuid, restored.peerId());
            assertEquals(63314, restored.tcpPort());
        }

        @Test
        @DisplayName("Should round-trip with UTF-8 multibyte characters")
        void shouldRoundTripWithUtf8() {
            DiscoveryPacket original = new DiscoveryPacket("node-प्रवाह-🌟", 5000);
            byte[] bytes = original.serialize();

            DiscoveryPacket restored = DiscoveryPacket.deserialize(bytes);
            assertEquals(original.peerId(), restored.peerId());
            assertEquals(5000, restored.tcpPort());
        }
    }

    @Nested
    @DisplayName("Wire Deserialization Validation & Rejection")
    class DeserializationRejections {

        @Test
        @DisplayName("Should reject null byte array")
        void shouldRejectNull() {
            assertThrows(IllegalArgumentException.class, () -> DiscoveryPacket.deserialize(null));
        }

        @Test
        @DisplayName("Should reject packet shorter than minimum header + payload (10 bytes)")
        void shouldRejectTooShort() {
            byte[] shortData = new byte[]{ 'P', 'R', 'A', 'H', 0x01, 0, 1, 'x', 0 }; // 9 bytes
            assertThrows(IllegalArgumentException.class, () -> DiscoveryPacket.deserialize(shortData));
        }

        @Test
        @DisplayName("Should reject packet with invalid magic bytes (random UDP noise)")
        void shouldRejectInvalidMagic() {
            byte[] noise = "SOME_RANDOM_UDP_TRAFFIC".getBytes(StandardCharsets.UTF_8);
            IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                    () -> DiscoveryPacket.deserialize(noise));
            assertTrue(ex.getMessage().contains("Invalid magic marker"));
        }

        @Test
        @DisplayName("Should reject packet with unsupported version")
        void shouldRejectUnsupportedVersion() {
            byte[] valid = new DiscoveryPacket("peer-1", 8080).serialize();
            valid[4] = 0x02; // Change version from 0x01 to 0x02

            IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                    () -> DiscoveryPacket.deserialize(valid));
            assertTrue(ex.getMessage().contains("Unsupported discovery version"));
        }

        @Test
        @DisplayName("Should reject zero-length peer ID")
        void shouldRejectZeroLengthPeerId() {
            ByteBuffer buf = ByteBuffer.allocate(10);
            buf.put(DiscoveryPacket.MAGIC);
            buf.put(DiscoveryPacket.VERSION);
            buf.putShort((short) 0); // idLen = 0
            buf.putShort((short) 8080);

            IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                    () -> DiscoveryPacket.deserialize(buf.array()));
            assertTrue(ex.getMessage().contains("Peer ID length cannot be zero"));
        }

        @Test
        @DisplayName("Should reject truncated payload where peer ID is incomplete")
        void shouldRejectTruncatedPeerId() {
            ByteBuffer buf = ByteBuffer.allocate(12);
            buf.put(DiscoveryPacket.MAGIC);
            buf.put(DiscoveryPacket.VERSION);
            buf.putShort((short) 10); // claims 10 bytes of peerId
            buf.put(new byte[]{ 'a', 'b', 'c' }); // only 3 bytes provided
            buf.putShort((short) 8080);

            assertThrows(IllegalArgumentException.class,
                    () -> DiscoveryPacket.deserialize(buf.array()));
        }
    }

    @Nested
    @DisplayName("Object Identity & Representation")
    class ObjectRepresentation {

        @Test
        @DisplayName("toString should contain peerId and port")
        void testToString() {
            DiscoveryPacket packet = new DiscoveryPacket("peer-test", 9999);
            String str = packet.toString();
            assertTrue(str.contains("peer-test"));
            assertTrue(str.contains("9999"));
        }
    }
}