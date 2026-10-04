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
 * A.D2.2 ÃƒÂ¢Ã¢â€šÂ¬Ã¢â‚¬Â Surgically stabilized cockpit.
 * Fix 3: RX event now shows payload content from ApplicationMessageListener, not messageId from ProtocolListener.
 * Fix 2: BT connection defers bindSession until real PeerId arrives via JOIN.
 * Fix 4: Timestamp resolution improved to HH:mm:ss.SSS.
 */
class DiagnosticActivity : Activity() {

    private lateinit var manager: PravahAndroidMessagingManager
    private val handler = Handler(Looper.getMainLooper())
    private val backgroundExecutor = Executors.newSingleThreadExecutor()

    private var connectedPeerId: PeerId? = null
    private val liveEventsList = CopyOnWriteArrayList<LiveWireEvent>()

    // Fix 4: Sub-second timestamp resolution
    private val timeFormatter = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    // Fix 2: Track synthetic BT PeerId for cleanup after real JOIN
    private var syntheticBtPeerId: PeerId? = null
    private lateinit var activityProtocolListener: ProtocolListener

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

        nodeStatusPanel = NodeStatusPanel(tvStatus)
        networkSnapshotPanel = NetworkSnapshotPanel()
        peerPanel = PeerPanel()
        pathPanel = PathPanel()
        liveWirePanel = LiveWirePanel(tvLog)
        operationsPanel = OperationsPanel(
            btnStart, btnStop, btnDiscover, btnConnectTcp,
            btnConnectBt, btnSimulateDrop, etMessage, btnSend
        )

        val shortId = UUID.randomUUID().toString().substring(0, 8)
        val peerId = PeerId.of("android-$shortId")
        manager = PravahAndroidMessagingManager(peerId)

        addSystemEvent("Pravah Hybrid Diagnostic Node initialized. PeerId: ${peerId.value()}")
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
                    addSystemEvent("TCP already ACTIVE for ${peer.value()} ÃƒÂ¢Ã¢â€šÂ¬Ã¢â‚¬Â skipping")
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
            val content = etMessage.text.toString()
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

