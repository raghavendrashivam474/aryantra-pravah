package com.aryntra.pravah.android

import com.aryntra.pravah.connectivity.ConnectivityPath
import com.aryntra.pravah.connectivity.EndpointAddress
import com.aryntra.pravah.connectivity.PathId
import com.aryntra.pravah.messaging.ApplicationMessageListener
import com.aryntra.pravah.peer.PeerId
import com.aryntra.pravah.protocol.MessageType
import com.aryntra.pravah.protocol.PeerState
import com.aryntra.pravah.transport.CompositeTransport
import com.aryntra.pravah.transport.Transport
import com.aryntra.pravah.transport.TransportCapabilities
import com.aryntra.pravah.transport.TransportListener
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

@DisplayName("S8.6 - Android Hybrid Messaging & Multi-Path Tests")
class PravahAndroidHybridTest {

    private lateinit var nodeA: PravahAndroidMessagingManager
    private lateinit var nodeB: PravahAndroidMessagingManager

    private val peerA = PeerId.of("android-hybrid-a")
    private val peerB = PeerId.of("android-hybrid-b")

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

    @Test
    @DisplayName("S8.6.1: Manager initializes CompositeTransport with TCP and Bluetooth support")
    fun testManagerCompositeTransportInitialization() {
        assertTrue(nodeA.isRunning)
        assertTrue(nodeA.compositeTransport is CompositeTransport)
        
        val comp = nodeA.compositeTransport as CompositeTransport
        assertTrue(comp.transports.isNotEmpty())
        assertEquals(nodeA.pathPolicy, nodeA.router.selectionPolicy())
    }

    @Test
    @DisplayName("S8.6.2: Single remote peer can register both TCP and Bluetooth paths simultaneously")
    fun testMultiPathRegistrationUnderSinglePeer() {
        val tcpPath = ConnectivityPath.active(
            PathId.of("path-tcp-b"),
            peerB,
            "tcp",
            EndpointAddress.tcp("192.168.1.100", 8080),
            "192.168.1.100:8080"
        )
        val btPath = ConnectivityPath.active(
            PathId.of("path-bt-b"),
            peerB,
            "bluetooth",
            EndpointAddress.of("bluetooth", "AA:BB:CC:DD:EE:02", 1),
            "bt:AA:BB:CC:DD:EE:02"
        )

        nodeA.connectivityRegistry.registerPath(peerB, tcpPath)
        nodeA.connectivityRegistry.registerPath(peerB, btPath)

        val connectivity = nodeA.connectivityRegistry.lookup(peerB).orElseThrow()
        assertEquals(peerB, connectivity.peerId())
        assertEquals(2, connectivity.pathCount())
        assertEquals(2, connectivity.activePaths().size)

        // Path policy prefers TCP
        assertEquals("192.168.1.100:8080", nodeA.router.resolveConnectionId(peerB))
    }

    @Test
    @DisplayName("S8.6.3: Failover seamlessly switches active routing from TCP to Bluetooth path on failure")
    fun testPathFailoverOnTcpDeactivation() {
        val tcpPath = ConnectivityPath.active(
            PathId.of("path-tcp-b"),
            peerB,
            "tcp",
            EndpointAddress.tcp("192.168.1.100", 8080),
            "192.168.1.100:8080"
        )
        val btPath = ConnectivityPath.active(
            PathId.of("path-bt-b"),
            peerB,
            "bluetooth",
            EndpointAddress.of("bluetooth", "AA:BB:CC:DD:EE:02", 1),
            "bt:AA:BB:CC:DD:EE:02"
        )

        nodeA.connectivityRegistry.registerPath(peerB, tcpPath)
        nodeA.connectivityRegistry.registerPath(peerB, btPath)

        // TCP initially selected
        assertEquals("192.168.1.100:8080", nodeA.router.resolveConnectionId(peerB))

        // Deactivate TCP path
        nodeA.connectivityRegistry.getOrCreate(peerB).addPath(tcpPath.deactivate())

        // Bluetooth immediately selected
        assertEquals("bt:AA:BB:CC:DD:EE:02", nodeA.router.resolveConnectionId(peerB))
    }

    @Test
    @DisplayName("S8.6.4: End-to-end messaging and session establishment over Android CompositeTransport")
    fun testEndToEndHybridMessaging() {
        // Connect Node A to Node B via TCP
        val connId = nodeA.connectToTcp("127.0.0.1", nodeB.boundPort, peerB)
        Thread.sleep(150)

        // Handshake JOIN
        nodeA.sendJoin(peerB, connId)
        Thread.sleep(200)

        val latch = CountDownLatch(1)
        val receivedContent = CopyOnWriteArrayList<String>()

        nodeB.addMessageListener(ApplicationMessageListener { msg ->
            receivedContent.add(msg.content())
            latch.countDown()
        })

        // Send application text
        nodeA.sendText(peerB, "Hello Android Hybrid Transport!")

        assertTrue(latch.await(5, TimeUnit.SECONDS), "Message did not arrive in time")
        assertEquals(1, receivedContent.size)
        assertEquals("Hello Android Hybrid Transport!", receivedContent[0])
    }
}