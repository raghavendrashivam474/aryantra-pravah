# AD2.2-F — Causality Matrix

| Hypothesis | Theory | Evidence | Verdict |
|---|---|---|---|
| **H1: Existing Core Behavior** | Core transport/presence always created duplicate paths prior to Track A. | Core tests (`MultiPathRepresentationTest`, `ConnectivityPathLifecycleTest`) prove Core preserves a strict 1-to-1 PathId map when connection IDs are uniform. Legacy Core had no multi-activation bug. | **DISPROVEN** |
| **H2: Diagnostic Representation** | DiagnosticModelMapper duplicates or misrepresents 1 Core path as 2 paths in UI. | DiagnosticModelMapper maps 1-to-1 from `PeerConnectivity.allPaths()`. Core actually contained 2 distinct `ConnectivityPath` objects due to differing PathIds (`path:...:conn:192...` vs `path:...:conn:/192...`). Mapper merely stripped the slash on display. | **DISPROVEN** (Mapper reflects true Core duplicate state) |
| **H3: Operation Invocation Regression** | Diagnostic operations / Android manager trigger redundant connections or duplicate lifecycle calls. | Traced call chains in `PravahAndroidMessagingManager.kt`: `connectToTcp`, `sendJoin`, `onPeerJoined`, and `onMessageReceived` each redundantly call `presenceBridge.handlePeerConnected()`. | **PROVEN** |
| **H4: A.D2.1 Regression** | Changes in A.D2.1 (duplicate connect guard, BT chooser, connection binding) introduced path anomalies. | A.D2.1 added `cleanConnId()` in some places but omitted it during outbound `connectToTcp()` and inside `TcpTransport` connection ID generation, creating the string mismatch that bypassed path deduplication. Also introduced `remote-bt-XXXXXX` synthetic IDs which diverged from manager cleanup. | **PROVEN** |
| **H5: A.D2.2 Regression** | Path pruning logic added to `PeerPresenceBridge` in A.D2.2 caused the duplicate active paths. | `PeerPresenceBridge.activatePath()` prunes inactive paths, but falls back to creating a new ACTIVE path if no inactive path matches and no active path matches the exact connectionId string. The string mismatch (`/IP:port` vs `IP:port`) triggers this creation. The pruning logic itself works as designed, but exposes the unnormalized ID flaw. | **DISPROVEN** (Pruning is correct; uncovered unnormalized IDs) |
| **H6: Environment / Physical Artifact** | Anomalies caused by OS socket variations, device pairing cache, or network retries. | The slash prefix `/192.168.1.50:8080` is standard Java `InetSocketAddress.toString()` behavior on Android/JVM. It is deterministic across all standard Java socket runtimes when unparsed. | **DISPROVEN** (Deterministic code logic, not environmental) |

## Final Causality Summary
The root cause is a **Track A Android Orchestration Defect**:
1. Connection IDs for TCP sockets were formatted without a leading slash (`"192.168.1.X:8080"`) during manual UI connect, but with a leading slash (`"/192.168.1.X:8080"`) by Java socket addresses inside `TcpTransport`.
2. Multiple redundant calls to `handlePeerConnected()` occurred across the connection lifecycle.
3. Synthetic Bluetooth PeerId generation used dynamic random names (`remote-bt-XXXXXX`) while manager cleanup looked for static name `remote-bt-node`.
