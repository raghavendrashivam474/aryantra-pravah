package com.aryntra.pravah.transport;

import com.aryntra.pravah.connectivity.ConnectivityPath;
import com.aryntra.pravah.connectivity.EndpointAddress;
import com.aryntra.pravah.connectivity.PathId;
import com.aryntra.pravah.connectivity.PeerConnectivityRegistry;
import com.aryntra.pravah.peer.PeerId;
import com.aryntra.pravah.peer.PeerRegistry;
import com.aryntra.pravah.peer.PeerRouter;
import com.aryntra.pravah.protocol.*;
import com.aryntra.pravah.transport.bluetooth.BluetoothRfcommTransport;
import com.aryntra.pravah.transport.tcp.TcpTransport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("S8.4 - Hybrid Messaging Integration via CompositeTransport")
@Timeout(value = 15, unit = TimeUnit.SECONDS)
class HybridMessagingIntegrationTest {

    private static final String MAC_A = "AA:BB:CC:DD:EE:01";
    private static final String MAC_B = "AA:BB:CC:DD:EE:02";

    private TcpTransport tcpServer;
    private TcpTransport tcpClient;
    private BluetoothRfcommTransport btTransportA;
    private BluetoothRfcommTransport btTransportB;

    private CompositeTransport compositeTransportNodeA;
    private CompositeTransport compositeTransportNodeB;

    @BeforeEach
    void setUp() {
        // TCP Transports
        tcpServer = new TcpTransport("127.0.0.1", 0);
        tcpClient = new TcpTransport("127.0.0.1", 0);

        // Bluetooth RFCOMM Transports
        btTransportA = new BluetoothRfcommTransport(MAC_A, 1);
        btTransportB = new BluetoothRfcommTransport(MAC_B, 1);

        // Composite Transports for Node A and Node B
        compositeTransportNodeA = new CompositeTransport(tcpClient, btTransportA);
        compositeTransportNodeB = new CompositeTransport(tcpServer, btTransportB);

        compositeTransportNodeA.start();
        compositeTransportNodeB.start();
    }

    @AfterEach
    void tearDown() {
        if (compositeTransportNodeA != null) compositeTransportNodeA.stop();
        if (compositeTransportNodeB != null) compositeTransportNodeB.stop();
    }

    @Test
    @DisplayName("S8.4.1: Seamlessly route PeerRouter application messages over TCP via CompositeTransport")
    void testPeerRouterMessageOverTcpViaCompositeTransport() throws Exception {
        PeerId nodeAId = PeerId.of("peer-node-a");
        PeerId nodeBId = PeerId.of("peer-node-b");

        PeerRegistry registryA = new PeerRegistry();
        PeerConnectivityRegistry connRegistryA = new PeerConnectivityRegistry();
        PeerRouter routerA = new PeerRouter(registryA, compositeTransportNodeA, connRegistryA);

        TestNodeReceiver receiverB = new TestNodeReceiver(1);
        compositeTransportNodeB.setListener(receiverB);

        // 1. Establish TCP connection
        int tcpPort = tcpServer.getBoundPort();
        tcpClient.connect("127.0.0.1", tcpPort);

        // Allow connection initialization to register in CompositeTransport
        Thread.sleep(100);

        // 2. Discover / Register active TCP path in Node A's registry
        String tcpConnId = "127.0.0.1:" + tcpPort;
        EndpointAddress tcpEndpoint = EndpointAddress.tcp("127.0.0.1", tcpPort);
        ConnectivityPath tcpPath = ConnectivityPath.active(
                PathId.of("path-tcp-b"),
                nodeBId,
                "tcp",
                tcpEndpoint,
                tcpConnId
        );
        connRegistryA.registerPath(nodeBId, tcpPath);

        // 3. Send application message from Node A to Node B via PeerRouter
        String payloadText = "Hello Peer B over TCP via CompositeTransport!";
        routerA.send(nodeAId, nodeBId, "msg-001", payloadText.getBytes(StandardCharsets.UTF_8));

        // 4. Verify message received and decoded on Node B
        assertTrue(receiverB.messageLatch.await(5, TimeUnit.SECONDS), "Message did not arrive over TCP in time");
        assertEquals(1, receiverB.receivedMessages.size());

        Message received = receiverB.receivedMessages.get(0);
        assertEquals(MessageType.MESSAGE, received.type());
        assertEquals(nodeAId.value(), received.senderId());
        assertEquals("msg-001", received.messageId());
        assertEquals(payloadText, new String(received.payload(), StandardCharsets.UTF_8));
    }

