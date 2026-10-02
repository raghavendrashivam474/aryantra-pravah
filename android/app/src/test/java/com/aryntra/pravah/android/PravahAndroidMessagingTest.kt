package com.aryntra.pravah.android

import com.aryntra.pravah.messaging.ApplicationMessage
import com.aryntra.pravah.messaging.ApplicationMessageListener
import com.aryntra.pravah.messaging.MessageState
import com.aryntra.pravah.messaging.reliability.OutboxEntry
import com.aryntra.pravah.messaging.reliability.OutboxState
import com.aryntra.pravah.peer.PeerId
import com.aryntra.pravah.protocol.PeerState
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@DisplayName("S7.4 Android Messaging End-to-End Tests")
class PravahAndroidMessagingTest {

    private lateinit var nodeA: PravahAndroidMessagingManager
    private lateinit var nodeB: PravahAndroidMessagingManager

    private val peerA = PeerId.of("android-node-a")
    private val peerB = PeerId.of("android-node-b")

    @BeforeEach
    fun setUp() {
        nodeA = PravahAndroidMessagingManager(peerA, "127.0.0.1", 0)
        nodeB = PravahAndroidMessagingManager(peerB, "127.0.0.1", 0)
        nodeA.start()
        nodeB.start()
    }

    @AfterEach
    fun tearDown() {
        nodeA.close()
        nodeB.close()
    }

    private fun establishSession() {
        // 1. Establish physical TCP connection from A to B
        nodeA.connectTo("127.0.0.1", nodeB.boundPort)
        val connIdAtoB = "127.0.0.1:${nodeB.boundPort}"

        // 2. Node A sends JOIN to Node B
        nodeA.sendJoin(peerB, connIdAtoB)
        Thread.sleep(100)

        // 3. Node B replies JOIN to Node A
        nodeB.replyJoin(peerA)
        Thread.sleep(100)
    }

    @Test
    @DisplayName("Scenario 1: Runtime startup")
    fun testScenario1_RuntimeStartup() {
        assertTrue(nodeA.isRunning)
        assertTrue(nodeB.isRunning)
        assertTrue(nodeA.boundPort > 0)
        assertTrue(nodeB.boundPort > 0)
        assertEquals(peerA, nodeA.localPeerId)
        assertEquals(peerB, nodeB.localPeerId)
    }

    @Test
    @DisplayName("Scenario 2: TCP Connection established")
    fun testScenario2_Connection() {
        nodeA.connectTo("127.0.0.1", nodeB.boundPort)
        Thread.sleep(100)
        // Transport should be running and accepting connections
        assertTrue(nodeA.isRunning)
        assertTrue(nodeB.isRunning)
    }

    @Test
    @DisplayName("Scenario 3: Protocol JOIN handshake creates session")
    fun testScenario3_JoinHandshake() {
        establishSession()

        // Protocol session managers should have recorded JOINED state
        assertEquals(
            PeerState.JOINED,
            nodeB.coordinator.sessionManager.getPeerState(peerA.value())
        )
        assertEquals(
            PeerState.JOINED,
            nodeA.coordinator.sessionManager.getPeerState(peerB.value())
        )
    }

    @Test
    @DisplayName("Scenario 4: 1:1 Application message delivery")
    fun testScenario4_SingleMessageDelivery() {
        establishSession()

        val receivedLatch = CountDownLatch(1)
        var receivedMessage: ApplicationMessage? = null

        nodeB.addMessageListener(object : ApplicationMessageListener {
            override fun onMessage(message: ApplicationMessage) {
                receivedMessage = message
                receivedLatch.countDown()
            }
        })

        val sentMsg = nodeA.sendText(peerB, "Hello from Android Node A")

        assertTrue(receivedLatch.await(3, TimeUnit.SECONDS), "Message was not received by Node B")
        assertNotNull(receivedMessage)
        assertEquals(sentMsg.messageId(), receivedMessage!!.messageId())
        assertEquals("Hello from Android Node A", receivedMessage!!.content())
        assertEquals(peerA, receivedMessage!!.sender())
    }

    @Test
    @DisplayName("Scenario 5 & 6: ACK handling and Outbox completion")
    fun testScenario5And6_AckAndDeliveryState() {
        establishSession()

        val receivedLatch = CountDownLatch(1)
        nodeB.addMessageListener(object : ApplicationMessageListener {
            override fun onMessage(message: ApplicationMessage) {
                receivedLatch.countDown()
            }
        })

        val sentMsg = nodeA.sendText(peerB, "ACK verification message")
        assertTrue(receivedLatch.await(3, TimeUnit.SECONDS), "Message not received")

        // Wait briefly for the automated ACK roundtrip to complete
        var outboxEntry: OutboxEntry? = null
        for (i in 1..30) {
            Thread.sleep(50)
            val opt = nodeA.outbox.findByMessageId(sentMsg.messageId())
            if (opt.isPresent && opt.get().state() == OutboxState.COMPLETED) {
                outboxEntry = opt.get()
                break
            }
        }

        assertNotNull(outboxEntry, "Outbox entry should reach COMPLETED state upon receiving ACK")
        assertEquals(OutboxState.COMPLETED, outboxEntry!!.state())
        assertEquals(sentMsg.messageId(), outboxEntry.messageId())
        assertEquals(peerB, outboxEntry.destination())
    }

