package com.aryntra.pravah.connectivity;

import com.aryntra.pravah.peer.PeerId;
import com.aryntra.pravah.peer.PeerRecord;
import com.aryntra.pravah.peer.PeerRegistry;
import com.aryntra.pravah.peer.PeerRouter;
import com.aryntra.pravah.protocol.FrameDecoder;
import com.aryntra.pravah.protocol.FrameEncoder;
import com.aryntra.pravah.protocol.Message;
import com.aryntra.pravah.protocol.MessageEncoder;
import com.aryntra.pravah.protocol.MessageParser;
import com.aryntra.pravah.protocol.MessageType;
import com.aryntra.pravah.transport.TransportCapabilities;
import com.aryntra.pravah.transport.TransportListener;
import com.aryntra.pravah.transport.tcp.TcpTransport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("S6.1-S6.3 - Connectivity Architecture Integration Test")
class ConnectivityIntegrationTest {

    private static final byte APP_MSG_TEXT = 0x01;
    private static final byte APP_MSG_ACK = 0x02;

    private TcpTransport serverTransport;
    private PeerRegistry peerRegistry;
    private PeerConnectivityRegistry connectivityRegistry;
    private PeerRouter peerRouter;
    private int serverPort;

    private final PeerId localNode = PeerId.of("node-local");
    private final PeerId remotePeer = PeerId.of("peer-remote");

    @BeforeEach
    void setUp() throws Exception {
        peerRegistry = new PeerRegistry();
        connectivityRegistry = new PeerConnectivityRegistry();

        // Start local TCP server transport on random port
        serverTransport = new TcpTransport("127.0.0.1", 0);
        serverTransport.start();
        serverPort = serverTransport.getBoundPort();

        peerRouter = new PeerRouter(peerRegistry, serverTransport);
    }

    @AfterEach
    void tearDown() {
        if (serverTransport != null && serverTransport.isRunning()) {
            serverTransport.stop();
        }
    }

