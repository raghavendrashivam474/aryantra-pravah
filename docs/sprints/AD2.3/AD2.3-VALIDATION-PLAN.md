# A.D2.3 — Validation Plan

**Sprint:** A.D2.3 — Diagnostic Runtime Integrity Finalization
**Baseline:** vA.D2.2-F
**Status:** Automated regression green. Physical validation pending.
**Scope:** Prove the three Track A corrections. Do not treat a green build as sprint completion.

## 1. Automated regression already executed

| Check | Result |
|---|---|
| Core Maven targeted suite | 37 tests, 0 failures |
| `TcpTransportTest#testCanonicalConnectionIdWithoutLeadingSlash` | PASS |
| Android `testDebugUnitTest` | BUILD SUCCESSFUL |
| Android `assembleDebug` | BUILD SUCCESSFUL |
| APK | `android/app/build/outputs/apk/debug/app-debug.apk` |

These checks prove the change compiles and does not break the existing JVM/Android unit suites. They do not replace the physical scenarios below.

## 2. What must remain true

- One physical TCP connection produces one ACTIVE TCP path.
- One physical Bluetooth connection produces one ACTIVE Bluetooth path.
- TCP and Bluetooth may both be ACTIVE for the same peer. That is legitimate multi-path, not a duplicate.
- `PathSelectionPolicy` remains the only route selector.
- JOIN still establishes the real peer.
- Reconnect does not leave a stale duplicate ACTIVE path.
- No `remote-bt-*` peer remains after a successful real JOIN.
- One logical connection produces one activation transition, not a burst of identical activations.

## 3. TCP-01 — Single connection

1. Fresh app state on both devices.
2. Start both nodes.
3. Discover from device A.
4. Tap CONNECT TCP once.
5. Wait for JOIN.

Record:

| Field | Value |
|---|---|
| PeerId | |
| Path count for TCP | |
| PathId(s) | |
| connectionId(s) | |
| Endpoint shown | |
| LiveWire activation count | |

Pass: exactly one `[TCP] ACTIVE` row for that physical connection. connectionId has no leading `/`.

## 4. TCP-02 — Duplicate connect

1. From the TCP-01 state, tap CONNECT TCP again.

Pass: existing guard skips or the second press does not create a second ACTIVE path for the same socket.

## 5. TCP-03 — Reconnect

1. Disconnect.
2. Connect TCP again.
3. Compare old PathId / connectionId with the new ones.

Pass: one ACTIVE TCP path after reconnect. The closed path is not left ACTIVE. No visually identical twin row.

## 6. BT-01 — Clean successful connection

1. Fresh state. Do not fail a candidate first.
2. Select the actual Pravaah peer.
3. Connect. Wait for JOIN.

Record selected MAC, displayed endpoint, connectionId, synthetic PeerId if any, real PeerId, PathId.

Pass: one real PeerId, one Bluetooth ACTIVE path, endpoint matches the selected device, no leftover `remote-bt-*` peer.

## 7. BT-02 — Failed candidate, then success

1. Select a wrong paired device. Let it fail.
2. Select the actual Pravaah peer. Let JOIN succeed.

Pass: failed synthetic state is gone. Final path endpoint is the successful device, not the failed candidate.

## 8. BT-03 — Reconnect

1. Connect, disconnect, connect again.

Pass: one ACTIVE Bluetooth path, correct endpoint, no orphan synthetic identity, no repeated activation burst for the new lifecycle.

## 9. Hybrid

1. Establish TCP and Bluetooth to the same peer.

Pass:

```text
Peer
├── TCP ACTIVE
└── Bluetooth ACTIVE
```

This must not be collapsed into one path. Then drop TCP and confirm the existing selection policy can use Bluetooth. Do not add a second selector.

## 10. LiveWire

For one TCP connect and one Bluetooth connect, count:

- `null -> ACTIVE` (or equivalent) events
- JOIN events

Pass: one activation for one logical connection. A second event is a fail unless it is a genuinely separate path (TCP plus Bluetooth).

## 11. Fail criteria

Stop and do not tag the sprint closed if any of these occur:

- two ACTIVE TCP rows for one socket
- connectionId still contains a leading `/`
- `remote-bt-*` remains after real JOIN
- Bluetooth endpoint belongs to the failed candidate
- TCP and Bluetooth are merged into one path
- routing, JOIN, ACK, or failover regresses

## 12. Evidence to attach before close

- TCP-01, TCP-02, TCP-03 notes
- BT-01, BT-02, BT-03 notes
- Hybrid note
- LiveWire counts
- `git status` clean aside from the validation write-up