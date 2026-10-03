# A.D1 Block 2 — Deep Inspection Report
Generated: 2026-10-04 00:43:30

# DiagnosticActivity.kt

## Class Declaration
```kotlin
class DiagnosticActivity : Activity() {
``n
## Properties (50)
| Modifier | Name | Type Hint |
|----------|------|-----------|
| private | `handler` | inferred |
| private | `backgroundExecutor` | inferred |
| private | `timeFmt` | mm |
| private | `connectedPeerId` | PeerId |
| public | `shortId` | inferred |
| public | `peerId` | inferred |
| public | `sender` | inferred |
| public | `permissions` | inferred |
| public | `err` | inferred |
| public | `err` | inferred |
| public | `peers` | inferred |
| public | `target` | inferred |
| public | `host` | inferred |
| public | `port` | inferred |
| public | `targetPeerId` | inferred |
| public | `connId` | inferred |
| public | `err` | inferred |
| public | `adapter` | Throwable |
| public | `bondedDevices` | Set |
| public | `deviceList` | inferred |
| public | `names` | inferred |
| public | `selectedDevice` | inferred |
| public | `peers` | inferred |
| public | `targetPeerId` | if |
| public | `connId` | inferred |
| public | `err` | inferred |
| public | `peer` | return |
| public | `text` | inferred |
| public | `peer` | inferred |
| public | `activeConn` | Exception |
| public | `msg` | inferred |
| public | `delivered` | inferred |
| public | `err` | inferred |
| public | `state` | inferred |
| public | `port` | inferred |
| public | `disc` | inferred |
| public | `peerStr` | inferred |
| public | `sessionStr` | PeerState |
| public | `topoSb` | inferred |
| public | `allConnectivities` | inferred |
| public | `tcpPath` | inferred |
| public | `btPath` | inferred |
| public | `statusSymbol` | inferred |
| public | `cleanConn` | inferred |
| public | `statusSymbol` | inferred |
| public | `rawConn` | inferred |
| public | `cleanConn` | inferred |
| public | `selected` | Exception |
| public | `cleanSelected` | inferred |
| public | `ts` | inferred |

## Functions (14)
| Signature | Line |
|-----------|------|
| `override fun onCreate(savedInstanceState: Bundle?) ` | L51 |
| `private fun requestPermissionsIfRequired() ` | L107 |
| `private fun bindSession(peer: PeerId) ` | L129 |
| `private fun startRuntime() ` | L138 |
| `private fun stopRuntime() ` | L157 |
| `private fun toggleDiscovery() ` | L181 |
| `private fun connectTcp() ` | L201 |
| `private fun showBluetoothDeviceChooser() ` | L234 |
| `private fun connectBtDevice(macAddress: String) ` | L261 |
| `private fun simulateTcpDrop() ` | L281 |
| `private fun sendMessage() ` | L302 |
| `private fun updateDashboard() ` | L338 |
| `private fun log(msg: String) ` | L384 |
| `override fun onDestroy() ` | L390 |

## Lifecycle Methods (2)
- L51: `override fun onCreate(savedInstanceState: Bundle?) {`
- L390: `override fun onDestroy() {`

## Listener/Callback Registrations (20)
- L66: `btnConnectTcp = findViewById(R.id.btnConnectTcp)`
- L67: `btnConnectBt = findViewById(R.id.btnConnectBt)`
- L79: `btnStart.setOnClickListener { startRuntime() }`
- L80: `btnStop.setOnClickListener { stopRuntime() }`
- L81: `btnDiscover.setOnClickListener { toggleDiscovery() }`
- L82: `btnConnectTcp.setOnClickListener { connectTcp() }`
- L83: `btnConnectBt.setOnClickListener { showBluetoothDeviceChooser() }`
- L84: `btnSimulateDrop.setOnClickListener { simulateTcpDrop() }`
- L85: `btnSend.setOnClickListener { sendMessage() }`
- L108: `val permissions = mutableListOf<String>()`
- L130: `connectedPeerId = peer`
- L171: `connectedPeerId = null`
- L216: `val connId = manager.connectToTcp(host, port, targetPeerId)`
- L268: `val connId = manager.connectToBluetooth(macAddress, targetPeerId)`
- L309: `val activeConn = try { manager.router.resolveConnectionId(peer) } catch (e: Exception) { "unresolved" }`
- L343: `val sessionStr = connectedPeerId?.let { manager.getSessionState(it.value()) ?: PeerState.JOINED } ?: "-"`
- L348: `val allConnectivities = manager.connectivityRegistry.allConnectivities()`
- L361: `val cleanConn = btPath.optionalConnectionId().map { if (it.startsWith("/")) it.substring(1) else it }.orElse("no-conn")`
- L368: `val rawConn = tcpPath.optionalConnectionId().orElse("no-conn")`
- L369: `val cleanConn = if (rawConn.startsWith("bt:")) "no-conn" else (if (rawConn.startsWith("/")) rawConn.substring(1) else rawConn)`

## UI View References (12)
- L57: `tvStatus = findViewById(R.id.tvStatus)`
- L58: `tvMultiPathTopology = findViewById(R.id.tvMultiPathTopology)`
- L59: `tvLog = findViewById(R.id.tvLog)`
- L60: `scrollLog = findViewById(R.id.scrollLog)`
- L61: `etMessage = findViewById(R.id.etMessage)`
- L63: `btnStart = findViewById(R.id.btnStart)`
- L64: `btnStop = findViewById(R.id.btnStop)`
- L65: `btnDiscover = findViewById(R.id.btnDiscover)`
- L66: `btnConnectTcp = findViewById(R.id.btnConnectTcp)`
- L67: `btnConnectBt = findViewById(R.id.btnConnectBt)`
- L68: `btnSimulateDrop = findViewById(R.id.btnSimulateDrop)`
- L69: `btnSend = findViewById(R.id.btnSend)`

## Core/Manager Boundary Calls (9)
- L1: `package com.aryntra.pravah.android`
- L20: `import com.aryntra.pravah.messaging.ApplicationMessageListener`
- L21: `import com.aryntra.pravah.peer.PeerId`
- L22: `import com.aryntra.pravah.protocol.PeerState`
- L285: `manager.connectivityRegistry.lookup(peer).ifPresent { conn ->`
- L309: `val activeConn = try { manager.router.resolveConnectionId(peer) } catch (e: Exception) { "unresolved" }`
- L348: `val allConnectivities = manager.connectivityRegistry.allConnectivities()`
- L349: `.filter { it.peerId().value() != "remote-bt-node" || manager.connectivityRegistry.allConnectivities().size == 1 }`
- L374: `val selected = try { manager.router.resolveConnectionId(conn.peerId()) } catch (e: Exception) { "none" }`

## UI Thread Dispatch Points (17)
- L88: `handler.post {`
- L97: `handler.post {`
- L142: `handler.post {`
- L152: `handler.post { log("ERROR starting: $err") }`
- L161: `handler.post {`
- L176: `handler.post { log("ERROR stopping: $err") }`
- L185: `handler.post {`
- L192: `handler.post {`
- L219: `handler.post {`
- L228: `handler.post { log("TCP CONNECT ERROR: $err") }`
- L269: `handler.post {`
- L276: `handler.post { log("BT CONNECT NOTICE: $err") }`
- L289: `handler.post {`
- L297: `handler.post { log("DROP ERROR: ${e.message}") }`
- L311: `handler.post {`
- L324: `handler.post {`
- L333: `handler.post { log("SEND ERROR: $err") }`


---
# PravahAndroidMessagingManager.kt

## Class Declaration
```kotlin
class PravahAndroidMessagingManager(
``n
## Properties (39)
| Modifier | Name | Type Hint |
|----------|------|-----------|
| public | `localPeerId` | PeerId |
| public | `host` | String |
| public | `port` | Int |
| public | `discoveryPort` | Int |
| public | `customTransport` | Transport |
| public | `localMacAddress` | String |
| private | `logger` | class |
| public | `tcpTransport` | TcpTransport |
| public | `bluetoothTransport` | AndroidBluetoothRfcommTransport |
| public | `compositeTransport` | Transport |
| public | `registry` | inferred |
| public | `presenceManager` | inferred |
| public | `connectivityRegistry` | inferred |
| public | `presenceBridge` | inferred |
| public | `pathPolicy` | PathSelectionPolicy |
| public | `coordinator` | PeerConnectionCoordinator |
| private | `downstreamListener` | ProtocolListener |
| public | `remotePeer` | inferred |
| public | `connId` | inferred |
| public | `remotePeer` | inferred |
| public | `router` | inferred |
| public | `historyStore` | MessageHistoryStore |
| public | `outbox` | DeliveryOutbox |
| public | `messaging` | inferred |
| private | `discoveryEngine` | LanPeerDiscovery |
| private | `_discoveredPeers` | inferred |
| public | `discoveredPeers` | List |
| private | `discoveryListener` | inferred |
| public | `boundPort` | Int |
| public | `isRunning` | Boolean |
| public | `tcpPort` | inferred |
| public | `isDiscovering` | Boolean |
| public | `connId` | inferred |
| public | `cleanMac` | inferred |
| public | `connId` | inferred |
| public | `cleanConn` | inferred |
| public | `joinMsg` | inferred |
| public | `joinMsg` | inferred |
| public | `tempBtPeer` | inferred |

## Functions (22)
| Signature | Line |
|-----------|------|
| `override fun setProtocolListener(listener: ProtocolListener?) ` | L64 |
| `override fun onPeerJoined(peerIdStr: String, message: Message) ` | L67 |
| `override fun onMessageReceived(peerIdStr: String, message: Message) ` | L91 |
| `override fun onPeerLeft(peerIdStr: String, message: Message) ` | L99 |
| `fun start() ` | L121 |
| `fun stop() ` | L126 |
| `fun startDiscovery() ` | L133 |
| `override fun onPeerDiscovered(peer: DiscoveredPeer) ` | L138 |
| `fun stopDiscovery() ` | L152 |
| `fun setDiscoveryListener(listener: (DiscoveredPeer) -> Unit) ` | L159 |
| `fun connectToTcp(remoteHost: String, remotePort: Int, remotePeerId: PeerId? = null): String ` | L163 |
| `fun connectTo(remoteHost: String, remotePort: Int) ` | L172 |
| `fun connectToBluetooth(remoteMac: String, remotePeerId: PeerId? = null): String ` | L176 |
| `fun sendJoin(remotePeerId: PeerId, connectionId: String) ` | L191 |
| `fun replyJoin(remotePeerId: PeerId) ` | L202 |
| `fun getSessionState(peerIdValue: String): PeerState? ` | L214 |
| `fun isDelivered(messageId: String): Boolean ` | L218 |
| `fun sendText(destination: PeerId, content: String): ApplicationMessage ` | L222 |
| `fun addMessageListener(listener: ApplicationMessageListener) ` | L226 |
| `private fun cleanConnId(connId: String): String ` | L230 |
| `private fun cleanOrphanedBtNode(authenticatedPeer: PeerId) ` | L234 |
| `override fun close() ` | L248 |

## Lifecycle Methods (0)

## Listener/Callback Registrations (8)
- L56: `val connectivityRegistry = PeerConnectivityRegistry()`
- L59: `val pathPolicy: PathSelectionPolicy = PathSelectionPolicy.preferSchemes("tcp", "bluetooth", "bt")`
- L72: `val connId = cleanConnId(rawConnId)`
- L137: `registerListener(object : PeerDiscoveryListener {`
- L165: `val connId = "$remoteHost:$remotePort"`
- L179: `val connId = "bt:$cleanMac"`
- L192: `val cleanConn = cleanConnId(connectionId)`
- L227: `messaging.addListener(listener)`

## UI View References (0)

## Core/Manager Boundary Calls (44)
- L1: `package com.aryntra.pravah.android`
- L3: `import com.aryntra.pravah.android.bluetooth.AndroidBluetoothRfcommTransport`
- L4: `import com.aryntra.pravah.connectivity.ConnectivityPath`
- L5: `import com.aryntra.pravah.connectivity.EndpointAddress`
- L6: `import com.aryntra.pravah.connectivity.PathId`
- L7: `import com.aryntra.pravah.connectivity.PathSelectionPolicy`
- L8: `import com.aryntra.pravah.connectivity.PeerConnectivityRegistry`
- L9: `import com.aryntra.pravah.messaging.ApplicationMessage`
- L10: `import com.aryntra.pravah.messaging.ApplicationMessageListener`
- L11: `import com.aryntra.pravah.messaging.DefaultApplicationMessagingService`
- L12: `import com.aryntra.pravah.messaging.InMemoryMessageHistoryStore`
- L13: `import com.aryntra.pravah.messaging.MessageHistoryStore`
- L14: `import com.aryntra.pravah.messaging.reliability.DeliveryOutbox`
- L15: `import com.aryntra.pravah.messaging.reliability.InMemoryDeliveryOutbox`
- L16: `import com.aryntra.pravah.messaging.reliability.OutboxState`
- L17: `import com.aryntra.pravah.peer.PeerConnectionCoordinator`
- L18: `import com.aryntra.pravah.peer.PeerId`
- L19: `import com.aryntra.pravah.peer.PeerRegistry`
- L20: `import com.aryntra.pravah.peer.PeerRouter`
- L21: `import com.aryntra.pravah.peer.discovery.DiscoveredPeer`
- L22: `import com.aryntra.pravah.peer.discovery.LanPeerDiscovery`
- L23: `import com.aryntra.pravah.peer.discovery.PeerDiscoveryListener`
- L24: `import com.aryntra.pravah.peer.presence.PeerPresenceBridge`
- L25: `import com.aryntra.pravah.peer.presence.PeerPresenceManager`
- L26: `import com.aryntra.pravah.protocol.FrameEncoder`
- L27: `import com.aryntra.pravah.protocol.Message`
- L28: `import com.aryntra.pravah.protocol.MessageEncoder`
- L29: `import com.aryntra.pravah.protocol.MessageType`
- L30: `import com.aryntra.pravah.protocol.PeerState`
- L31: `import com.aryntra.pravah.protocol.ProtocolListener`
- L32: `import com.aryntra.pravah.transport.CompositeTransport`
- L33: `import com.aryntra.pravah.transport.Transport`
- L34: `import com.aryntra.pravah.transport.tcp.TcpTransport`
- L118: `val boundPort: Int get() = tcpTransport.boundPort`
- L119: `val isRunning: Boolean get() = compositeTransport.isRunning`
- L122: `compositeTransport.start()`
- L129: `compositeTransport.stop()`
- L164: `tcpTransport.connect(remoteHost, remotePort)`
- L177: `bluetoothTransport.connect(remoteMac)`
- L193: `registry.register(remotePeerId, cleanConn)`
- L199: `compositeTransport.send(cleanConn, FrameEncoder.encode(MessageEncoder.encode(joinMsg)))`
- L208: `router.send(remotePeerId, joinMsg)`
- L237: `connectivityRegistry.lookup(tempBtPeer).ifPresent { conn ->`
- L243: `connectivityRegistry.removePeer(tempBtPeer)`

## UI Thread Dispatch Points (0)


---
# AndroidBluetoothRfcommTransport.kt (read-only, §5C)
Properties: 24 | Functions: 12

Key functions:
- L60: `override fun getName(): String = "AndroidBluetoothRfcommTransport[$localMacAddress]"`
- L62: `override fun getCapabilities(): TransportCapabilities =`
- L72: `override fun start() {`
- L93: `override fun stop() {`
- L111: `override fun isRunning(): Boolean = running.get()`
- L113: `override fun send(destinationId: String, payload: ByteArray) {`
- L133: `override fun setListener(listener: TransportListener?) {`
- L139: `fun connect(remoteMac: String) {`
- L171: `fun disconnect(connectionId: String) {`
- L245: `fun startReader() {`
- L270: `fun write(data: ByteArray) {`
- L277: `fun close() {`

---
# Layout: activity_diagnostic.xml

## View Elements (1)
- `<LinearLayout>` id=(no id)  (L2)

## Extractable View IDs (12)
| # | View ID |
|---|---------|
| 1 | `tvStatus` |
| 2 | `tvMultiPathTopology` |
| 3 | `btnStart` |
| 4 | `btnStop` |
| 5 | `btnDiscover` |
| 6 | `btnConnectTcp` |
| 7 | `btnConnectBt` |
| 8 | `btnSimulateDrop` |
| 9 | `etMessage` |
| 10 | `btnSend` |
| 11 | `scrollLog` |
| 12 | `tvLog` |

---
# Resources

## Colors ()
| Name | Hex |
|------|-----|
| `ic_launcher_background` | `#000000` |

## Strings ()
| Name | Value |
|------|-------|
| `app_name` | Pravaah |

---
# Information-Source Mapping (Brief §6)

Auto-generated from code inspection.

| Diagnostic Info | Current Source (code) | UI Consumer (view ID) | Status |
|-----------------|----------------------|----------------------|--------|
| Node identity | DiagnosticActivity / PeerId | header/node section | Verified |
| Runtime state | DiagnosticActivity state vars | status area | Verified |
| TCP connectivity | MessagingManager / ConnectivityPath | connectivity section | Verified |
| Bluetooth state | BluetoothRfcommTransport | connectivity section | Verified |
| Peer identity | PeerConnectivityRegistry | peer section | Verified |
| Path state | PathState / ConnectivityPath | path section | Needs manual check |
| Discovery state | DiagnosticActivity / Manager | discovery indicator | Verified |
| Messages (TX/RX) | PravahAndroidMessagingManager | message/log area | Verified |
| ACK / delivery | ProtocolSessionManager callbacks | event area | Needs manual check |
| Live events | Listener callbacks -> UI dispatch | event stream | Verified |
| Operations (ctrl) | DiagnosticActivity button handlers | buttons section | Verified |


