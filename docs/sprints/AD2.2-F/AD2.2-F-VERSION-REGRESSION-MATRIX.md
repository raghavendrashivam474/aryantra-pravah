# AD2.2-F — Version Regression Matrix

This matrix tracks core and diagnostic-level changes across the baseline versions to isolate the entry point of current TCP/Bluetooth runtime anomalies.

## Version History Mapping

| Version | Commit/Tag | Core Changes | Android Changes | TCP Behavior | BT Behavior | Notes |
|---------|------------|--------------|-----------------|--------------|-------------|-------|
| **vB.R2** | 4e82d4f | Baseline production build | Baseline application (No diagnostic cockpit) | Production path behavior; single connection per peer | Production BT transport active | Stable, production-proven baseline. No Diagnostic view. |
| **vA.D2** | 3e1a804 | Diagnostic hooks; initial event generation | Added Live network topology, Diagnostic cockpit UI | Diagnostic UI added; live events active | BT device chooser added | Initial implementation of Track A diagnostic framework. |
| **vA.D2.1** | e319946 | Added TCP duplicate-connect guard, connection session binding | Added BT device chooser, scrolling improvements, path presentation | Duplicate-connect guards introduced | BT MAC address mapping improvements | UI and transport stabilization sprint. |
| **vA.D2.2** | e2c2cc3 | PeerPresenceBridge modifications (path pruning, closed-connection handling) | Resolved Synthetic BT identity leak, inactive path retention, improved UI precision | Reported issue: TCP double-ACTIVE paths | Reported issue: BT MAC mismatch / multiple activations | Core path lifecycle pruning added to fix inactive path leaks. |

## Change Analysis of PeerPresenceBridge.java

Recent commits touching PeerPresenceBridge.java:
```text
5d75793 fix(connectivity): prune redundant inactive paths in PeerPresenceBridge during activation and closed events
cacad1c fix(connectivity): restore recovered paths and prevent synthetic orphan paths
e6b3211 fix(connectivity): enforce canonical single path per transport scheme and normalize connection IDs
3262ede feat(s8.3): integrate hybrid discovery and multi-transport addressing
06d7fc0 feat(s6.4): integrate lan discovery with candidate pathways and link connection lifecycle
```

## Key Inferences for Hypotheses H1, H4, and H5

- **H1 (Core Legacy)**: Did the issue exist in vB.R2 but remain invisible due to lack of Diagnostic Cockpit?
- **H4 (A.D2.1 Regression)**: Did the duplicate-connect guards or state changes in vA.D2.1 introduce 
  routing/presentation side effects?
- **H5 (A.D2.2 Regression)**: Did the addition of activation/pruning logic in PeerPresenceBridge.java alter physical path mappings?