    @Test
    @DisplayName("S8.4.2: Seamlessly route PeerRouter application messages over Bluetooth RFCOMM via CompositeTransport")
    void testPeerRouterMessageOverBluetoothViaCompositeTransport() throws Exception {
        PeerId nodeAId = PeerId.of("peer-node-a");
        PeerId nodeBId = PeerId.of("peer-node-b");

        PeerRegistry registryA = new PeerRegistry();
        PeerConnectivityRegistry connRegistryA = new PeerConnectivityRegistry();
        PeerRouter routerA = new PeerRouter(registryA, compositeTransportNodeA, connRegistryA);

        TestNodeReceiver receiverB = new TestNodeReceiver(1);
        compositeTransportNodeB.setListener(receiverB);

        // 1. Establish Bluetooth connection from A to B
        btTransportA.connect(MAC_B);
        String btConnId = "bt:" + MAC_B;

        // 2. Discover / Register active Bluetooth path in Node A's registry
        EndpointAddress btEndpoint = EndpointAddress.of("bluetooth", MAC_B, 1);
        ConnectivityPath btPath = ConnectivityPath.active(
                PathId.of("path-bt-b"),
                nodeBId,
                "bluetooth",
                btEndpoint,
                btConnId
        );
        connRegistryA.registerPath(nodeBId, btPath);

        // 3. Send application message from Node A to Node B via PeerRouter
        String payloadText = "Hello Peer B over Bluetooth RFCOMM via CompositeTransport!";
        routerA.send(nodeAId, nodeBId, "msg-002", payloadText.getBytes(StandardCharsets.UTF_8));

        // 4. Verify message received and decoded on Node B
        assertTrue(receiverB.messageLatch.await(5, TimeUnit.SECONDS), "Message did not arrive over Bluetooth in time");
        assertEquals(1, receiverB.receivedMessages.size());

        Message received = receiverB.receivedMessages.get(0);
        assertEquals(MessageType.MESSAGE, received.type());
        assertEquals(nodeAId.value(), received.senderId());
        assertEquals("msg-002", received.messageId());
        assertEquals(payloadText, new String(received.payload(), StandardCharsets.UTF_8));
    }

    @Test
    @DisplayName("S8.4.3: End-to-end multi-transport session across both TCP and Bluetooth RFCOMM")
    void testSimultaneousTransportsUnderOneRouter() throws Exception {
        PeerId nodeAId = PeerId.of("peer-node-a");
        PeerId peerTcp = PeerId.of("peer-tcp-only");
        PeerId peerBt = PeerId.of("peer-bt-only");

        PeerRegistry registryA = new PeerRegistry();
        PeerConnectivityRegistry connRegistryA = new PeerConnectivityRegistry();
        PeerRouter routerA = new PeerRouter(registryA, compositeTransportNodeA, connRegistryA);

        TestNodeReceiver receiverB = new TestNodeReceiver(2);
        compositeTransportNodeB.setListener(receiverB);

        // Connect TCP
        int tcpPort = tcpServer.getBoundPort();
        tcpClient.connect("127.0.0.1", tcpPort);
        Thread.sleep(100);
        connRegistryA.registerPath(peerTcp, ConnectivityPath.active(
                PathId.of("p-tcp"), peerTcp, "tcp", EndpointAddress.tcp("127.0.0.1", tcpPort), "127.0.0.1:" + tcpPort
        ));

        // Connect Bluetooth
        btTransportA.connect(MAC_B);
        connRegistryA.registerPath(peerBt, ConnectivityPath.active(
                PathId.of("p-bt"), peerBt, "bluetooth", EndpointAddress.of("bluetooth", MAC_B, 1), "bt:" + MAC_B
        ));

        // Send over TCP
        routerA.send(nodeAId, peerTcp, "tcp-msg-1", "TCP Data".getBytes(StandardCharsets.UTF_8));
        // Send over Bluetooth
        routerA.send(nodeAId, peerBt, "bt-msg-1", "BT Data".getBytes(StandardCharsets.UTF_8));

        // Assert both arrived at receiver
        assertTrue(receiverB.messageLatch.await(5, TimeUnit.SECONDS));
        assertEquals(2, receiverB.receivedMessages.size());
    }

    /**
     * Receiver that consumes transport events, parses frames into Messages, and accumulates them.
     */
    private static class TestNodeReceiver implements TransportListener {
        final ConcurrentHashMap<String, FrameDecoder> decoders = new ConcurrentHashMap<>();
        final List<Message> receivedMessages = Collections.synchronizedList(new ArrayList<>());
        final CountDownLatch messageLatch;

        TestNodeReceiver(int expectedMessages) {
            this.messageLatch = new CountDownLatch(expectedMessages);
        }

        @Override
        public void onDataReceived(String senderId, byte[] payload) {
            FrameDecoder decoder = decoders.computeIfAbsent(senderId, k -> new FrameDecoder());
            List<byte[]> frames = decoder.feed(payload);
            for (byte[] frame : frames) {
                try {
                    Message msg = MessageParser.parse(frame);
                    receivedMessages.add(msg);
                    messageLatch.countDown();
                } catch (Exception ignored) {
                }
            }
        }
    }
}