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
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.aryntra.pravah.android.presentation.*
import com.aryntra.pravah.android.state.DiagnosticModelMapper
import com.aryntra.pravah.android.state.LiveWireEvent
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
 * A.D2.1 — Stabilized Live Network Cockpit.
 * Fixes: BT device selection (A), scrolling (B), TCP idempotency (D),
 *        peer identity (F), topology label (G).
 */
class DiagnosticActivity : Activity() {

    private lateinit var manager: PravahAndroidMessagingManager
    private val handler = Handler(Looper.getMainLooper())
    private val backgroundExecutor = Executors.newSingleThreadExecutor()

    private var connectedPeerId: PeerId? = null
    private val liveEventsList = CopyOnWriteArrayList<LiveWireEvent>()
    private val timeFormatter = SimpleDateFormat("HH:mm:ss", Locale.US)

    // Component Panels
    private lateinit var nodeStatusPanel: NodeStatusPanel
    private lateinit var networkSnapshotPanel: NetworkSnapshotPanel
    private lateinit var peerPanel: PeerPanel
    private lateinit var pathPanel: PathPanel
    private lateinit var liveWirePanel: LiveWirePanel
    private lateinit var operationsPanel: OperationsPanel

    private lateinit var tvMultiPathTopology: TextView
    private lateinit var etMessage: EditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_diagnostic)

        requestPermissionsIfRequired()

        // 1. Core UI Elements Extraction
        val tvStatus: TextView = findViewById(R.id.tvStatus)
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

        // 2. Initialize Components & Panels
        // A.D2.1: LiveWirePanel no longer needs ScrollView (outer ScrollView handles it)
        nodeStatusPanel = NodeStatusPanel(tvStatus)
        networkSnapshotPanel = NetworkSnapshotPanel()
        peerPanel = PeerPanel()
        pathPanel = PathPanel()
        liveWirePanel = LiveWirePanel(tvLog)
        operationsPanel = OperationsPanel(
            btnStart, btnStop, btnDiscover, btnConnectTcp,
            btnConnectBt, btnSimulateDrop, etMessage, btnSend
        )

        // 3. Set up local Peer ID and runtime messaging context
        val shortId = UUID.randomUUID().toString().substring(0, 8)
        val peerId = PeerId.of("android-$shortId")
        manager = PravahAndroidMessagingManager(peerId)

        addSystemEvent("Pravah Hybrid Diagnostic Node initialized. PeerId: ${peerId.value()}")
        updateDashboard()

        // 4. Operations click bindings
        btnStart.setOnClickListener { startRuntime() }
        btnStop.setOnClickListener { stopRuntime() }
        btnDiscover.setOnClickListener {
            if (manager.isDiscovering) stopDiscovery() else startDiscovery()
        }
        btnConnectTcp.setOnClickListener {
            val disc = manager.discoveredPeers.firstOrNull()
            if (disc != null) {
                // A.D2.1 Fix D: Guard against duplicate TCP connect
                val peer = disc.peerId()
                val hasActiveTcp = manager.connectivityRegistry.lookup(peer).map { conn ->
                    conn.activePaths().any { it.transportName().equals("tcp", ignoreCase = true) }
                }.orElse(false)

                if (hasActiveTcp) {
                    addSystemEvent("TCP already ACTIVE for ${peer.value()} — skipping duplicate connect")
                } else {
                    connectTcp(disc.hostAddress(), disc.port(), peer)
                }
            } else {
                addErrorEvent("No discovered peers to connect TCP")
            }
        }
        btnConnectBt.setOnClickListener { showBluetoothDeviceChooser() }
        btnSimulateDrop.setOnClickListener { simulateTcpDrop() }
        btnSend.setOnClickListener {
            val content = etMessage.text.toString()
            if (content.isNotEmpty()) {
                sendPayloadMessage(content)
                etMessage.setText("")
            }
        }

        // 5. Setup Live Path State Transitions Listener
        manager.connectivityRegistry.addPathStateListener(PathStateListener { _, path, prev ->
            postEvent("PATH", "${path.transportName()} ${prev}->${path.state()}")
            val tb = manager.router.transitionBuffer()
            if (tb != null && tb.size() > 0) {
                postEvent("BUFFER", "Holding ${tb.size()} msgs (${tb.currentBytes()}B)")
            }
        })

        // 6. Setup Protocol Engine Listeners
        manager.coordinator.setProtocolListener(object : ProtocolListener {
            override fun onPeerJoined(peerIdStr: String, message: Message) {
                postEvent("JOIN", "Peer $peerIdStr joined")
                handler.post { bindSession(PeerId.of(peerIdStr)) }
            }
            override fun onMessageReceived(peerIdStr: String, message: Message) {
                postEvent("RX", "[${peerIdStr.take(16)}] ${message.messageId()}")
            }
            override fun onPeerLeft(peerIdStr: String, message: Message) {
                postEvent("LEFT", "Peer $peerIdStr left")
                handler.post {
                    if (connectedPeerId?.value() == peerIdStr) {
                        connectedPeerId = null
                        updateDashboard()
                    }
                }
            }
        })

        // 7. Application payload listener
        manager.addMessageListener(ApplicationMessageListener { msg ->
            handler.post {
                bindSession(msg.sender())
                updateDashboard()
            }
        })

        // 8. Discovery listener
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
        val uiState = DiagnosticModelMapper.map(manager, connectedPeerId, liveEventsList.toList())
        nodeStatusPanel.render(uiState.nodeStatus)
        operationsPanel.render(uiState.operations)

        val topoSb = StringBuilder()
        topoSb.append(networkSnapshotPanel.renderAsciiTopology(uiState.topology))
        topoSb.append("\n── PATH CONNECTIONS ──\n")
        if (uiState.peers.isNotEmpty()) {
            for (peer in uiState.peers) {
                topoSb.append(peerPanel.formatPeerHeader(peer))
                uiState.paths.filter { it.peerId == peer.peerId }.forEach { path ->
                    topoSb.append(pathPanel.formatPath(path))
                }
                topoSb.append(pathPanel.formatDispatchRoute(peer.resolvedRoute))
            }
        } else {
            topoSb.append(" [No connected peer registrations]\n")
        }

        topoSb.append("\n── TRANSITION BUFFER (B.R2) ──\n")
        val bs = uiState.bufferState
        topoSb.append(String.format(" Queue: %-5d | Bytes: %-5d\n", bs.currentSize, bs.currentBytes))
        topoSb.append(String.format(" Buffered: %-4d | Flushed: %-4d\n", bs.totalBuffered, bs.totalFlushed))
        topoSb.append(String.format(" Expired: %-5d | Evicted: %-5d | Rejected: %d\n", bs.totalExpired, bs.totalEvicted, bs.totalRejected))

        tvMultiPathTopology.text = topoSb.toString()
    }

    // --- Runtime Operations ---

    private fun startRuntime() {
        backgroundExecutor.execute {
            try {
                manager.start()
                handler.post { addSystemEvent("Runtime STARTED on TCP port ${manager.boundPort}") }
            } catch (e: Exception) {
                handler.post { addErrorEvent("Start error: ${e.message}") }
            }
        }
    }

    private fun stopRuntime() {
        backgroundExecutor.execute {
            try {
                manager.stop()
                handler.post { addSystemEvent("Runtime STOPPED"); updateDashboard() }
            } catch (e: Exception) {
                handler.post { addErrorEvent("Stop error: ${e.message}") }
            }
        }
    }

    private fun startDiscovery() {
        backgroundExecutor.execute {
            try {
                manager.startDiscovery()
                handler.post { addSystemEvent("UDP Discovery STARTED (Port: ${manager.discoveryPort})") }
            } catch (e: Exception) {
                handler.post { addErrorEvent("Discovery error: ${e.message}") }
            }
        }
    }

    private fun stopDiscovery() {
        backgroundExecutor.execute {
            try {
                manager.stopDiscovery()
                handler.post { addSystemEvent("UDP Discovery STOPPED") }
            } catch (e: Exception) {
                handler.post { addErrorEvent("Discovery stop error: ${e.message}") }
            }
        }
    }

    private fun connectTcp(host: String, port: Int, targetPeerId: PeerId) {
        backgroundExecutor.execute {
            try {
                handler.post { addSystemEvent("Connecting TCP to ${targetPeerId.value()}...") }
                val connId = manager.connectToTcp(host, port, targetPeerId)
                handler.post { addSystemEvent("TCP socket active: $connId") }
                Thread.sleep(200)
                manager.sendJoin(targetPeerId, connId)
                handler.post { addSystemEvent("TCP JOIN sent to ${targetPeerId.value()}"); bindSession(targetPeerId) }
                Thread.sleep(200)
                manager.replyJoin(targetPeerId)
            } catch (e: Exception) {
                handler.post { addErrorEvent("TCP CONNECT ERROR: ${e.message}") }
            }
        }
    }

    /**
     * A.D2.1 Fix A: Bluetooth device selection via AlertDialog.
     * Prevents accidental connection to non-Pravaah bonded devices
     * (earbuds, speakers, etc.) by requiring explicit user selection.
     */
    @SuppressLint("MissingPermission")
    private fun showBluetoothDeviceChooser() {
        @Suppress("DEPRECATION")
        val adapter = try { BluetoothAdapter.getDefaultAdapter() } catch (t: Throwable) { null }
        if (adapter == null || !adapter.isEnabled) {
            addErrorEvent("Bluetooth adapter unavailable or disabled")
            return
        }

        val bondedDevices = adapter.bondedDevices.toList()
        if (bondedDevices.isEmpty()) {
            addErrorEvent("No paired Bluetooth devices found")
            return
        }

        // A.D2.1: Present explicit device selection dialog
        val deviceNames = bondedDevices.map { "${it.name ?: "Unknown"} [${it.address}]" }.toTypedArray()

        AlertDialog.Builder(this)
            .setTitle("Select Pravaah BT Peer")
            .setItems(deviceNames) { _, which ->
                val selectedDevice = bondedDevices[which]
                val targetPeerId = PeerId.of("remote-bt-${selectedDevice.address.replace(":", "").takeLast(6)}")
                addSystemEvent("User selected BT device: ${selectedDevice.name} [${selectedDevice.address}]")

                backgroundExecutor.execute {
                    try {
                        val connId = manager.connectToBluetooth(selectedDevice.address, targetPeerId)
                        handler.post {
                            addSystemEvent("Bluetooth channel active: $connId")
                            bindSession(targetPeerId)
                        }
                    } catch (e: Exception) {
                        handler.post { addErrorEvent("BT CONNECT ERROR: ${e.message}") }
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun simulateTcpDrop() {
        val peer = connectedPeerId ?: return
        backgroundExecutor.execute {
            try {
                manager.connectivityRegistry.lookup(peer).ifPresent { conn ->
                    for (path in conn.activePaths()) {
                        if (path.transportName().equals("tcp", ignoreCase = true)) {
                            conn.addPath(path.deactivate())
                            handler.post {
                                addSystemEvent("SIMULATED TCP FAILURE: ${path.pathId().value()} DEACTIVATED")
                                updateDashboard()
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                handler.post { addErrorEvent("DROP ERROR: ${e.message}") }
            }
        }
    }

    private fun sendPayloadMessage(content: String) {
        val destination = connectedPeerId ?: return
        backgroundExecutor.execute {
            try {
                manager.sendText(destination, content)
                handler.post { postEvent("TX", "[${destination.value().take(16)}] $content") }
            } catch (e: Exception) {
                handler.post { addErrorEvent("SEND ERROR: ${e.message}") }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        backgroundExecutor.shutdown()
        manager.close()
    }
}