        // Protocol listener ÃƒÂ¢Ã¢â€šÂ¬Ã¢â‚¬Â Fix 3: NO RX event here.
        // RX is now posted from ApplicationMessageListener where payload content is available.
        activityProtocolListener = object : ProtocolListener {
            override fun onPeerJoined(peerIdStr: String, message: Message) {
                postEvent("JOIN", "Peer $peerIdStr joined")
                handler.post {
                    val realPeerId = PeerId.of(peerIdStr)

                    // Fix 2: Clean up synthetic BT peer if real identity differs
                    val synth = syntheticBtPeerId
                    if (synth != null && synth.value() != peerIdStr) {
                        try {
                            manager.connectivityRegistry.removePeer(synth)
                            addSystemEvent("Cleaned synthetic BT peer ${synth.value()} -> real $peerIdStr")
                        } catch (_: Exception) {
                            // Cleanup best-effort
                        }
                        syntheticBtPeerId = null
                    }

                    bindSession(realPeerId)
                }
            }

            override fun onMessageReceived(peerIdStr: String, message: Message) {
                // Fix 3: Do NOT post RX here. The raw protocol Message contains
                // messageId (UUID) and framed payload bytes, not human-readable content.
                // The decoded content is available in ApplicationMessageListener below.
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
        }

        // Fix 3: Application message listener ÃƒÂ¢Ã¢â€šÂ¬Ã¢â‚¬Â RX event posted HERE with real content.
        // DefaultApplicationMessagingService decodes the framed payload into
        // ApplicationMessage.content() before firing this callback.
        manager.coordinator.setProtocolListener(activityProtocolListener)
        manager.addMessageListener(ApplicationMessageListener { msg ->
            handler.post {
                val senderStr = msg.sender().value().take(16)
                val content = msg.content()
                postEvent("RX", "[$senderStr] $content")
                bindSession(msg.sender())
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
        val uiState = DiagnosticModelMapper.map(manager, connectedPeerId, liveEventsList.toList())
        nodeStatusPanel.render(uiState.nodeStatus)
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
                topoSb.append(pathPanel.formatDispatchRoute(peer.resolvedRoute))
            }
        } else {
            topoSb.append(" [No connected peer registrations]\n")
        }

        topoSb.append("\n-- TRANSITION BUFFER (B.R2) --\n")
        val bs = uiState.bufferState
        topoSb.append(String.format(" Queue: %-5d | Bytes: %-5d\n", bs.currentSize, bs.currentBytes))
        topoSb.append(String.format(" Buffered: %-4d | Flushed: %-4d\n", bs.totalBuffered, bs.totalFlushed))
        topoSb.append(String.format(" Expired: %-5d | Evicted: %-5d | Rejected: %d\n", bs.totalExpired, bs.totalEvicted, bs.totalRejected))

        tvMultiPathTopology.text = topoSb.toString()
    }

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
     * Fix 2: Bluetooth device selection with deferred identity binding.
     * Creates a temporary PeerId for the transport connection, but defers
     * bindSession() until onPeerJoined fires with the real PeerId from JOIN.
     * The synthetic peer is cleaned up in onPeerJoined when the real identity arrives.
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

        val deviceNames = bondedDevices.map { "${it.name ?: "Unknown"} [${it.address}]" }.toTypedArray()

        AlertDialog.Builder(this)
            .setTitle("Select Pravaah BT Peer")
            .setItems(deviceNames) { _, which ->
                val selectedDevice = bondedDevices[which]
                val tempPeerId = PeerId.of("remote-bt-${selectedDevice.address.replace(":", "").takeLast(6)}")

                // Fix 2: Store synthetic PeerId for later cleanup in onPeerJoined
                syntheticBtPeerId = tempPeerId
                addSystemEvent("User selected BT device: ${selectedDevice.name} [${selectedDevice.address}]")

                backgroundExecutor.execute {
                    try {
                        val connId = manager.connectToBluetooth(selectedDevice.address, tempPeerId)
                        handler.post {
                            addSystemEvent("Bluetooth channel active: $connId")
                            // Fix 2: Do NOT bindSession here. Wait for onPeerJoined
                            // with the real PeerId from the JOIN exchange.
                        }
                    } catch (e: Exception) {
                        handler.post { addErrorEvent("BT CONNECT ERROR: ${e.message}") }
                        syntheticBtPeerId = null
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showDropTransportDialog() {
        val peer = connectedPeerId ?: run {
            addErrorEvent("DROP ERROR: No connected peer")
            return
        }
        val transports = arrayOf("TCP", "BLUETOOTH")
        android.app.AlertDialog.Builder(this)
            .setTitle("SELECT TRANSPORT")
            .setItems(transports) { _, which ->
                when (which) {
                    0 -> simulateTransportDrop("tcp")
                    1 -> simulateTransportDrop("bluetooth")
                }
            }
            .setNegativeButton("CANCEL", null)
            .show()
    }

    private fun simulateTransportDrop(transportName: String) {
        val peer = connectedPeerId ?: return
        addSystemEvent("DROP requested target=$transportName")
        backgroundExecutor.execute {
            try {
                manager.connectivityRegistry.lookup(peer).ifPresent { conn ->
                    var found = false
                    for (path in conn.activePaths()) {
                        if (path.transportName().equals(transportName, ignoreCase = true)) {
                            conn.addPath(path.deactivate())
                            found = true
                            handler.post {
                                addSystemEvent("PATH: ${path.pathId().value()} ($transportName) ACTIVE->INACTIVE")
                                updateDashboard()
                            }
                        }
                    }
                    if (!found) {
                        handler.post {
                            addErrorEvent("DROP: No active $transportName path found")
                        }
                    }
                }
            } catch (e: Exception) {
                handler.post { addErrorEvent("DROP ERROR: ${e.message}") }
            }
        }
    }

    private fun simulateTcpDrop() {
        simulateTransportDrop("tcp")
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
        try {
            // Lifecycle safety: unregister listener on destroy to prevent stale callbacks
            val coord = manager.coordinator
            val removeMethod = coord.javaClass.getMethod("removeProtocolListener", ProtocolListener::class.java)
            removeMethod.invoke(coord, activityProtocolListener)
        } catch (_: Exception) {}
        backgroundExecutor.shutdown()
        manager.close()
    }
}