    @Test
    @DisplayName("Full flow: Discovered Endpoint -> Candidate Path -> Established Connection -> Active Path -> Message Dispatch -> Client Receipt -> ACK")
    void testEndToEndConnectivityFlow() throws Exception {
        // 1. Verify Transport Capabilities on the underlying transport
        TransportCapabilities caps = serverTransport.getCapabilities();
        assertTrue(caps.reliable());
        assertTrue(caps.connectionOriented());

        // 2. Discover endpoint and register Candidate Path in Connectivity Model
        EndpointAddress remoteEndpoint = EndpointAddress.tcp("127.0.0.1", serverPort);
        PathId pathId = PathId.of("path-tcp-primary");

        ConnectivityPath candidatePath = ConnectivityPath.candidate(
                pathId, remotePeer, serverTransport.getName(), remoteEndpoint);

        connectivityRegistry.registerPath(remotePeer, candidatePath);

        // Verify Candidate state
        PeerConnectivity connectivity = connectivityRegistry.lookup(remotePeer).orElseThrow();
        assertEquals(1, connectivity.candidatePaths().size());
        assertFalse(connectivity.hasActivePath());

        // 3. Remote client connects into server
        CountDownLatch connectionLatch = new CountDownLatch(1);
        AtomicReference<String> establishedConnId = new AtomicReference<>();

        serverTransport.setListener(new TransportListener() {
            @Override
            public void onDataReceived(String senderId, byte[] payload) {
                // Handled in step 6
            }

            @Override
            public void onConnectionOpened(String connectionId) {
                establishedConnId.set(connectionId);
                connectionLatch.countDown();
            }
        });

        Socket clientSocket = new Socket("127.0.0.1", serverPort);
        assertTrue(connectionLatch.await(3, TimeUnit.SECONDS), "Connection should open");
        assertNotNull(establishedConnId.get());

        String connId = establishedConnId.get();

        // 4. Update Connectivity Path to ACTIVE and register in PeerRegistry
        ConnectivityPath activePath = candidatePath.activate(connId);
        connectivityRegistry.registerPath(remotePeer, activePath);
        peerRegistry.register(PeerRecord.connected(remotePeer, connId));

        assertTrue(connectivity.hasActivePath());
        assertEquals(1, connectivity.activePaths().size());

        // 5. Send message through PeerRouter -> FrameEncoder -> TcpTransport -> Socket
        String rawText = "Pravah Phase 6 Connectivity Active!";
        byte[] textBytes = rawText.getBytes(StandardCharsets.UTF_8);
        byte[] appPayload = new byte[1 + textBytes.length];
        appPayload[0] = APP_MSG_TEXT;
        System.arraycopy(textBytes, 0, appPayload, 1, textBytes.length);

        CountDownLatch messageReceivedLatch = new CountDownLatch(1);
        AtomicReference<Message> receivedMessageRef = new AtomicReference<>();

        // Remote client reading thread
        Thread clientReader = new Thread(() -> {
            try {
                InputStream in = clientSocket.getInputStream();
                byte[] lengthBuf = new byte[4];
                int read = in.readNBytes(lengthBuf, 0, 4);
                if (read == 4) {
                    int length = ((lengthBuf[0] & 0xFF) << 24) |
                                 ((lengthBuf[1] & 0xFF) << 16) |
                                 ((lengthBuf[2] & 0xFF) << 8)  |
                                 (lengthBuf[3] & 0xFF);
                    byte[] payload = in.readNBytes(length);
                    Message parsed = MessageParser.parse(payload);
                    receivedMessageRef.set(parsed);
                    messageReceivedLatch.countDown();
                }
            } catch (Exception ignored) {}
        });
        clientReader.start();

        // Dispatch via PeerRouter targeting PeerId
        peerRouter.send(localNode, remotePeer, "msg-s6-001", appPayload);

        assertTrue(messageReceivedLatch.await(3, TimeUnit.SECONDS), "Remote client should receive framed message");
        Message received = receivedMessageRef.get();
        assertNotNull(received);
        assertEquals(MessageType.MESSAGE, received.type());
        assertEquals(localNode.value(), received.senderId());
        assertEquals("msg-s6-001", received.messageId());
        assertArrayEquals(appPayload, received.payload());

        // 6. Remote client sends ACK back -> Server receives and parses
        CountDownLatch ackLatch = new CountDownLatch(1);
        AtomicReference<Message> serverReceivedAck = new AtomicReference<>();
        FrameDecoder frameDecoder = new FrameDecoder();

        serverTransport.setListener(new TransportListener() {
            @Override
            public void onDataReceived(String senderId, byte[] payload) {
                List<byte[]> frames = frameDecoder.feed(payload);
                for (byte[] frame : frames) {
                    try {
                        Message ack = MessageParser.parse(frame);
                        serverReceivedAck.set(ack);
                        ackLatch.countDown();
                    } catch (Exception ignored) {}
                }
            }
        });

        byte[] msgIdBytes = "msg-s6-001".getBytes(StandardCharsets.UTF_8);
        byte[] ackPayload = new byte[1 + msgIdBytes.length];
        ackPayload[0] = APP_MSG_ACK;
        System.arraycopy(msgIdBytes, 0, ackPayload, 1, msgIdBytes.length);

        Message ackMsg = new Message(MessageType.MESSAGE, remotePeer.value(), "ack-001", ackPayload);
        byte[] encodedAck = MessageEncoder.encode(ackMsg);
        byte[] framedAck = FrameEncoder.encode(encodedAck);

        OutputStream out = clientSocket.getOutputStream();
        out.write(framedAck);
        out.flush();

        assertTrue(ackLatch.await(3, TimeUnit.SECONDS), "Server should receive ACK from remote client");
        Message ack = serverReceivedAck.get();
        assertNotNull(ack);
        assertEquals(MessageType.MESSAGE, ack.type());
        assertEquals("ack-001", ack.messageId());
        assertEquals(remotePeer.value(), ack.senderId());

        // Clean up client socket
        clientSocket.close();
    }
}
