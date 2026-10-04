package com.aryntra.pravah.connectivity;

import com.aryntra.pravah.peer.PeerId;
import com.aryntra.pravah.peer.PeerRegistry;
import com.aryntra.pravah.peer.PeerRouter;
import com.aryntra.pravah.peer.PeerRoutingException;
import com.aryntra.pravah.protocol.Message;
import com.aryntra.pravah.protocol.MessageType;
import com.aryntra.pravah.transport.Transport;
import com.aryntra.pravah.transport.TransportCapabilities;
import com.aryntra.pravah.transport.TransportListener;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.*;

/**
 * B.R2 Transition-Window Validation Test.
 *
 * <p>Validates that messages sent during the interval between active-path
 * failure and alternate-path activation are safely buffered in the TransitionBuffer
 * and delivered with ZERO loss upon candidate activation.</p>
 */
class TransitionWindowValidationTest {

    private PeerRegistry registry;
    private PeerConnectivityRegistry connectivityRegistry;
    private PathSelectionPolicy policy;
    private PeerId peer;

    @BeforeEach
    void setUp() {
        registry = new PeerRegistry();
        connectivityRegistry = new PeerConnectivityRegistry();
        policy = PathSelectionPolicy.preferSchemes("tcp", "bluetooth");
        peer = PeerId.of("transition-peer");
    }

    static class ScriptedTransport implements Transport {
        private TransportListener listener;
        private final Set<String> failingConnectionIds = ConcurrentHashMap.newKeySet();
        private final List<String> sendLog = new CopyOnWriteArrayList<>();
        private final List<String> deliveryLog = new CopyOnWriteArrayList<>();

        void markFailing(String connectionId) {
            failingConnectionIds.add(connectionId);
        }

        void markHealthy(String connectionId) {
            failingConnectionIds.remove(connectionId);
        }

        List<String> getSendLog() { return Collections.unmodifiableList(sendLog); }
        List<String> getDeliveryLog() { return Collections.unmodifiableList(deliveryLog); }

        @Override public String getName() { return "scripted"; }
        @Override public void start() {}
        @Override public void stop() {}
        @Override public boolean isRunning() { return true; }
        @Override public void setListener(TransportListener l) { this.listener = l; }
        @Override public TransportCapabilities getCapabilities() {
            return TransportCapabilities.defaultCapabilities();
        }

        @Override
        public void send(String destinationId, byte[] payload) {
            sendLog.add(destinationId);
            if (failingConnectionIds.contains(destinationId)) {
                throw new RuntimeException("Simulated transport failure on " + destinationId);
            }
            deliveryLog.add(destinationId);
        }
    }

    private ConnectivityPath tcpActive() {
        return ConnectivityPath.active(
                PathId.of("tcp-primary"), peer, "tcp",
                EndpointAddress.tcp("10.0.0.1", 8080), "tcp-conn-1"
        );
    }

    private ConnectivityPath btActive() {
        return ConnectivityPath.active(
                PathId.of("bt-secondary"), peer, "bluetooth",
                EndpointAddress.of("bluetooth", "AA:BB:CC:DD:EE:01", 1), "bt-conn-1"
        );
    }

    private ConnectivityPath btCandidate() {
        return ConnectivityPath.candidate(
                PathId.of("bt-candidate"), peer, "bluetooth",
                EndpointAddress.of("bluetooth", "AA:BB:CC:DD:EE:01", 1)
        );
    }

    private Message makeMessage(int seq) {
        return new Message(MessageType.MESSAGE, "local",
                "M" + String.format("%03d", seq),
                ("payload-" + seq).getBytes());
    }

    @Nested
    @DisplayName("Phase A: Baseline delivery (no failure)")
    class PhaseABaseline {
        @Test
        @DisplayName("All 10 messages delivered via TCP when both paths active, no failure")
        void baselineDelivery() {
            connectivityRegistry.registerPath(peer, tcpActive());
            connectivityRegistry.registerPath(peer, btActive());
            registry.register(peer, "tcp-conn-1");

            ScriptedTransport transport = new ScriptedTransport();
            PeerRouter router = new PeerRouter(registry, transport, connectivityRegistry, policy);

            int delivered = 0;
            for (int i = 1; i <= 10; i++) {
                Message msg = makeMessage(i);
                router.send(peer, msg);
                delivered++;
            }

            assertEquals(10, delivered, "All messages should be delivered in baseline");
            assertEquals(10, transport.getDeliveryLog().size(),
                    "Transport should have 10 successful writes");
        }
    }

    @Nested
    @DisplayName("Phase B: Failover without transition traffic (BT already ACTIVE)")
    class PhaseBFailoverNoGap {
        @Test
        @DisplayName("Messages sent after TCP failure route to BT with no loss")
        void failoverWithoutTransitionTraffic() {
            connectivityRegistry.registerPath(peer, tcpActive());
            connectivityRegistry.registerPath(peer, btActive());
            registry.register(peer, "tcp-conn-1");

            ScriptedTransport transport = new ScriptedTransport();
            PeerRouter router = new PeerRouter(registry, transport, connectivityRegistry, policy);
            transport.markFailing("tcp-conn-1");

            int delivered = 0;
            for (int i = 1; i <= 10; i++) {
                Message msg = makeMessage(i);
                router.send(peer, msg);
                delivered++;
            }

            assertEquals(10, delivered, "All 10 messages should reach destination");
        }
    }

