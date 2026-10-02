package com.aryntra.pravah.android

import com.aryntra.pravah.connectivity.ConnectivityPath
import com.aryntra.pravah.connectivity.EndpointAddress
import com.aryntra.pravah.connectivity.PathId
import com.aryntra.pravah.connectivity.PathState
import com.aryntra.pravah.connectivity.PeerConnectivityRegistry
import com.aryntra.pravah.core.PravahException
import com.aryntra.pravah.peer.PeerId
import com.aryntra.pravah.transport.TransportListener
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class PravahAndroidNetworkTest {

    @Test
    fun testTransportLifecycle() {
        val networkManager = PravahAndroidNetworkManager(port = 0)
        assertFalse(networkManager.isRunning)

        networkManager.start()
        assertTrue(networkManager.isRunning)
        assertTrue(networkManager.boundPort > 0)

        networkManager.stop()
        assertFalse(networkManager.isRunning)
    }

    @Test
    fun testTcpConnectionAndDataRoundtrip() {
        val server = PravahAndroidNetworkManager(port = 0)
        val client = PravahAndroidNetworkManager(port = 0)

        server.start()
        client.start()

        val serverReceivedLatch = CountDownLatch(1)
        val clientConnectedLatch = CountDownLatch(1)
        var receivedPayload: ByteArray? = null

        server.addListener(object : TransportListener {
            override fun onDataReceived(senderId: String, payload: ByteArray) {
                receivedPayload = payload
                serverReceivedLatch.countDown()
            }
        })

        client.addListener(object : TransportListener {
            override fun onConnectionOpened(connectionId: String) {
                clientConnectedLatch.countDown()
            }
            override fun onDataReceived(senderId: String, payload: ByteArray) {}
        })

        // Connect client to server
        client.connect("127.0.0.1", server.boundPort)
        assertTrue(clientConnectedLatch.await(3, TimeUnit.SECONDS), "Client failed to establish connection")

        // Client sends framed payload
        val testData = "Android-to-JVM-Pravah-Test".toByteArray(Charsets.UTF_8)
        client.send(server.boundPort.toString(), testData)

        assertTrue(serverReceivedLatch.await(3, TimeUnit.SECONDS), "Server did not receive payload")
        assertArrayEquals(testData, receivedPayload)

        // Teardown
        client.stop()
        server.stop()
    }

    @Test
    fun testConnectionFailurePropagation() {
        val client = PravahAndroidNetworkManager(port = 0)
        client.start()

        // Attempt connecting to closed port
        assertThrows(PravahException::class.java) {
            client.connect("127.0.0.1", 59999)
        }

        assertEquals(0, client.connectionCount)
        client.stop()
    }

    @Test
    fun testConnectivityPathPromotionAndDeactivation() {
        val registry = PeerConnectivityRegistry()
        val server = PravahAndroidNetworkManager(port = 0, connectivityRegistry = registry)
        val client = PravahAndroidNetworkManager(port = 0, connectivityRegistry = registry)

        server.start()
        client.start()

        val peerB = PeerId.of("peer-b")
        val candidatePath = ConnectivityPath.candidate(
            PathId.of("path:peer-b:tcp:127.0.0.1:${server.boundPort}"),
            peerB,
            "tcp",
            EndpointAddress.tcp("127.0.0.1", server.boundPort)
        )
        registry.registerPath(peerB, candidatePath)

        // Verify Candidate
        assertEquals(1, registry.lookup(peerB).get().candidatePaths().size)
        assertEquals(0, registry.lookup(peerB).get().activePaths().size)

        // Connect via candidate path promotion
        client.connectPath(peerB, candidatePath)

        // Verify Promotion to Active
        val activePaths = registry.lookup(peerB).get().activePaths()
        assertEquals(1, activePaths.size)
        assertEquals(PathState.ACTIVE, activePaths[0].state())

        // Stop server to trigger disconnect
        val closedLatch = CountDownLatch(1)
        client.addListener(object : TransportListener {
            override fun onDataReceived(senderId: String, payload: ByteArray) {}
            override fun onConnectionClosed(connectionId: String) {
                closedLatch.countDown()
            }
        })

        server.stop()
        closedLatch.await(2, TimeUnit.SECONDS)

        // Verify Inactive
        val finalActive = registry.lookup(peerB).get().activePaths()
        assertEquals(0, finalActive.size, "Active paths should be deactivated upon connection close")

        client.stop()
    }
}
