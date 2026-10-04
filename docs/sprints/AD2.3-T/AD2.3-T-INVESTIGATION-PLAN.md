# A.D2.3-T — TCP Runtime Delivery & Connection-Lifecycle Root-Cause Investigation Plan

## Baseline
- **Tag:** vA.D2.3
- **Branch:** main
- **Mode:** FORENSIC INVESTIGATION ONLY — NO PRODUCTION FIXES AUTHORIZED
- **Scope:** TCP only. Bluetooth is explicitly out of scope for this sprint.

## Observations From Physical Validation (Not Yet Proven Root Cause)
| ID | Symptom | Status |
|----|---------|--------|
| O1 | Duplicate `[TCP] ● ACTIVE` rows for apparently one physical connection | OPEN |
| O2 | Path endpoint displays `10.177.67.157:36681` while dispatch route displays `/10.177.67.157:36681` | OPEN |
| O3 | TX event fires but no corresponding RX event appears | OPEN |

## Primary Investigation Questions
### Connection Lifecycle
- [ ] Who creates the initial TCP connection ID? What exact value?
- [ ] Who stores, transforms, compares it?
- [ ] Who creates the ConnectivityPath? How many after one physical connect?
- [ ] Are duplicate paths one socket twice, or two sockets?

### Routing
- [ ] Which PathId does PathSelectionPolicy actually select?
- [ ] Which connectionId belongs to that path?
- [ ] What does PeerRouter.send() pass onward? Does it change before reaching transport?

### Transport
- [ ] What connectionId does CompositeTransport resolve?
- [ ] What connectionId does TcpTransport.send() receive?
- [ ] Does send() actually write bytes? How many?

### Receiving
- [ ] Does the remote socket receive bytes?
- [ ] Does TcpTransport.onDataReceived fire?
- [ ] Does protocol decode succeed?
- [ ] Does the decoded messageId match the original?
- [ ] Does DefaultApplicationMessagingService receive it?
- [ ] Does the Diagnostic RX listener fire?

### Final Question
- [ ] At which exact boundary does `PRAVAAH-TCP-001` stop progressing?

## Six Hypotheses to Prove or Disprove
| ID | Hypothesis | Verdict |
|----|-----------|---------|
| H1 | A.D2.3 normalization is incomplete / another layer re-injects the slash | PENDING |
| H2 | Two physical sockets are created per connect (inbound + outbound symmetric) | PENDING |
| H3 | PathSelectionPolicy selects a stale path whose connectionId no longer maps to a live socket | PENDING |
| H4 | CompositeTransport routing lookup fails and silently drops / dispatches to wrong transport | PENDING |
| H5 | Protocol decode fails for inbound frames (silent failure) | PENDING |
| H6 | DiagnosticModelMapper display anomaly only — underlying delivery works | PENDING |

## Investigation Order (Strict)
1. **Phase 1 — Static source trace** (Blocks 2–6)
   - Group A: Android orchestration (manager, activity, mapper)
   - Group B: Core routing/transport (router, composite, tcp)
   - Group C: Path lifecycle (read-only confirmation)
   - Group D: Message lifecycle (sender/receiver)
2. **Phase 2 — Controlled physical experiment** (Block 7+)
   - Single TCP connect, single `PRAVAAH-TCP-001` message, A → B
   - Bidirectional test: B → A `PRAVAAH-TCP-002`
   - Record identifier at every boundary
3. **Phase 3 — Causal analysis & root-cause classification**
4. **Phase 4 — Surgical remediation proposal (NO code change in A.D2.3-T)**

## Hard Constraints
- Do NOT modify any Core file (connectivity, presence, router, policy, protocol, reliability, security).
- Do NOT re-fix the slash anywhere else. A.D2.3 normalized at the authoritative boundary.
- Do NOT add retries, buffering, or alternate routing to hide the problem.
- Do NOT modify PathSelectionPolicy.
- Do NOT change protocol or wire format.
- Do NOT touch Bluetooth.
- Temporary instrumentation (trace logs) allowed, must be removed or explicitly approved before close.

## Deliverables
- `AD2.3-T-INVESTIGATION-PLAN.md` (this file)
- `AD2.3-T-ROOT-CAUSE-REPORT.md` (end of sprint)
- `AD2.3-T-VALIDATION-EVIDENCE.md` (physical experiment table)
- `AD2.3-T-ARCHITECTURE-NOTE.md` (only if architecture change is proposed)