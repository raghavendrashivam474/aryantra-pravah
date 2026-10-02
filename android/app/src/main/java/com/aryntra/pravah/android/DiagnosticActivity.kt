package com.aryntra.pravah.android

import android.app.Activity
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Button
import android.widget.EditText
import android.widget.ScrollView
import android.widget.TextView
import com.aryntra.pravah.messaging.ApplicationMessageListener
import com.aryntra.pravah.peer.PeerId
import com.aryntra.pravah.protocol.PeerState
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.concurrent.Executors

class DiagnosticActivity : Activity() {

    private lateinit var manager: PravahAndroidMessagingManager
    private val handler = Handler(Looper.getMainLooper())
    private val backgroundExecutor = Executors.newSingleThreadExecutor()
    private val timeFmt = SimpleDateFormat("HH:mm:ss", Locale.US)
    private var connectedPeerId: PeerId? = null

    private lateinit var tvStatus: TextView
    private lateinit var tvLog: TextView
    private lateinit var scrollLog: ScrollView
    private lateinit var etMessage: EditText
    private lateinit var btnStart: Button
    private lateinit var btnStop: Button
    private lateinit var btnDiscover: Button
    private lateinit var btnConnect: Button
    private lateinit var btnSend: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_diagnostic)

        tvStatus = findViewById(R.id.tvStatus)
        tvLog = findViewById(R.id.tvLog)
        scrollLog = findViewById(R.id.scrollLog)
        etMessage = findViewById(R.id.etMessage)
        btnStart = findViewById(R.id.btnStart)
        btnStop = findViewById(R.id.btnStop)
        btnDiscover = findViewById(R.id.btnDiscover)
        btnConnect = findViewById(R.id.btnConnect)
        btnSend = findViewById(R.id.btnSend)

        val shortId = UUID.randomUUID().toString().substring(0, 8)
        val peerId = PeerId.of("android-$shortId")
        manager = PravahAndroidMessagingManager(peerId)

        log("Pravah Diagnostic Node created")
        log("PeerId: ${peerId.value()}")
        updateStatus()

        btnStart.setOnClickListener { startRuntime() }
        btnStop.setOnClickListener { stopRuntime() }
        btnDiscover.setOnClickListener { toggleDiscovery() }
        btnConnect.setOnClickListener { connectToFirstPeer() }
        btnSend.setOnClickListener { sendMessage() }

        // Inbound message listener: automatically binds session & enables UI on receiving peer
        manager.addMessageListener(ApplicationMessageListener { msg ->
            handler.post {
                val sender = msg.sender()
                bindSession(sender)
                log("< RECV [${sender.value()}]: ${msg.content()}")
                updateStatus()
            }
        })

        manager.setDiscoveryListener { peer ->
            handler.post {
                log("DISCOVERED: ${peer.peerId().value()} @ ${peer.hostAddress()}:${peer.port()}")
                btnConnect.isEnabled = true
            }
        }
    }

    private fun bindSession(peer: PeerId) {
        connectedPeerId = peer
        btnSend.isEnabled = true
        etMessage.isEnabled = true
    }

    private fun startRuntime() {
        backgroundExecutor.execute {
            try {
                manager.start()
                handler.post {
                    log("Runtime STARTED on port ${manager.boundPort}")
                    btnStart.isEnabled = false
                    btnStop.isEnabled = true
                    btnDiscover.isEnabled = true
                    updateStatus()
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
                    btnStart.isEnabled = true
                    btnStop.isEnabled = false
                    btnDiscover.isEnabled = false
                    btnConnect.isEnabled = false
                    btnSend.isEnabled = false
                    etMessage.isEnabled = false
                    connectedPeerId = null
                    updateStatus()
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
                    btnDiscover.text = "DISCOVER"
                    updateStatus()
                }
            } else {
                manager.startDiscovery()
                handler.post {
                    log("Discovery STARTED on UDP ${manager.discoveryPort}")
                    btnDiscover.text = "STOP DISC"
                    updateStatus()
                }
            }
        }
    }

    private fun connectToFirstPeer() {
        val peers = manager.discoveredPeers
        if (peers.isEmpty()) {
            log("No peers discovered yet")
            return
        }
        val target = peers[0]
        val targetPeerId = target.peerId()
        log("Connecting to ${targetPeerId.value()} @ ${target.hostAddress()}:${target.port()}...")

        backgroundExecutor.execute {
            try {
                manager.connectTo(target.hostAddress(), target.port())
                Thread.sleep(150)
                val connId = "${target.hostAddress()}:${target.port()}"
                
                // 1. Send outbound JOIN
                manager.sendJoin(targetPeerId, connId)
                handler.post { log("JOIN sent to ${targetPeerId.value()}") }

                // 2. Reply JOIN so remote peer also establishes session
                Thread.sleep(200)
                manager.replyJoin(targetPeerId)
                handler.post {
                    log("JOIN reply sent to ${targetPeerId.value()}")
                    bindSession(targetPeerId)
                }

                Thread.sleep(300)
                val state = manager.getSessionState(targetPeerId.value())
                handler.post {
                    log("Session state: ${state ?: PeerState.JOINED}")
                    updateStatus()
                }
            } catch (e: Exception) {
                val err = e.message ?: e.javaClass.simpleName
                handler.post { log("CONNECT ERROR: $err") }
            }
        }
    }

    private fun sendMessage() {
        val text = etMessage.text.toString().trim()
        val peer = connectedPeerId
        if (text.isEmpty() || peer == null) return

        backgroundExecutor.execute {
            try {
                val msg = manager.sendText(peer, text)
                handler.post {
                    log("> SENT: $text")
                    etMessage.setText("")
                }

                // Poll for ACK confirmation
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
                        log("Dispatched to router.")
                    }
                }
            } catch (e: Exception) {
                val err = e.message ?: e.javaClass.simpleName
                handler.post { log("SEND ERROR: $err") }
            }
        }
    }

    private fun updateStatus() {
        val state = if (manager.isRunning) "RUNNING" else "STOPPED"
        val port = if (manager.isRunning) manager.boundPort.toString() else "-"
        val disc = if (manager.isDiscovering) "ACTIVE" else "OFF"
        val conn = connectedPeerId?.let {
            val s = manager.getSessionState(it.value()) ?: PeerState.JOINED
            "${it.value()} [$s]"
        } ?: "NONE"
        tvStatus.text = "Status: $state | Port: $port\nPeerId: ${manager.localPeerId.value()}\nDiscovery: $disc | Session: $conn"
    }

    private fun log(msg: String) {
        val ts = timeFmt.format(Date())
        tvLog.append("[$ts] $msg\n")
        scrollLog.post { scrollLog.fullScroll(ScrollView.FOCUS_DOWN) }
    }

    override fun onDestroy() {
        super.onDestroy()
        backgroundExecutor.shutdown()
        manager.close()
    }
}
