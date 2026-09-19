package com.aryntra.pravah.transport;

import com.aryntra.pravah.transport.tcp.TcpTransport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("S6.3 - Transport Capability Model Tests")
class TransportCapabilitiesTest {

    @Nested
    @DisplayName("Pre-defined Capabilities Presets")
    class PresetsTests {

        @Test
        @DisplayName("TCP capabilities match exact TCP networking guarantees")
        void testTcpCapabilities() {
            TransportCapabilities tcp = TransportCapabilities.tcp();
            assertTrue(tcp.reliable(), "TCP must be reliable");
            assertTrue(tcp.connectionOriented(), "TCP must be connection-oriented");
            assertTrue(tcp.unicast(), "TCP must be unicast");
            assertFalse(tcp.supportsMultiplexing(), "Raw TCP does not support native channel multiplexing");
        }

        @Test
        @DisplayName("LAN Broadcast capabilities match UDP discovery model")
        void testLanBroadcastCapabilities() {
            TransportCapabilities udp = TransportCapabilities.lanBroadcast();
            assertFalse(udp.reliable(), "LAN broadcast UDP is lossy/unreliable");
            assertFalse(udp.connectionOriented(), "LAN broadcast UDP is connectionless");
            assertFalse(udp.unicast(), "LAN broadcast is broadcast, not unicast");
            assertFalse(udp.supportsMultiplexing(), "LAN broadcast does not support multiplexing");
        }

        @Test
        @DisplayName("Default capabilities fallback safely to conservative TCP preset")
        void testDefaultCapabilities() {
            assertEquals(TransportCapabilities.tcp(), TransportCapabilities.defaultCapabilities());
        }
    }

    @Nested
    @DisplayName("Builder and Custom Capabilities")
    class BuilderTests {

        @Test
        @DisplayName("Builder correctly constructs custom capability profiles (e.g. QUIC or BLE)")
        void testCustomCapabilities() {
            TransportCapabilities quic = TransportCapabilities.builder()
                    .reliable(true)
                    .connectionOriented(true)
                    .unicast(true)
                    .supportsMultiplexing(true)
                    .build();

            assertTrue(quic.reliable());
            assertTrue(quic.connectionOriented());
            assertTrue(quic.unicast());
            assertTrue(quic.supportsMultiplexing());

            TransportCapabilities ble = TransportCapabilities.builder()
                    .reliable(false)
                    .connectionOriented(false)
                    .unicast(true)
                    .supportsMultiplexing(false)
                    .build();

            assertFalse(ble.reliable());
            assertFalse(ble.connectionOriented());
            assertTrue(ble.unicast());
            assertFalse(ble.supportsMultiplexing());
        }

        @Test
        @DisplayName("Value equality and hashCode")
        void testEqualityAndHashCode() {
            TransportCapabilities c1 = TransportCapabilities.builder()
                    .reliable(true).connectionOriented(true).unicast(true).supportsMultiplexing(false).build();
            TransportCapabilities c2 = TransportCapabilities.tcp();

            assertEquals(c1, c2);
            assertEquals(c1.hashCode(), c2.hashCode());
        }
    }

    @Nested
    @DisplayName("Transport Contract & TcpTransport Integration")
    class TransportIntegrationTests {

        @Test
        @DisplayName("TcpTransport instance returns TCP capabilities truthfully")
        void testTcpTransportReturnsTcpCapabilities() {
            TcpTransport transport = new TcpTransport("127.0.0.1", 0);
            TransportCapabilities caps = transport.getCapabilities();

            assertNotNull(caps);
            assertEquals(TransportCapabilities.tcp(), caps);
            assertTrue(caps.reliable());
            assertTrue(caps.connectionOriented());
            assertTrue(caps.unicast());
            assertFalse(caps.supportsMultiplexing());
        }

        @Test
        @DisplayName("Generic Transport implementation defaults to defaultCapabilities()")
        void testDefaultTransportCapabilities() {
            Transport customTransport = new Transport() {
                @Override public String getName() { return "custom"; }
                @Override public void start() {}
                @Override public void stop() {}
                @Override public boolean isRunning() { return false; }
                @Override public void send(String destinationId, byte[] payload) {}
                @Override public void setListener(TransportListener listener) {}
            };

            assertNotNull(customTransport.getCapabilities());
            assertEquals(TransportCapabilities.defaultCapabilities(), customTransport.getCapabilities());
        }
    }
}
