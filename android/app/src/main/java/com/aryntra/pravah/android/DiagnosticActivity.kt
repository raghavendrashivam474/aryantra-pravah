package com.aryntra.pravah.android

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.bluetooth.BluetoothAdapter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.aryntra.pravah.android.presentation.*
import com.aryntra.pravah.android.state.*
import com.aryntra.pravah.connectivity.PathStateListener
import com.aryntra.pravah.messaging.ApplicationMessageListener
import com.aryntra.pravah.peer.PeerId
import com.aryntra.pravah.protocol.Message
import com.aryntra.pravah.protocol.ProtocolListener
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors

/**
 * A.D3: Persistent Pravaah Network Cockpit.
 * Transitions diagnostic dashboard into a unified product experience:
 * 1. Human on the surface (Conversation & Peer Context).
 * 2. Network underneath (Multi-path Topology & Path selection).
 * 3. Truth everywhere (Progressive disclosure & Forensic Live Wire).
 */
class DiagnosticActivity : Activity() {

    private lateinit var manager: PravahAndroidMessagingManager
    private val handler = Handler(Looper.getMainLooper())
    private val backgroundExecutor = Executors.newSingleThreadExecutor()

    private var connectedPeerId: PeerId? = null
    private val liveEventsList = CopyOnWriteArrayList<LiveWireEvent>()
    private val uiMessagesList = CopyOnWriteArrayList<UiMessageItem>()
    private val timeFormatter = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    private var syntheticBtPeerId: PeerId? = null
    private lateinit var activityProtocolListener: ProtocolListener

    // Component Panels
    private lateinit var nodeStatusPanel: NodeStatusPanel
    private lateinit var networkSnapshotPanel: NetworkSnapshotPanel
    private lateinit var peerPanel: PeerPanel
    private lateinit var pathPanel: PathPanel
    private lateinit var liveWirePanel: LiveWirePanel
    private lateinit var operationsPanel: OperationsPanel
    private lateinit var peerContextPanel: PeerContextPanel
    private lateinit var conversationPanel: ConversationPanel

