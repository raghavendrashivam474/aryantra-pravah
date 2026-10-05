package com.aryntra.pravah.peer;

import com.aryntra.pravah.connectivity.ConnectivityPath;
import com.aryntra.pravah.connectivity.EndpointAddress;
import com.aryntra.pravah.connectivity.PathId;
import com.aryntra.pravah.connectivity.PathSelectionPolicy;
import com.aryntra.pravah.connectivity.PeerConnectivityRegistry;
import com.aryntra.pravah.messaging.ApplicationMessage;
import com.aryntra.pravah.messaging.DefaultApplicationMessagingService;
import com.aryntra.pravah.messaging.MessageState;
import com.aryntra.pravah.peer.presence.PeerPresenceBridge;
import com.aryntra.pravah.peer.presence.PeerPresenceManager;
import com.aryntra.pravah.protocol.Message;
import com.aryntra.pravah.protocol.MessageType;
import com.aryntra.pravah.transport.CompositeTransport;
import com.aryntra.pravah.transport.Transport;
import com.aryntra.pravah.transport.TransportCapabilities;
import com.aryntra.pravah.transport.TransportListener;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A.D2.6 Forensic Reproduction Suite: Proves the exact causality for T1, T2, T3, and T4.
 */
class TcpDeliveryForensicInvestigationTest {

    private PeerId localPeer;
    private PeerId remotePeer;
    private PeerRegistry registry;
    private PeerConnectivityRegistry connectivityRegistry;
    private PeerPresenceManager presenceManager;
    private PeerPresenceBridge presenceBridge;
    private PathSelectionPolicy selectionPolicy;
    private MockTransport mockTransport;
    private CompositeTransport compositeTransport;
    private PeerRouter router;
    private PeerConnectionCoordinator coordinator;
    private DefaultApplicationMessagingService messagingService;

    static class MockTransport implements Transport {
        final List<String> sentDestinations = new ArrayList<>();
        final List<byte[]> sentPayloads = new ArrayList<>();
        AtomicBoolean failNextSend = new AtomicBoolean(false);

        @Override public String getName() { return "tcp"; }
        @Override public TransportCapabilities getCapabilities() { return TransportCapabilities.tcp(); }
        @Override public void start() {}
        @Override public void stop() {}
        @Override public boolean isRunning() { return true; }
        @Override public void setListener(TransportListener listener) {}

        @Override
        public void send(String destinationId, byte[] payload) {
            if (failNextSend.get()) {
                throw new RuntimeException("Cannot send payload: No active TCP connection to " + destinationId);
            }
            sentDestinations.add(destinationId);
            sentPayloads.add(payload);
        }
    }

    @BeforeEach
    void setUp() {
        localPeer = PeerId.of("local-node");
        remotePeer = PeerId.of("remote-node");
        registry = new PeerRegistry();
        connectivityRegistry = new PeerConnectivityRegistry();
        presenceManager = new PeerPresenceManager(2000L);
        presenceBridge = new PeerPresenceBridge(registry, presenceManager, connectivityRegistry);
        selectionPolicy = PathSelectionPolicy.preferSchemes("tcp", "bluetooth");
        mockTransport = new MockTransport();
        compositeTransport = new CompositeTransport(mockTransport);
        compositeTransport.start();
        coordinator = new PeerConnectionCoordinator(compositeTransport, registry, presenceBridge);
        router = new PeerRouter(registry, compositeTransport, connectivityRegistry, selectionPolicy);
        messagingService = new DefaultApplicationMessagingService(localPeer, router, coordinator);
    }

    @Test
    @DisplayName("T2 Proof: When path is CANDIDATE, Application send buffers in TransitionBuffer without transport write")
    void proveT2_CandidatePathBuffersAndReportsSentWithoutTransportWrite() {
        PathId pathId = PathId.of("path:tcp:candidate");
        ConnectivityPath candidatePath = ConnectivityPath.candidate(pathId, remotePeer, "tcp", EndpointAddress.tcp("10.177.67.157", 45737));
        connectivityRegistry.registerPath(remotePeer, candidatePath);

        ApplicationMessage appMsg = messagingService.sendText(remotePeer, "Hello Candidate");
        assertNotNull(appMsg);

        // 1. App messaging service records the message state as SENT upon buffer acceptance
        MessageState state = messagingService.getMessageState(appMsg.messageId());
        assertEquals(MessageState.BUFFERED, state, "B.R3: Application messaging service accurately reflects BUFFERED state upon transition buffer capture");

        // 2. Mock transport received ZERO writes
        assertEquals(0, mockTransport.sentDestinations.size(), "Mock transport received 0 writes");

        // 3. Message is captured in TransitionBuffer
        assertEquals(1, router.transitionBuffer().size(), "Message is captured in TransitionBuffer");
        assertEquals(1, router.transitionBuffer().totalBuffered(), "totalBuffered counter incremented");
    }

    @Test
    @DisplayName("T3 Proof: When activePaths is empty, resolveConnectionId returns stale legacy connectionId instead of null/NONE")
    void proveT3_ResolveConnectionIdReturnsStaleLegacyRouteWhenNoActivePaths() {
        registry.register(remotePeer, "10.177.67.157:45737");

        PathId pathId = PathId.of("path:tcp:candidate");
        ConnectivityPath candidatePath = ConnectivityPath.candidate(pathId, remotePeer, "tcp", EndpointAddress.tcp("10.177.67.157", 45737));
        connectivityRegistry.registerPath(remotePeer, candidatePath);

        String resolved = router.resolveConnectionId(remotePeer);
        assertEquals("10.177.67.157:45737", resolved, "resolveConnectionId returns stale legacy ID despite no active path");
    }

    @Test
    @DisplayName("T1/T4 Proof: Discarded candidate paths cause router to fall back to legacy registry and fail on stale socket")
    void proveT1_T4_FallbackToLegacyThrowsNoActiveConnection() {
        registry.register(remotePeer, "10.177.67.157:45737");
        mockTransport.failNextSend.set(true);

        Message msg = new Message(MessageType.MESSAGE, localPeer.value(), "msg-101", "test".getBytes());

        PeerRoutingException ex = assertThrows(PeerRoutingException.class, () -> router.send(remotePeer, msg));
        assertNotNull(ex.getMessage());
        assertTrue(ex.getMessage().contains("No active TCP connection to 10.177.67.157:45737"),
                "Exception message must contain the underlying transport error: " + ex.getMessage());
    }
}