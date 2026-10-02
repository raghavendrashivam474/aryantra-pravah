package com.aryntra.pravah.android

import com.aryntra.pravah.android.bluetooth.AndroidBluetoothRfcommTransport
import com.aryntra.pravah.connectivity.ConnectivityPath
import com.aryntra.pravah.connectivity.EndpointAddress
import com.aryntra.pravah.connectivity.PathId
import com.aryntra.pravah.connectivity.PathSelectionPolicy
import com.aryntra.pravah.connectivity.PeerConnectivityRegistry
import com.aryntra.pravah.messaging.ApplicationMessage
import com.aryntra.pravah.messaging.ApplicationMessageListener
import com.aryntra.pravah.messaging.DefaultApplicationMessagingService
import com.aryntra.pravah.messaging.InMemoryMessageHistoryStore
import com.aryntra.pravah.messaging.MessageHistoryStore
import com.aryntra.pravah.messaging.reliability.DeliveryOutbox
import com.aryntra.pravah.messaging.reliability.InMemoryDeliveryOutbox
import com.aryntra.pravah.messaging.reliability.OutboxState
import com.aryntra.pravah.peer.PeerConnectionCoordinator
import com.aryntra.pravah.peer.PeerId
import com.aryntra.pravah.peer.PeerRegistry
import com.aryntra.pravah.peer.PeerRouter
import com.aryntra.pravah.peer.discovery.DiscoveredPeer
import com.aryntra.pravah.peer.discovery.LanPeerDiscovery
import com.aryntra.pravah.peer.discovery.PeerDiscoveryListener
import com.aryntra.pravah.peer.presence.PeerPresenceBridge
import com.aryntra.pravah.peer.presence.PeerPresenceManager
import com.aryntra.pravah.protocol.FrameEncoder
import com.aryntra.pravah.protocol.Message
import com.aryntra.pravah.protocol.MessageEncoder
import com.aryntra.pravah.protocol.MessageType
import com.aryntra.pravah.protocol.PeerState
import com.aryntra.pravah.protocol.ProtocolListener
import com.aryntra.pravah.transport.CompositeTransport
import com.aryntra.pravah.transport.Transport
import com.aryntra.pravah.transport.tcp.TcpTransport
import java.util.Collections
import java.util.concurrent.CopyOnWriteArrayList
import java.util.logging.Logger

/**
 * S8.6 - Android Hybrid Messaging Manager.
 * Orchestrates multi-path routing across TCP and Bluetooth RFCOMM via CompositeTransport.
 */
