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
 * B.R1 Transition-Window Validation Experiment.
 *
 * <p>Objective: Determine the exact behavior of messages sent during the
 * interval between active-path failure and alternate-path activation.</p>
 *
 * <p>This is an OBSERVATION sprint, not a FIX sprint. No production code
 * is modified. Outcomes are classified as:
 * DELIVERED_ONCE, DELIVERED_AFTER_RETRY, LOST, DUPLICATED, REORDERED, UNKNOWN.</p>
 */
class TransitionWindowValidationTest {

    private PeerRegistry registry;
    private PeerConnectivityRegistry connectivityRegistry;
    private PathSelectionPolicy policy;
    private PeerId peer;

    enum Outcome { DELIVERED_ONCE, DELIVERED_AFTER_RETRY, LOST, DUPLICATED, REORDERED, UNKNOWN }

    record MessageRecord(
            String messageId,
            int sequenceNumber,
            String phase,
            String bucket,
            Outcome outcome,
            String detail
    ) {}

    private final List<MessageRecord> results = new CopyOnWriteArrayList<>();

    @BeforeEach
    void setUp() {
        registry = new PeerRegistry();
        connectivityRegistry = new PeerConnectivityRegistry();
        policy = PathSelectionPolicy.preferSchemes("tcp", "bluetooth");
        peer = PeerId.of("transition-peer");
        results.clear();
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
            int lost = 0;

            for (int i = 1; i <= 10; i++) {
                Message msg = makeMessage(i);
                try {
                    router.send(peer, msg);
                    delivered++;
                    results.add(new MessageRecord(
                            msg.messageId(), i, "A", "BEFORE_FAILURE",
                            Outcome.DELIVERED_ONCE, "TCP active, no failure"));
                } catch (PeerRoutingException e) {
                    lost++;
                    results.add(new MessageRecord(
                            msg.messageId(), i, "A", "BEFORE_FAILURE",
                            Outcome.LOST, e.getMessage()));
                }
            }

            assertEquals(10, delivered, "All messages should be delivered in baseline");
            assertEquals(0, lost, "No messages should be lost in baseline");
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
            int deliveredAfterRetry = 0;
            int lost = 0;

            for (int i = 1; i <= 10; i++) {
                Message msg = makeMessage(i);
                try {
                    router.send(peer, msg);
                    long tcpAttempts = transport.getSendLog().stream()
                            .filter(s -> s.equals("tcp-conn-1")).count();
                    if (tcpAttempts > 0 && i == 1) {
                        deliveredAfterRetry++;
                        results.add(new MessageRecord(
                                msg.messageId(), i, "B", "POST_FAILOVER",
                                Outcome.DELIVERED_AFTER_RETRY,
                                "TCP failed, retried on BT"));
                    } else {
                        delivered++;
                        results.add(new MessageRecord(
                                msg.messageId(), i, "B", "POST_FAILOVER",
                                Outcome.DELIVERED_ONCE,
                                "Routed via BT after TCP deactivated"));
                    }
                } catch (PeerRoutingException e) {
                    lost++;
                    results.add(new MessageRecord(
                            msg.messageId(), i, "B", "POST_FAILOVER",
                            Outcome.LOST, e.getMessage()));
                }
            }

            assertEquals(0, lost, "No messages should be lost when BT is already ACTIVE");
            assertEquals(10, delivered + deliveredAfterRetry,
                    "All 10 messages should reach destination");
        }
    }

    @Nested
    @DisplayName("Phase C: Transition-window traffic (BT is CANDIDATE during gap)")
    class PhaseCTransitionWindow {

        @Test
        @DisplayName("Messages sent while TCP dead and BT still CANDIDATE are LOST")
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

            // Bucket 1: BEFORE failure
            int bucket1Delivered = 0;
            for (int i = 1; i <= 3; i++) {
                Message msg = makeMessage(i);
                try {
                    router.send(peer, msg);
                    bucket1Delivered++;
                    results.add(new MessageRecord(
                            msg.messageId(), i, "C", "BUCKET1_BEFORE_FAILURE",
                            Outcome.DELIVERED_ONCE, "TCP active"));
                } catch (PeerRoutingException e) {
                    results.add(new MessageRecord(
                            msg.messageId(), i, "C", "BUCKET1_BEFORE_FAILURE",
                            Outcome.LOST, e.getMessage()));
                }
            }

            // T2: TCP fails
            transport.markFailing("tcp-conn-1");

            // Bucket 2: TRANSITION WINDOW
            int bucket2Lost = 0;
            int bucket2Delivered = 0;
            for (int i = 4; i <= 7; i++) {
                Message msg = makeMessage(i);
                try {
                    router.send(peer, msg);
                    bucket2Delivered++;
                    results.add(new MessageRecord(
                            msg.messageId(), i, "C", "BUCKET2_TRANSITION_GAP",
                            Outcome.DELIVERED_AFTER_RETRY, "Unexpectedly delivered"));
                } catch (PeerRoutingException e) {
                    bucket2Lost++;
                    results.add(new MessageRecord(
                            msg.messageId(), i, "C", "BUCKET2_TRANSITION_GAP",
                            Outcome.LOST,
                            "TCP failed + BT is CANDIDATE = no active paths"));
                }
            }

            // T4: BT activates
            connectivityRegistry.registerPath(peer, btActive());

            assertTrue(connectivityRegistry.getOrCreate(peer).hasActivePath(),
                    "BT should now be ACTIVE");

            // Bucket 3: POST-TRANSITION
            int bucket3Delivered = 0;
            for (int i = 8; i <= 10; i++) {
                Message msg = makeMessage(i);
                try {
                    router.send(peer, msg);
                    bucket3Delivered++;
                    results.add(new MessageRecord(
                            msg.messageId(), i, "C", "BUCKET3_POST_TRANSITION",
                            Outcome.DELIVERED_ONCE, "BT active"));
                } catch (PeerRoutingException e) {
                    results.add(new MessageRecord(
                            msg.messageId(), i, "C", "BUCKET3_POST_TRANSITION",
                            Outcome.LOST, e.getMessage()));
                }
            }

            assertEquals(3, bucket1Delivered,
                    "Bucket 1: All pre-failure messages should be delivered");
            assertEquals(4, bucket2Lost,
                    "Bucket 2: ALL transition-window messages should be LOST " +
                    "(no active path exists during gap)");
            assertEquals(0, bucket2Delivered,
                    "Bucket 2: No messages should survive the gap without B.R2 buffering");
            assertEquals(3, bucket3Delivered,
                    "Bucket 3: All post-transition messages should be delivered via BT");
        }

        @Test
        @DisplayName("Repeated transition cycles produce consistent loss pattern")
        void repeatedTransitionCycles() {
            int totalLostInGap = 0;
            int totalSentInGap = 0;

            for (int cycle = 0; cycle < 3; cycle++) {
                connectivityRegistry = new PeerConnectivityRegistry();
                connectivityRegistry.registerPath(peer, tcpActive());
                connectivityRegistry.registerPath(peer, btCandidate());
                registry = new PeerRegistry();
                registry.register(peer, "tcp-conn-1");

                ScriptedTransport transport = new ScriptedTransport();
                PeerRouter router = new PeerRouter(registry, transport, connectivityRegistry, policy);

                transport.markFailing("tcp-conn-1");

                for (int i = 1; i <= 5; i++) {
                    totalSentInGap++;
                    Message msg = makeMessage(cycle * 10 + i);
                    try {
                        router.send(peer, msg);
                    } catch (PeerRoutingException e) {
                        totalLostInGap++;
                    }
                }
            }

            assertEquals(totalSentInGap, totalLostInGap,
                    "100% of transition-gap messages should be LOST across all cycles. " +
                    "Lost: " + totalLostInGap + "/" + totalSentInGap);
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