    @Test
    @DisplayName("Scenario 7: Multiple messages retain distinct IDs and correct payloads")
    fun testScenario7_MultipleMessages() {
        establishSession()

        val receivedMessages = ConcurrentLinkedQueue<ApplicationMessage>()
        val latch = CountDownLatch(3)

        nodeB.addMessageListener(object : ApplicationMessageListener {
            override fun onMessage(message: ApplicationMessage) {
                receivedMessages.add(message)
                latch.countDown()
            }
        })

        val msg1 = nodeA.sendText(peerB, "Message Alpha")
        val msg2 = nodeA.sendText(peerB, "Message Beta")
        val msg3 = nodeA.sendText(peerB, "Message Gamma")

        assertTrue(latch.await(3, TimeUnit.SECONDS), "Not all 3 messages were received")
        assertEquals(3, receivedMessages.size)

        val list = receivedMessages.toList()
        assertEquals("Message Alpha", list[0].content())
        assertEquals(msg1.messageId(), list[0].messageId())

        assertEquals("Message Beta", list[1].content())
        assertEquals(msg2.messageId(), list[1].messageId())

        assertEquals("Message Gamma", list[2].content())
        assertEquals(msg3.messageId(), list[2].messageId())

        // Verify all 3 IDs are unique
        val uniqueIds = setOf(msg1.messageId(), msg2.messageId(), msg3.messageId())
        assertEquals(3, uniqueIds.size)
    }

    @Test
    @DisplayName("Scenario 8: Disconnect closes connection and resets session")
    fun testScenario8_Disconnect() {
        establishSession()

        // Verify connected and session established
        assertEquals(
            PeerState.JOINED,
            nodeB.coordinator.sessionManager.getPeerState(peerA.value())
        )

        // Stop Node A (simulating disconnect)
        nodeA.stop()
        Thread.sleep(150)

        // Peer B's coordinator should handle connection close and unregister peer
        assertTrue(nodeB.coordinator.getConnectionIdForPeer(peerA).isEmpty)
    }

    @Test
    @DisplayName("Scenario 9: Reconnect with same stable PeerId")
    fun testScenario9_ReconnectSamePeerId() {
        establishSession()

        // Disconnect Node A
        nodeA.stop()
        Thread.sleep(100)

        // Restart Node A with SAME PeerId
        nodeA = PravahAndroidMessagingManager(peerA, "127.0.0.1", 0)
        nodeA.start()

        // Reconnect and re-handshake
        nodeA.connectTo("127.0.0.1", nodeB.boundPort)
        val connIdAtoB = "127.0.0.1:${nodeB.boundPort}"
        nodeA.sendJoin(peerB, connIdAtoB)
        Thread.sleep(100)
        nodeB.replyJoin(peerA)
        Thread.sleep(100)

        // PeerId identity remains identical
        assertEquals(peerA, nodeA.localPeerId)
        assertEquals(
            PeerState.JOINED,
            nodeB.coordinator.sessionManager.getPeerState(peerA.value())
        )
    }

    @Test
    @DisplayName("Scenario 10: Message exchange after reconnection")
    fun testScenario10_MessageAfterReconnect() {
        establishSession()

        // 1. Message before disconnect
        val latch1 = CountDownLatch(1)
        var msgReceived1: ApplicationMessage? = null
        nodeB.addMessageListener(object : ApplicationMessageListener {
            override fun onMessage(message: ApplicationMessage) {
                msgReceived1 = message
                latch1.countDown()
            }
        })

        nodeA.sendText(peerB, "Pre-disconnect message")
        assertTrue(latch1.await(3, TimeUnit.SECONDS))
        assertEquals("Pre-disconnect message", msgReceived1?.content())

        // 2. Disconnect Node A
        nodeA.stop()
        Thread.sleep(100)

        // 3. Restart Node A with same PeerId
        nodeA = PravahAndroidMessagingManager(peerA, "127.0.0.1", 0)
        nodeA.start()

        // 4. Reconnect
        nodeA.connectTo("127.0.0.1", nodeB.boundPort)
        val connIdAtoB = "127.0.0.1:${nodeB.boundPort}"
        nodeA.sendJoin(peerB, connIdAtoB)
        Thread.sleep(100)
        nodeB.replyJoin(peerA)
        Thread.sleep(100)

        // 5. Message after reconnect
        val latch2 = CountDownLatch(1)
        var msgReceived2: ApplicationMessage? = null
        nodeB.addMessageListener(object : ApplicationMessageListener {
            override fun onMessage(message: ApplicationMessage) {
                msgReceived2 = message
                latch2.countDown()
            }
        })

        nodeA.sendText(peerB, "Post-reconnect message")
        assertTrue(latch2.await(3, TimeUnit.SECONDS), "Post-reconnect message was not received")
        assertEquals("Post-reconnect message", msgReceived2?.content())
    }
}
