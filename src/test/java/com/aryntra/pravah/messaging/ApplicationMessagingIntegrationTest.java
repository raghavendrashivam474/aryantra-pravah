package com.aryntra.pravah.messaging;

import com.aryntra.pravah.peer.PeerConnectionCoordinator;
import com.aryntra.pravah.peer.PeerId;
import com.aryntra.pravah.peer.presence.PeerPresenceBridge;
import com.aryntra.pravah.peer.presence.PeerPresenceManager;
import com.aryntra.pravah.peer.PeerRegistry;
import com.aryntra.pravah.peer.PeerRouter;
import com.aryntra.pravah.protocol.FrameEncoder;
import com.aryntra.pravah.protocol.Message;
import com.aryntra.pravah.protocol.MessageEncoder;
import com.aryntra.pravah.protocol.MessageType;
import com.aryntra.pravah.transport.tcp.TcpTransport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("S4.1–S4.3 Application Messaging & Delivery Receipt Integration Test")
class ApplicationMessagingIntegrationTest {

    private PeerId aliceId;
    private PeerId bobId;

    private TcpTransport aliceTransport;
    private TcpTransport bobTransport;

    private PeerRegistry aliceRegistry;
    private PeerRegistry bobRegistry;

    private PeerPresenceManager alicePresence;
    private PeerPresenceManager bobPresence;

    private PeerPresenceBridge aliceBridge;
    private PeerPresenceBridge bobBridge;

    private PeerConnectionCoordinator aliceCoordinator;
    private PeerConnectionCoordinator bobCoordinator;

    private PeerRouter aliceRouter;
    private PeerRouter bobRouter;

    private DefaultApplicationMessagingService aliceMessaging;
    private DefaultApplicationMessagingService bobMessaging;

    @BeforeEach
    void setUp() {
        aliceId = PeerId.of("alice");
        bobId = PeerId.of("bob");

        aliceTransport = new TcpTransport(0);
        bobTransport = new TcpTransport(0);

        aliceTransport.start();
        bobTransport.start();

        aliceRegistry = new PeerRegistry();
        bobRegistry = new PeerRegistry();

        alicePresence = new PeerPresenceManager(2000);
        bobPresence = new PeerPresenceManager(2000);

        alicePresence.start();
        bobPresence.start();

        aliceBridge = new PeerPresenceBridge(aliceRegistry, alicePresence);
        bobBridge = new PeerPresenceBridge(bobRegistry, bobPresence);

        aliceCoordinator = new PeerConnectionCoordinator(aliceTransport, aliceRegistry, aliceBridge);
        bobCoordinator = new PeerConnectionCoordinator(bobTransport, bobRegistry, bobBridge);

        aliceRouter = new PeerRouter(aliceRegistry, aliceTransport);
        bobRouter = new PeerRouter(bobRegistry, bobTransport);

        aliceMessaging = new DefaultApplicationMessagingService(aliceId, aliceRouter, aliceCoordinator);
        bobMessaging = new DefaultApplicationMessagingService(bobId, bobRouter, bobCoordinator);
    }

    @AfterEach
    void tearDown() {
        if (alicePresence != null) alicePresence.stop();
        if (bobPresence != null) bobPresence.stop();
        if (aliceTransport != null) aliceTransport.stop();
        if (bobTransport != null) bobTransport.stop();
    }

    @Test
    @DisplayName("Complete S4.1–S4.3 Flow: Send -> Conversation Linking -> Remote Receive -> Remote ACK -> DELIVERED")
    void testEndToEndLifecycleAndAckDelivery() throws Exception {
        // 1. Establish TCP connection from Alice to Bob
        aliceTransport.connect("127.0.0.1", bobTransport.getBoundPort());
        String aliceOutboundConn = "127.0.0.1:" + bobTransport.getBoundPort();
        aliceRegistry.register(bobId, aliceOutboundConn);

        // 2. Alice sends JOIN protocol message so Bob authenticates Alice
        Message aliceJoin = new Message(MessageType.JOIN, aliceId.value(), "join-1", new byte[0]);
        byte[] encodedAliceJoin = FrameEncoder.encode(MessageEncoder.encode(aliceJoin));
        aliceTransport.send(aliceOutboundConn, encodedAliceJoin);

        // Wait for Bob's coordinator to authenticate Alice
        Thread.sleep(100);

        // 3. Bob sends JOIN protocol message so Alice authenticates Bob
        Message bobJoin = new Message(MessageType.JOIN, bobId.value(), "join-2", new byte[0]);
        bobRouter.send(aliceId, bobJoin);

        // Wait for Alice's coordinator to authenticate Bob
        Thread.sleep(100);

        // 4. Set up Bob's listener and Alice's lifecycle listener
        CountDownLatch bobReceiveLatch = new CountDownLatch(1);
        CountDownLatch aliceDeliveredLatch = new CountDownLatch(1);
        AtomicReference<ApplicationMessage> bobReceivedMessage = new AtomicReference<>();

        bobMessaging.addListener(msg -> {
            bobReceivedMessage.set(msg);
            bobReceiveLatch.countDown();
        });

        aliceMessaging.addLifecycleListener((msgId, state) -> {
            if (state == MessageState.DELIVERED) {
                aliceDeliveredLatch.countDown();
            }
        });

        // 5. Alice sends text message
        ApplicationMessage sentMsg = aliceMessaging.sendText(bobId, "Pravah Application Layer Verified!");
        assertEquals(MessageState.SENT, aliceMessaging.getMessageState(sentMsg.messageId()));

        // 6. Bob receives message and conversation context is derived automatically
        boolean bobGotIt = bobReceiveLatch.await(5, TimeUnit.SECONDS);
        assertTrue(bobGotIt, "Bob must receive the message");

        ApplicationMessage received = bobReceivedMessage.get();
        assertEquals(sentMsg.messageId(), received.messageId());
        assertEquals(aliceId, received.sender());
        assertEquals("Pravah Application Layer Verified!", received.content());
        assertEquals(ConversationManager.deriveDirectConversationId(aliceId, bobId), received.conversationId());

        // 7. Alice receives Bob's ACK and transitions to DELIVERED
        boolean aliceGotAck = aliceDeliveredLatch.await(5, TimeUnit.SECONDS);
        assertTrue(aliceGotAck, "Alice must receive delivery ACK from Bob");
        assertEquals(MessageState.DELIVERED, aliceMessaging.getMessageState(sentMsg.messageId()));
    }
}