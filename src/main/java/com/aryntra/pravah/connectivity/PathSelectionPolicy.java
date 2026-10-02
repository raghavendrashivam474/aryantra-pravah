package com.aryntra.pravah.connectivity;

import com.aryntra.pravah.peer.PeerId;

import java.util.*;

/**
 * Deterministic path selection strategy interface for multi-path routing in Pravaah.
 *
 * <p>Separates routing decisions (WHERE to send) from transport mechanics (HOW bytes are transferred)
 * and reliability logic (WHEN/WHETHER to retry).</p>
 *
 * <p>Requirements (ADR / S8.5):
 * <ul>
 *   <li>Deterministic, testable, and explainable path selection.</li>
 *   <li>Zero machine learning or non-deterministic heuristics.</li>
 *   <li>Preserves backward-compatible PathId lexicographical ordering by default.</li>
 *   <li>Supports prioritized transport scheme preferences (e.g., LAN TCP over Bluetooth RFCOMM).</li>
 * </ul>
 * </p>
 *
 * S8.5 - Phase 8: Multi-Path Operation
 */
@FunctionalInterface
public interface PathSelectionPolicy {

    /**
     * Selects a single active communication path from a list of eligible candidates for a destination peer.
     *
     * @param destination the destination PeerId (must not be null)
     * @param activePaths the list of currently ACTIVE paths for this peer (must not be null or empty)
     * @return the selected ConnectivityPath, or {@link Optional#empty()} if no eligible path exists
     */
    Optional<ConnectivityPath> selectPath(PeerId destination, List<ConnectivityPath> activePaths);

    /**
     * Default deterministic policy: sorts active paths lexicographically by {@link PathId#value()}.
     * Guarantees 100% compatibility with Phase 6 (S6.5 / ADR 001).
     */
    static PathSelectionPolicy byPathId() {
        return (destination, activePaths) -> {
            if (activePaths == null || activePaths.isEmpty()) {
                return Optional.empty();
            }
            return activePaths.stream()
                    .filter(ConnectivityPath::isActive)
                    .min(Comparator.comparing(p -> p.pathId().value()));
        };
    }

    /**
     * Default standard policy (alias for {@link #byPathId()}).
     */
    static PathSelectionPolicy defaultPolicy() {
        return byPathId();
    }

    /**
     * Prioritized scheme policy: selects active paths matching preferred transport schemes in order
     * (e.g., "tcp", "bluetooth"), breaking ties deterministically by {@link PathId#value()}.
     *
     * @param preferredSchemes transport scheme names in priority order (e.g. "tcp", "bluetooth")
     */
    static PathSelectionPolicy preferSchemes(String... preferredSchemes) {
        Objects.requireNonNull(preferredSchemes, "preferredSchemes must not be null");
        List<String> schemeOrder = Arrays.stream(preferredSchemes)
                .filter(Objects::nonNull)
                .map(s -> s.trim().toLowerCase())
                .toList();

        return (destination, activePaths) -> {
            if (activePaths == null || activePaths.isEmpty()) {
                return Optional.empty();
            }

            List<ConnectivityPath> usable = activePaths.stream()
                    .filter(ConnectivityPath::isActive)
                    .toList();

            if (usable.isEmpty()) {
                return Optional.empty();
            }

            // 1. Try matching preferred schemes in priority order
            for (String scheme : schemeOrder) {
                Optional<ConnectivityPath> match = usable.stream()
                        .filter(p -> p.transportName().equalsIgnoreCase(scheme)
                                || p.endpointAddress().transportScheme().equalsIgnoreCase(scheme))
                        .min(Comparator.comparing(p -> p.pathId().value()));
                if (match.isPresent()) {
                    return match;
                }
            }

            // 2. Fallback to default PathId ordering
            return usable.stream().min(Comparator.comparing(p -> p.pathId().value()));
        };
    }
}