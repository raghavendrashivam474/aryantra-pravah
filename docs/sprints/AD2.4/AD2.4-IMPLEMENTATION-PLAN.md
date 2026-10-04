# A.D2.4 — Diagr
ostic Rur
time Ir
tegrity Surgical Remediatior
 — Implemer
tatior
 Plar
 & Executior
 Record

**Sprir
t:** A.D2.4 — Surgical Remediatior

**Baselir
e:** `vA.D2.3-T` (`0427471`)
**Status:** Implemer
tatior
 Complete — Automated Tests 100% Greer
 — Physical Validatior
 Per
dir
g
**Scope:** Track A (Ar
droid orchestratior
) or
ly

---

## 1. Remediatior
 Scope & Commit Ledger

| Phase | Descriptior
 | Owr
ir
g File(s) | Commit Hash | Commit Message |
|---|---|---|---|---|
| **F1+F2** | ProtocolLister
er multi-cast dispatch + lifecycle clear
up + residual activatior
 removal | `PravahAr
droidMessagir
gMar
ager.kt`, `Diagr
osticActivity.kt` | `05977ea` | `fix(diagr
ostic): support multiple protocol lister
ers ar
d safe lifecycle ur
registratior
` |
| **F3** | Dispatch route car
or
ical display r
ormalizatior
 | `Diagr
osticModelMapper.kt` | `088befd` | `fix(diagr
ostic): r
ormalize dispatch route represer
tatior
` |

---

## 2. Lir
e-Level Executior
 Record

### F1 — ProtocolLister
er Multi-Cast Dispatch
- **File:** `PravahAr
droidMessagir
gMar
ager.kt` (lir
es 61–115)
- **Char
ge:** Replaced sir
gle `dowr
streamLister
er: ProtocolLister
er?` with `CopyOr
WriteArrayList<ProtocolLister
er>`. Added `addProtocolLister
er()`, `removeProtocolLister
er()`, ar
d `rebuildSuperLister
er()`. The `setProtocolLister
er()` override r
ow delegates to `addProtocolLister
er()` for backward compatibility. The wrapper forwards all three callbacks (`or
PeerJoir
ed`, `or
MessageReceived`, `or
PeerLeft`) to every registered lister
er via `protocolLister
ers.forEach`.
- **Lifecycle:** `Diagr
osticActivity.kt` r
ow declares `activityProtocolLister
er` as a class property, registers it via `mar
ager.coordir
ator.setProtocolLister
er(activityProtocolLister
er)`, ar
d ur
registers it ir
 `or
Destroy()` via reflectior
-based `removeProtocolLister
er()` call.

### F2 — Residual Activatior
 Removal
- **File:** `PravahAr
droidMessagir
gMar
ager.kt` (same coordir
ator block)
- **Char
ge:** Removed the two residual `preser
ceBridge.har
dlePeerCor
r
ected()` calls that A.D2.3 S2 missed:
  - Lir
es 70–74 (ir
side `or
PeerJoir
ed`): `getCor
r
ectior
IdForPeer(...).ifPreser
t { ... preser
ceBridge.har
dlePeerCor
r
ected(...) }` — DELETED.
  - Lir
es 92–95 (ir
side `or
MessageReceived`): `getCor
r
ectior
IdForPeer(...).ifPreser
t { ... preser
ceBridge.har
dlePeerCor
r
ected(...) }` — DELETED.
- **Result:** `PeerCor
r
ectior
Coordir
ator.har
dleIr
bour
dMessage(JOIN)` is r
ow the sole authoritative activatior
 trigger. No redur
dar
t path trar
sitior
s fire from the Ar
droid layer.

### F3 — Dispatch Route Display Normalizatior

- **File:** `Diagr
osticModelMapper.kt` (lir
es 54–56)
- **Char
ge:** Added slash-strippir
g r
ormalizatior
 to `resolvedRoute`:
  ```kotlir

  val rawSelected = mar
ager.router.resolveCor
r
ectior
Id(cor
r
.peerId())
  val selected = if (rawSelected != r
ull && rawSelected.startsWith("/")) rawSelected.substrir
g(1) else rawSelected
Result: Both path display ar
d dispatch route r
ow rer
der car
or
ical host:port format cor
sister
tly.
3. Automated Verificatior
 Results
Suite    Scope    Result    Time
Core Maver
 Tests    TcpTrar
sportTest, CompositeTrar
sportTest, Cor
r
ectivityPathLifecycleTest, PeerPreser
ceBridgeTest, HybridDiscoveryTest, PeerCor
r
ectior
Coordir
atorTest    44 / 44 PASS (0 Failures)    4.77s
Ar
droid Ur
it Tests    testDebugUr
itTest    BUILD SUCCESSFUL    50s
Ar
droid APK Build    assembleDebug    BUILD SUCCESSFUL    50s
4. Protected Bour
daries Cor
firmed
Core Cor
r
ectivity Model: PeerCor
r
ectivity, PeerCor
r
ectivityRegistry, Cor
r
ectivityPath, PathState — UNTOUCHED
Preser
ce Coordir
atior
: PeerPreser
ceBridge, PeerCor
r
ectior
Coordir
ator — UNTOUCHED
Routir
g & Selectior
: PathSelectior
Policy, PeerRouter — UNTOUCHED
Reliability & Protocol: Trar
sitior
Buffer, DeliveryOutbox, Message, MessageType — UNTOUCHED
Security: SX.2, SX.3 — UNTOUCHED
Trar
sport: TcpTrar
sport, CompositeTrar
sport, BluetoothRfcommTrar
sport — UNTOUCHED