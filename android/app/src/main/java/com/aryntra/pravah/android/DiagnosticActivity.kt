package com.aryntra.pravah.android

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
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
import com.aryntra.pravah.messaging.ApplicationMessageListener
import com.aryntra.pravah.peer.PeerId
import java.util.UUID
import java.util.concurrent.Executors

/**
 * Modernized Diagnostic UI Entry Point (§2, §28).
 * Retains 100% of validated network core interactions while delegating rendering 
 * to decoupled presentation panels and clean mapping states.
 */
class DiagnosticActivity : Activity() {

    private lateinit var manager: PravahAndroidMessagingManager
    private val handler = Handler(Looper.getMainLooper())
    private val backgroundExecutor = Executors.newSingleThreadExecutor()

    private var connectedPeerId: PeerId? = null

    // Component Panels (§14)
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
        val scrollLog: ScrollView = findViewById(R.id.scrollLog)
        etMessage = findViewById(R.id.etMessage)

        val btnStart: Button = findViewById(R.id.btnStart)
        val btnStop: Button = findViewById(R.id.btnStop)
        val btnDiscover: Button = findViewById(R.id.btnDiscover)
        val btnConnectTcp: Button = findViewById(R.id.btnConnectTcp)
        val btnConnectBt: Button = findViewById(R.id.btnConnectBt)
        val btnSimulateDrop: Button = findViewById(R.id.btnSimulateDrop)
        val btnSend: Button = findViewById(R.id.btnSend)

        // 2. Initialize Components & Panels
        nodeStatusPanel = NodeStatusPanel(tvStatus)
        networkSnapshotPanel = NetworkSnapshotPanel()
        peerPanel = PeerPanel()
        pathPanel = PathPanel()
        liveWirePanel = LiveWirePanel(scrollLog, tvLog)
        operationsPanel = OperationsPanel(
            btnStart, btnStop, btnDiscover, btnConnectTcp, 
            btnConnectBt, btnSimulateDrop, etMessage, btnSend
        )

        // 3. Set up local Peer ID and runtime messaging context
        val shortId = UUID.randomUUID().toString().substring(0, 8)
        val peerId = PeerId.of("android-$shortId")
        manager = PravahAndroidMessagingManager(peerId)

        log("Pravah Hybrid Diagnostic Node initialized")
        log("PeerId: ${peerId.value()}")
        updateDashboard()

        // 4. Operations click bindings
        btnStart.setOnClickListener { startRuntime() }
        btnStop.setOnClickListener { stopRuntime() }
        btnDiscover.setOnClickListener { toggleDiscovery() }
        btnConnectTcp.setOnClickListener { connectTcp() }
        btnConnectBt.setOnClickListener { showBluetoothDeviceChooser() }
        btnSimulateDrop.setOnClickListener { simulateTcpDrop() }
        btnSend.setOnClickListener { sendMessage() }

        // 5. Setup unchanged messaging callbacks
        manager.addMessageListener(ApplicationMessageListener { msg ->
            handler.post {
                val sender = msg.sender()
                bindSession(sender)
                log("< RECV [${sender.value()}]: ${msg.content()}")
                updateDashboard()
            }
        })