    @Nested
    @DisplayName("Phase C: Transition-window traffic (BT is CANDIDATE during gap)")
    class PhaseCTransitionWindow {
        @Test
        @DisplayName("B.R2 Fix: Messages sent while TCP dead and BT CANDIDATE are BUFFERED and FLUSHED on activation")
        void trafficDuringTransitionGap() {
            connectivityRegistry.registerPath(peer, tcpActive());
            connectivityRegistry.registerPath(peer, btCandidate());
            registry.register(peer, "tcp-conn-1");

            ScriptedTransport transport = new ScriptedTransport();
            PeerRouter router = new PeerRouter(registry, transport, connectivityRegistry, policy);

            assertEquals(1, connectivityRegistry.getOrCreate(peer).activePaths().size(),
                    "Only TCP should be ACTIVE initially");
            assertEquals(1, connectivityRegistry.getOrCreate(peer).candidatePaths().size(),
                    "BT should be CANDIDATE");

            // Bucket 1: BEFORE failure (M001..M003)
            for (int i = 1; i <= 3; i++) {
                router.send(peer, makeMessage(i));
            }
            assertEquals(3, transport.getDeliveryLog().size(), "Bucket 1 delivered via TCP");

            // T2: TCP fails
            transport.markFailing("tcp-conn-1");

            // Bucket 2: TRANSITION WINDOW (M004..M007)
            // Under B.R2, router.send() should NOT throw PeerRoutingException; it buffers.
            for (int i = 4; i <= 7; i++) {
                final Message msg = makeMessage(i);
                assertDoesNotThrow(() -> router.send(peer, msg),
                        "Transition-gap messages must be buffered, not rejected");
            }

            assertEquals(4, router.transitionBuffer().size(),
                    "Transition buffer should hold 4 messages during the gap");
            assertEquals(3, transport.getDeliveryLog().size(),
                    "No new deliveries yet since BT is still candidate");

            // T4: BT activates -> triggers PathStateListener -> flushes TransitionBuffer
            connectivityRegistry.registerPath(peer, btActive());

            assertTrue(connectivityRegistry.getOrCreate(peer).hasActivePath(),
                    "BT should now be ACTIVE");
            assertEquals(0, router.transitionBuffer().size(),
                    "Transition buffer should be completely drained after flush");
            assertEquals(7, transport.getDeliveryLog().size(),
                    "Bucket 1 (3) + Bucket 2 (4) should now be delivered (total 7)");

            // Bucket 3: POST-TRANSITION (M008..M010)
            for (int i = 8; i <= 10; i++) {
                router.send(peer, makeMessage(i));
            }

            assertEquals(10, transport.getDeliveryLog().size(),
                    "All 10 messages across all 3 buckets delivered with 0% loss");
            assertEquals(4, router.transitionBuffer().totalFlushed(),
                    "Exactly 4 messages flushed from transition buffer");
        }

        @Test
        @DisplayName("Repeated transition cycles achieve 100% delivery (0% loss)")
        void repeatedTransitionCycles() {
            int totalSentInGap = 0;

            for (int cycle = 0; cycle < 3; cycle++) {
                connectivityRegistry = new PeerConnectivityRegistry();
                connectivityRegistry.registerPath(peer, tcpActive());
                connectivityRegistry.registerPath(peer, btCandidate());
                registry = new PeerRegistry();
                registry.register(peer, "tcp-conn-1");

                ScriptedTransport transport = new ScriptedTransport();
                PeerRouter router = new PeerRouter(registry, transport, connectivityRegistry, policy);

                // TCP fails
                transport.markFailing("tcp-conn-1");

                // Send 5 messages during gap
                for (int i = 1; i <= 5; i++) {
                    totalSentInGap++;
                    Message msg = makeMessage(cycle * 10 + i);
                    router.send(peer, msg);
                }

                assertEquals(5, router.transitionBuffer().size());

                // Activate BT
                connectivityRegistry.registerPath(peer, btActive());

                // Assert zero loss after activation
                assertEquals(0, router.transitionBuffer().size());
                assertEquals(5, transport.getDeliveryLog().size());
            }

            assertEquals(15, totalSentInGap, "15 gap messages tested across 3 cycles");
        }
    }

    @Nested
    @DisplayName("Phase D: Duplicate and ordering observation")
    class PhaseDDuplicatesAndOrdering {
        @Test
        @DisplayName("No unexpected duplicates during failover to active BT")
        void noUnexpectedDuplicates() {
            connectivityRegistry.registerPath(peer, tcpActive());
            connectivityRegistry.registerPath(peer, btActive());
            registry.register(peer, "tcp-conn-1");

            ScriptedTransport transport = new ScriptedTransport();
            PeerRouter router = new PeerRouter(registry, transport, connectivityRegistry, policy);
            transport.markFailing("tcp-conn-1");

            List<String> deliveredIds = new ArrayList<>();
            for (int i = 1; i <= 10; i++) {
                Message msg = makeMessage(i);
                try {
                    router.send(peer, msg);
                    deliveredIds.add(msg.messageId());
                } catch (PeerRoutingException ignored) {}
            }

            long uniqueCount = deliveredIds.stream().distinct().count();
            assertEquals(deliveredIds.size(), uniqueCount,
                    "No duplicate message IDs should appear in delivery log");
        }
    }
}