class PravahAndroidMessagingManager(
    val localPeerId: PeerId,
    val host: String = "0.0.0.0",
    val port: Int = 0,
    val discoveryPort: Int = 49152,
    val customTransport: Transport? = null,
    val localMacAddress: String = "02:00:00:00:00:00"
) : AutoCloseable {

    private val logger = Logger.getLogger(PravahAndroidMessagingManager::class.java.name)

    // Concrete underlying transports
    val tcpTransport: TcpTransport = TcpTransport(host, port)
    val bluetoothTransport: AndroidBluetoothRfcommTransport = AndroidBluetoothRfcommTransport(localMacAddress)

    // Unified Composite Transport
    val compositeTransport: Transport = customTransport ?: CompositeTransport(tcpTransport, bluetoothTransport)

    val registry = PeerRegistry()
    val presenceManager = PeerPresenceManager(2000L)
    val connectivityRegistry = PeerConnectivityRegistry()
    val presenceBridge = PeerPresenceBridge(registry, presenceManager, connectivityRegistry)

    // Path selection policy: prefer high-bandwidth TCP over Bluetooth, with seamless failover
    val pathPolicy: PathSelectionPolicy = PathSelectionPolicy.preferSchemes("tcp", "bluetooth", "bt")

    // Override setProtocolListener to chain listeners and auto-reply initial JOINs
    val coordinator = object : PeerConnectionCoordinator(compositeTransport, registry, presenceBridge) {
        private var downstreamListener: ProtocolListener? = null

        override fun setProtocolListener(listener: ProtocolListener?) {
            this.downstreamListener = listener
            super.setProtocolListener(object : ProtocolListener {
                override fun onPeerJoined(peerIdStr: String, message: Message) {
                    downstreamListener?.onPeerJoined(peerIdStr, message)
                    if (message != null && message.messageId().startsWith("join-")) {
                        try {
                            val remotePeer = PeerId.of(peerIdStr)
                            logger.info("Auto-replying reciprocal JOIN to $peerIdStr")
                            replyJoin(remotePeer)
                        } catch (e: Exception) {
                            logger.fine("Reciprocal JOIN auto-reply notice: ${e.message}")
                        }
                    }
                }

                override fun onMessageReceived(peerIdStr: String, message: Message) {
                    downstreamListener?.onMessageReceived(peerIdStr, message)
                }

                override fun onPeerLeft(peerIdStr: String, message: Message) {
                    downstreamListener?.onPeerLeft(peerIdStr, message)
                }
            })
        }
    }

    val router = PeerRouter(registry, compositeTransport, connectivityRegistry, pathPolicy)
    val historyStore: MessageHistoryStore = InMemoryMessageHistoryStore()
    val outbox: DeliveryOutbox = InMemoryDeliveryOutbox()
    val messaging = DefaultApplicationMessagingService(
        localPeerId, router, coordinator, historyStore, outbox
    )

    private var discoveryEngine: LanPeerDiscovery? = null
    private val _discoveredPeers = CopyOnWriteArrayList<DiscoveredPeer>()
    val discoveredPeers: List<DiscoveredPeer> get() = Collections.unmodifiableList(_discoveredPeers)
    private var discoveryListener: ((DiscoveredPeer) -> Unit)? = null

    val boundPort: Int get() = tcpTransport.boundPort
    val isRunning: Boolean get() = compositeTransport.isRunning

    fun start() {
        compositeTransport.start()
        presenceManager.start()
    }

    fun stop() {
        stopDiscovery()
        presenceManager.stop()
        compositeTransport.stop()
    }

    @Synchronized
    fun startDiscovery() {
        if (discoveryEngine == null || !discoveryEngine!!.isRunning) {
            val tcpPort = if (boundPort > 0) boundPort else (if (port > 0) port else 8080)
            discoveryEngine = LanPeerDiscovery(localPeerId, tcpPort, discoveryPort, 200L).apply {
                registerListener(object : PeerDiscoveryListener {
                    override fun onPeerDiscovered(peer: DiscoveredPeer) {
                        if (peer.peerId() != localPeerId && _discoveredPeers.none { it.peerId() == peer.peerId() }) {
                            _discoveredPeers.add(peer)
                            presenceBridge.onPeerDiscovered(peer)
                            discoveryListener?.invoke(peer)
                        }
                    }
                })
                start()
            }
        }
    }

    @Synchronized
    fun stopDiscovery() {
        discoveryEngine?.stop()
        discoveryEngine = null
    }

    val isDiscovering: Boolean get() = discoveryEngine?.isRunning ?: false

    fun setDiscoveryListener(listener: (DiscoveredPeer) -> Unit) {
        discoveryListener = listener
    }

    /**
     * Connects via TCP and registers path in ConnectivityRegistry.
     */
    fun connectToTcp(remoteHost: String, remotePort: Int, remotePeerId: PeerId? = null): String {
        tcpTransport.connect(remoteHost, remotePort)
        val connId = "$remoteHost:$remotePort"
        if (remotePeerId != null) {
            val path = ConnectivityPath.active(
                PathId.of("path-tcp-$connId"),
                remotePeerId,
                "tcp",
                EndpointAddress.tcp(remoteHost, remotePort),
                connId
            )
            connectivityRegistry.registerPath(remotePeerId, path)
        }
        return connId
    }

    /**
     * Backward-compatible alias for connectToTcp.
     */
    fun connectTo(remoteHost: String, remotePort: Int) {
        connectToTcp(remoteHost, remotePort)
    }

    /**
     * Connects via Bluetooth RFCOMM and registers path in ConnectivityRegistry.
     */
    fun connectToBluetooth(remoteMac: String, remotePeerId: PeerId? = null): String {
        bluetoothTransport.connect(remoteMac)
        val cleanMac = remoteMac.removePrefix("bt:").trim().uppercase()
        val connId = "bt:$cleanMac"
        if (remotePeerId != null) {
            val path = ConnectivityPath.active(
                PathId.of("path-bt-$cleanMac"),
                remotePeerId,
                "bluetooth",
                EndpointAddress.of("bluetooth", cleanMac, 1),
                connId
            )
            connectivityRegistry.registerPath(remotePeerId, path)
        }
        return connId
    }

    fun sendJoin(remotePeerId: PeerId, connectionId: String) {
        registry.register(remotePeerId, connectionId)
        presenceBridge.handlePeerConnected(remotePeerId, connectionId)
        val joinMsg = Message(
            MessageType.JOIN, localPeerId.value(),
            "join-${localPeerId.value()}-${remotePeerId.value()}", ByteArray(0)
        )
        compositeTransport.send(connectionId, FrameEncoder.encode(MessageEncoder.encode(joinMsg)))
    }

    fun replyJoin(remotePeerId: PeerId) {
        val joinMsg = Message(
            MessageType.JOIN, localPeerId.value(),
            "reply-join-${localPeerId.value()}-${remotePeerId.value()}", ByteArray(0)
        )
        try {
            router.send(remotePeerId, joinMsg)
        } catch (e: Exception) {
            logger.fine("Reply JOIN error: ${e.message}")
        }
    }

    fun getSessionState(peerIdValue: String): PeerState? {
        return coordinator.sessionManager.getPeerState(peerIdValue)
    }

    fun isDelivered(messageId: String): Boolean {
        return outbox.findByMessageId(messageId).map { it.state() == OutboxState.COMPLETED }.orElse(false)
    }

    fun sendText(destination: PeerId, content: String): ApplicationMessage {
        return messaging.sendText(destination, content)
    }

    fun addMessageListener(listener: ApplicationMessageListener) {
        messaging.addListener(listener)
    }

    override fun close() { stop() }
}