        manager.setDiscoveryListener { peer ->
            handler.post {
                bindSession(peer.peerId())
                log("DISCOVERED: ${peer.peerId().value()} @ ${peer.hostAddress()}:${peer.port()}")
                updateDashboard()
            }
        }
    }

    private fun requestPermissionsIfRequired() {
        val permissions = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                permissions.add(Manifest.permission.BLUETOOTH_CONNECT)
            }
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) {
                permissions.add(Manifest.permission.BLUETOOTH_SCAN)
            }
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_ADVERTISE) != PackageManager.PERMISSION_GRANTED) {
                permissions.add(Manifest.permission.BLUETOOTH_ADVERTISE)
            }
        } else {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                permissions.add(Manifest.permission.ACCESS_FINE_LOCATION)
            }
        }
        if (permissions.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, permissions.toTypedArray(), 101)
        }
    }

    private fun bindSession(peer: PeerId) {
        connectedPeerId = peer
        updateDashboard()
    }

    private fun startRuntime() {
        backgroundExecutor.execute {
            try {
                manager.start()
                handler.post {
                    log("Runtime STARTED on TCP port ${manager.boundPort}")
                    updateDashboard()
                }
            } catch (e: Exception) {
                val err = e.message ?: e.javaClass.simpleName
                handler.post { log("ERROR starting: $err") }
            }
        }
    }

    private fun stopRuntime() {
        backgroundExecutor.execute {
            try {
                manager.stop()
                handler.post {
                    log("Runtime STOPPED")
                    connectedPeerId = null
                    updateDashboard()
                }
            } catch (e: Exception) {
                val err = e.message ?: e.javaClass.simpleName
                handler.post { log("ERROR stopping: $err") }
            }
        }
    }

    private fun toggleDiscovery() {
        backgroundExecutor.execute {
            if (manager.isDiscovering) {
                manager.stopDiscovery()
                handler.post {
                    log("Discovery STOPPED")
                    updateDashboard()
                }
            } else {
                manager.startDiscovery()
                handler.post {
                    log("Discovery STARTED on UDP ${manager.discoveryPort}")
                    updateDashboard()
                }
            }
        }
    }

    private fun connectTcp() {
        val peers = manager.discoveredPeers
        if (peers.isEmpty()) {
            log("No peers discovered yet via UDP. Please press DISCOVER first.")
            return
        }

        val target = peers.last()
        val host = target.hostAddress()
        val port = target.port()
        val targetPeerId = target.peerId()

        log("Connecting TCP to ${targetPeerId.value()} @ $host:$port...")
        backgroundExecutor.execute {
            try {
                val connId = manager.connectToTcp(host, port, targetPeerId)
                Thread.sleep(150)
                manager.sendJoin(targetPeerId, connId)
                handler.post {
                    log("TCP JOIN sent to ${targetPeerId.value()}")
                    bindSession(targetPeerId)
                    updateDashboard()
                }
                Thread.sleep(200)
                manager.replyJoin(targetPeerId)
            } catch (e: Exception) {
                val err = e.message ?: e.javaClass.simpleName
                handler.post { log("TCP CONNECT ERROR: $err") }
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun showBluetoothDeviceChooser() {
        val adapter = try { BluetoothAdapter.getDefaultAdapter() } catch (t: Throwable) { null }
        if (adapter == null || !adapter.isEnabled) {
            log("Bluetooth is turned OFF or unavailable on this device")
            return
        }

        val bondedDevices: Set<BluetoothDevice> = try { adapter.bondedDevices ?: emptySet() } catch (e: Exception) { emptySet() }
        val deviceList = bondedDevices.toList()

        if (deviceList.isEmpty()) {
            log("No paired Bluetooth devices found. Please pair both Android devices in Bluetooth Settings first.")
            return
        }

        val names = deviceList.map { "${it.name ?: "Unknown"} (${it.address})" }.toTypedArray()

        AlertDialog.Builder(this)
            .setTitle("Select Paired Bluetooth Peer")
            .setItems(names) { _, which ->
                val selectedDevice = deviceList[which]
                connectBtDevice(selectedDevice.address)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun connectBtDevice(macAddress: String) {
        val peers = manager.discoveredPeers
        val targetPeerId = connectedPeerId ?: if (peers.isNotEmpty()) peers.last().peerId() else PeerId.of("remote-bt-node")

        log("Connecting Bluetooth RFCOMM to $macAddress for ${targetPeerId.value()}...")
        backgroundExecutor.execute {
            try {
                val connId = manager.connectToBluetooth(macAddress, targetPeerId)
                handler.post {
                    log("Bluetooth path established: $connId")
                    bindSession(targetPeerId)
                    updateDashboard()
                }
            } catch (e: Exception) {
                val err = e.message ?: e.javaClass.simpleName
                handler.post { log("BT CONNECT NOTICE: $err") }
            }
        }
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
                                log("SIMULATED TCP FAILURE: Path ${path.pathId().value()} DEACTIVATED")
                                updateDashboard()
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                handler.post { log("DROP ERROR: ${e.message}") }
            }
        }
    }

    private fun sendMessage() {
        val text = etMessage.text.toString().trim()
        val peer = connectedPeerId
        if (text.isEmpty() || peer == null) return

        backgroundExecutor.execute {
            try {
                val activeConn = try { manager.router.resolveConnectionId(peer) } catch (e: Exception) { "unresolved" }
                val msg = manager.sendText(peer, text)
                handler.post {
                    log("> SENT via [$activeConn]: $text")
                    etMessage.setText("")
                    updateDashboard()
                }
                var delivered = false
                for (i in 1..15) {
                    Thread.sleep(200)
                    if (manager.isDelivered(msg.messageId())) {
                        delivered = true
                        break
                    }
                }
                handler.post {
                    if (delivered) {
                        log("ACK received -> DELIVERED")
                    } else {
                        log("Dispatched to router -> Pending ACK")
                    }
                }
            } catch (e: Exception) {
                val err = e.message ?: e.javaClass.simpleName
                handler.post { log("SEND ERROR: $err") }
            }
        }
    }

    /**
     * Reorganized updateDashboard implementation (§7, §14, §15).
     * Compiles manager state, requests standard mapping models, and delegates
     * styling outputs across component panel domains.
     */
    private fun updateDashboard() {
        // 1. Build immutable UI state from Core & Manager State
        val uiState = DiagnosticModelMapper.map(manager, connectedPeerId)

        // 2. Render Node Status Section
        nodeStatusPanel.render(uiState.nodeStatus)

        // 3. Render Button/Interactive Control States
        operationsPanel.render(uiState.operations)

        // 4. Construct Multi-path topology representation dynamically
        val topoSb = StringBuilder()

        // Network snapshot summary header (§9)
        topoSb.append("--- NETWORK SNAPSHOT ---\n")
        topoSb.append(networkSnapshotPanel.formatTelemetry(uiState.snapshot)).append("\n\n")

        if (uiState.peers.isNotEmpty()) {
            for (peer in uiState.peers) {
                // Formatting peer details (§10)
                topoSb.append(peerPanel.formatPeerHeader(peer))

                // Group associated sub-paths (§11)
                val associatedPaths = uiState.paths.filter { it.peerId == peer.peerId }
                for (path in associatedPaths) {
                    topoSb.append(pathPanel.formatPath(path))
                }

                // Dispatch line resolution
                topoSb.append(pathPanel.formatDispatchRoute(peer.resolvedRoute))
            }
        } else {
            topoSb.append("No active peer paths.")
        }

        tvMultiPathTopology.text = topoSb.toString().trim()
    }

    private fun log(msg: String) {
        liveWirePanel.log(msg)
    }

    override fun onDestroy() {
        super.onDestroy()
        backgroundExecutor.shutdown()
        manager.close()
    }
}