    private lateinit var tvMultiPathTopology: TextView
    private lateinit var tvPeerContext: TextView
    private lateinit var tvConversation: TextView
    private lateinit var etMessage: EditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_diagnostic)

        requestPermissionsIfRequired()

        val tvStatus: TextView = findViewById(R.id.tvStatus)
        tvPeerContext = findViewById(R.id.tvPeerContext)
        tvConversation = findViewById(R.id.tvConversation)
        tvMultiPathTopology = findViewById(R.id.tvMultiPathTopology)
        val tvLog: TextView = findViewById(R.id.tvLog)
        etMessage = findViewById(R.id.etMessage)

        val btnStart: Button = findViewById(R.id.btnStart)
        val btnStop: Button = findViewById(R.id.btnStop)
        val btnDiscover: Button = findViewById(R.id.btnDiscover)
        val btnConnectTcp: Button = findViewById(R.id.btnConnectTcp)
        val btnConnectBt: Button = findViewById(R.id.btnConnectBt)
        val btnSimulateDrop: Button = findViewById(R.id.btnSimulateDrop)
        val btnSend: Button = findViewById(R.id.btnSend)

        // Initialize Panels
        nodeStatusPanel = NodeStatusPanel(tvStatus)
        networkSnapshotPanel = NetworkSnapshotPanel()
        peerPanel = PeerPanel()
        pathPanel = PathPanel()
                val svLog: android.widget.ScrollView = findViewById(R.id.svLog)
        val tvFollowBadge: TextView = findViewById(R.id.tvFollowBadge)
        liveWirePanel = LiveWirePanel(tvLog, svLog, tvFollowBadge)
        peerContextPanel = PeerContextPanel(tvPeerContext)
        conversationPanel = ConversationPanel(tvConversation)

        operationsPanel = OperationsPanel(
            btnStart, btnStop, btnDiscover, btnConnectTcp,
            btnConnectBt, btnSimulateDrop, etMessage, btnSend
        )

        // Tap on Conversation Surface opens Journey Dialog for latest message
        tvConversation.setOnClickListener {
            val latest = uiMessagesList.lastOrNull()
            if (latest != null) {
                MessageJourneyDialog.show(this, latest)
            }
        }

        val shortId = UUID.randomUUID().toString().substring(0, 8)
        val peerId = PeerId.of("android-$shortId")
        manager = PravahAndroidMessagingManager(peerId)

        addSystemEvent("Pravaah Cockpit initialized. Node: ${peerId.value()}")
        updateDashboard()

        btnStart.setOnClickListener { startRuntime() }
        btnStop.setOnClickListener { stopRuntime() }
        btnDiscover.setOnClickListener {
            if (manager.isDiscovering) stopDiscovery() else startDiscovery()
        }

        btnConnectTcp.setOnClickListener {
            val disc = manager.discoveredPeers.firstOrNull()
            if (disc != null) {
                val peer = disc.peerId()
                val hasActiveTcp = manager.connectivityRegistry.lookup(peer).map { conn ->
                    conn.activePaths().any { it.transportName().equals("tcp", ignoreCase = true) }
                }.orElse(false)
                if (hasActiveTcp) {
                    addSystemEvent("TCP already ACTIVE for ${peer.value()} - skipping")
                } else {
                    connectTcp(disc.hostAddress(), disc.port(), peer)
                }
            } else {
                addErrorEvent("No discovered peers to connect TCP")
            }
        }

        btnConnectBt.setOnClickListener { showBluetoothDeviceChooser() }
        btnSimulateDrop.setOnClickListener { showDropTransportDialog() }

        btnSend.setOnClickListener {
            val content = etMessage.text.toString().trim()
            if (content.isNotEmpty()) {
                sendPayloadMessage(content)
                etMessage.setText("")
            }
        }

        // Path state transitions
        manager.connectivityRegistry.addPathStateListener(PathStateListener { _, path, prev ->
            postEvent("PATH", "${path.transportName()} ${prev}->${path.state()}")
            val tb = manager.router.transitionBuffer()
            if (tb != null && tb.size() > 0) {
                postEvent("BUFFER", "Holding ${tb.size()} msgs (${tb.currentBytes()}B)")
            }
        })

        // Protocol listener
        activityProtocolListener = object : ProtocolListener {
            override fun onPeerJoined(peerIdStr: String, message: Message) {
                postEvent("JOIN", "Peer $peerIdStr joined")
                handler.post {
                    val realPeerId = PeerId.of(peerIdStr)
                    val synth = syntheticBtPeerId
                    if (synth != null && synth.value() != peerIdStr) {
                        try {
                            manager.connectivityRegistry.removePeer(synth)
                            addSystemEvent("Migrated synthetic BT peer ${synth.value()} -> real $peerIdStr")
                        } catch (_: Exception) {}
                        syntheticBtPeerId = null
                    }
                    bindSession(realPeerId)
                }
            }

            override fun onMessageReceived(peerIdStr: String, message: Message) {}

            override fun onPeerLeft(peerIdStr: String, message: Message) {
                postEvent("LEFT", "Peer $peerIdStr left")
                handler.post {
                    if (connectedPeerId?.value() == peerIdStr) {
                        connectedPeerId = null
                        updateDashboard()
                    }
                }
            }
        }

        manager.coordinator.setProtocolListener(activityProtocolListener)

        // Application message listener
        manager.addMessageListener(ApplicationMessageListener { msg ->
            handler.post {
                val senderStr = msg.sender().value()
                val content = msg.content()
                val nowStr = timeFormatter.format(Date())

                postEvent("RX", "[${DiagnosticModelMapper.formatHumanDisplayName(senderStr)}] $content")
                bindSession(msg.sender())

                val steps = listOf(
                    MessageJourneyStep("Accepted", "Received on remote network interface", nowStr, true),
                    MessageJourneyStep("Delivered", "Delivered to local application session", nowStr, true)
                )

                val journey = MessageJourneyState(
                    messageId = msg.messageId(),
                    sequenceNumber = msg.sequenceNumber(),
                    destinationPeerId = manager.localPeerId.value(),
                    humanStatus = "âœ“ Delivered",
                    networkAwareStatus = "âœ“ Delivered via Active Path",
                    technicalSummary = "Decoded application frame; sequence=${msg.sequenceNumber()}",
                    steps = steps,
                    forensicLog = listOf(
                        "$nowStr RX messageId=${msg.messageId()} sender=$senderStr seq=${msg.sequenceNumber()}"
                    )
                )

                val uiItem = UiMessageItem(
                    messageId = msg.messageId(),
                    senderPeerId = senderStr,
                    senderDisplayName = DiagnosticModelMapper.formatHumanDisplayName(senderStr),
                    content = content,
                    timestamp = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date()),
                    isOutgoing = false,
                    status = MessageDeliveryStatus.DELIVERED,
                    journey = journey
                )

                uiMessagesList.add(uiItem)
                while (uiMessagesList.size > 50) uiMessagesList.removeAt(0)
                updateDashboard()
            }
        })

        // Discovery listener
        manager.setDiscoveryListener { peer ->
            handler.post {
                bindSession(peer.peerId())
                postEvent("SYSTEM", "DISCOVERED: ${peer.peerId().value()} @ ${peer.hostAddress()}:${peer.port()}")
            }
        }
    }

    private fun requestPermissionsIfRequired() {
        val permissions = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED)
                permissions.add(Manifest.permission.BLUETOOTH_CONNECT)
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED)
                permissions.add(Manifest.permission.BLUETOOTH_SCAN)
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED)
            permissions.add(Manifest.permission.ACCESS_FINE_LOCATION)

        if (permissions.isNotEmpty())
            ActivityCompat.requestPermissions(this, permissions.toTypedArray(), 101)
    }

    private fun bindSession(peer: PeerId) {
        connectedPeerId = peer
        updateDashboard()
    }

    private fun postEvent(type: String, detail: String) {
        val timestamp = timeFormatter.format(Date())
        val event = LiveWireEvent(timestamp, direction = type, eventType = type, detail = detail)
        liveEventsList.add(event)
        while (liveEventsList.size > 100) liveEventsList.removeAt(0)
        handler.post {
            liveWirePanel.appendEvent(event)
            updateDashboard()
        }
    }

    private fun addSystemEvent(msg: String) = postEvent("SYSTEM", msg)
    private fun addErrorEvent(msg: String) = postEvent("ERROR", msg)

    private fun updateDashboard() {
        val uiState = DiagnosticModelMapper.map(manager, connectedPeerId, liveEventsList.toList(), uiMessagesList.toList())

        nodeStatusPanel.render(uiState.nodeStatus)
        peerContextPanel.render(uiState.activePeerContext)
        conversationPanel.render(uiState.conversationMessages)
        operationsPanel.render(uiState.operations)

        val topoSb = StringBuilder()
        topoSb.append(networkSnapshotPanel.renderAsciiTopology(uiState.topology))
        topoSb.append("\n-- PATH CONNECTIONS --\n")
        if (uiState.peers.isNotEmpty()) {
            for (peer in uiState.peers) {
                topoSb.append(peerPanel.formatPeerHeader(peer))
                uiState.paths.filter { it.peerId == peer.peerId }.forEach { path ->
                    topoSb.append(pathPanel.formatPath(path))
                }
            }
        } else {
            topoSb.append("  (No connected peers)\n")
        }

        val tb = uiState.bufferState
        if (tb.currentSize > 0 || tb.totalBuffered > 0) {
            topoSb.append("\n-- TRANSITION BUFFER (B.R2) --\n")
            topoSb.append("  Holding: ${tb.currentSize} msgs (${tb.currentBytes}B)\n")
            topoSb.append("  Buffered: ${tb.totalBuffered} | Flushed: ${tb.totalFlushed} | Expired: ${tb.totalExpired}")
        }

        tvMultiPathTopology.text = topoSb.toString()
    }

    private fun startRuntime() {
        backgroundExecutor.execute {
            try {
                manager.start()
                val port = manager.boundPort
                postEvent("RUNTIME", "Runtime started on TCP port $port")
                handler.post { updateDashboard() }
            } catch (e: Exception) {
                postEvent("ERROR", "Start failed: ${e.message}")
            }
        }
    }

    private fun stopRuntime() {
        backgroundExecutor.execute {
            try {
                manager.stop()
                postEvent("RUNTIME", "Runtime stopped")
                handler.post {
                    connectedPeerId = null
                    updateDashboard()
                }
            } catch (e: Exception) {
                postEvent("ERROR", "Stop failed: ${e.message}")
            }
        }
    }

    private fun startDiscovery() {
        backgroundExecutor.execute {
            try {
                manager.startDiscovery()
                postEvent("DISC", "LAN discovery started")
                handler.post { updateDashboard() }
            } catch (e: Exception) {
                postEvent("ERROR", "Discovery start failed: ${e.message}")
            }
        }
    }

    private fun stopDiscovery() {
        backgroundExecutor.execute {
            try {
                manager.stopDiscovery()
                postEvent("DISC", "LAN discovery stopped")
                handler.post { updateDashboard() }
            } catch (e: Exception) {
                postEvent("ERROR", "Discovery stop failed: ${e.message}")
            }
        }
    }

    private fun connectTcp(host: String, port: Int, peerId: PeerId?) {
        backgroundExecutor.execute {
            try {
                postEvent("TCP", "Connecting to $host:$port...")
                val connId = manager.connectToTcp(host, port, peerId)
                if (peerId != null) {
                    manager.sendJoin(peerId, connId)
                    handler.post { bindSession(peerId) }
                }
                postEvent("TCP", "Connected -> $connId")
            } catch (e: Exception) {
                postEvent("ERROR", "TCP connect failed: ${e.message}")
            }
        }
    }

    private fun connectBluetooth(mac: String, peerId: PeerId?) {
        backgroundExecutor.execute {
            try {
                postEvent("BT", "Connecting to $mac...")
                val connId = manager.connectToBluetooth(mac, peerId)
                postEvent("BT", "Connected -> $connId")
                if (peerId != null) {
                    handler.post { bindSession(peerId) }
                }
            } catch (e: Exception) {
                postEvent("ERROR", "BT connect failed: ${e.message}")
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun showBluetoothDeviceChooser() {
        val btAdapter = BluetoothAdapter.getDefaultAdapter()
        if (btAdapter == null || !btAdapter.isEnabled) {
            addErrorEvent("Bluetooth is not enabled on device")
            return
        }
        val pairedDevices = btAdapter.bondedDevices.toList()
        if (pairedDevices.isEmpty()) {
            addErrorEvent("No paired Bluetooth devices found")
            return
        }

        val deviceNames = pairedDevices.map { "${it.name} (${it.address})" }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Select Bluetooth Peer")
            .setItems(deviceNames) { _, which ->
                val device = pairedDevices[which]
                val targetPeer = connectedPeerId ?: PeerId.of("remote-bt-${device.address.replace(":", "").take(8)}")
                if (connectedPeerId == null) {
                    syntheticBtPeerId = targetPeer
                }
                connectBluetooth(device.address, targetPeer)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showDropTransportDialog() {
        val peer = connectedPeerId
        if (peer == null) {
            addErrorEvent("No active peer session to drop transport for")
            return
        }
        val schemes = arrayOf("TCP", "Bluetooth")
        AlertDialog.Builder(this)
            .setTitle("Drop Transport for ${peer.value()}")
            .setItems(schemes) { _, which ->
                val scheme = if (which == 0) "tcp" else "bluetooth"
                backgroundExecutor.execute {
                    val dropped = manager.dropTransport(peer, scheme)
                    if (dropped) {
                        postEvent("DROP", "Dropped $scheme path for ${peer.value()}")
                    } else {
                        postEvent("DROP", "No active $scheme path to drop")
                    }
                    handler.post { updateDashboard() }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun sendPayloadMessage(content: String) {
        val peer = connectedPeerId
        if (peer == null) {
            addErrorEvent("No connected peer session")
            return
        }

        backgroundExecutor.execute {
            try {
                val appMsg = manager.sendText(peer, content)
                val nowStr = timeFormatter.format(Date())
                postEvent("TX", "[YOU] $content")

                val isDelivered = manager.isDelivered(appMsg.messageId())
                val status = if (isDelivered) MessageDeliveryStatus.DELIVERED else MessageDeliveryStatus.DISPATCHED

                val steps = listOf(
                    MessageJourneyStep("Accepted", "Message accepted into outbox queue", nowStr, true),
                    MessageJourneyStep("Dispatched", "Dispatched over selected transport path", nowStr, true),
                    MessageJourneyStep("Delivered", "Delivered and confirmed", nowStr, isDelivered, !isDelivered)
                )

                val journey = MessageJourneyState(
                    messageId = appMsg.messageId(),
                    sequenceNumber = appMsg.sequenceNumber(),
                    destinationPeerId = peer.value(),
                    humanStatus = if (isDelivered) "âœ“ Delivered" else "â—Œ Sending...",
                    networkAwareStatus = "Dispatched via Active Route",
                    technicalSummary = "Message sequence=${appMsg.sequenceNumber()}; outbox tracked",
                    steps = steps,
                    forensicLog = listOf(
                        "$nowStr TX messageId=${appMsg.messageId()} dest=${peer.value()} seq=${appMsg.sequenceNumber()}"
                    )
                )

                val uiItem = UiMessageItem(
                    messageId = appMsg.messageId(),
                    senderPeerId = manager.localPeerId.value(),
                    senderDisplayName = "YOU",
                    content = content,
                    timestamp = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date()),
                    isOutgoing = true,
                    status = status,
                    journey = journey
                )

                handler.post {
                    uiMessagesList.add(uiItem)
                    while (uiMessagesList.size > 50) uiMessagesList.removeAt(0)
                    updateDashboard()
                }
            } catch (e: Exception) {
                postEvent("ERROR", "Send failed: ${e.message}")
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        backgroundExecutor.shutdown()
        manager.close()
    }
}