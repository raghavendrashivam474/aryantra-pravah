package com.aryntra.pravah.security.trust;

/**
 * Represents the security trust lifecycle state of a peer.
 *
 * <p>This is intentionally separate from {@link com.aryntra.pravah.protocol.PeerState}
 * and {@link com.aryntra.pravah.peer.presence.PeerPresenceState} because:</p>
 * <ul>
 *   <li>PeerState tracks protocol session membership (JOINED / LEFT).</li>
 *   <li>PeerPresenceState tracks physical reachability (UNKNOWN / AVAILABLE / CONNECTED / UNAVAILABLE).</li>
 *   <li>TrustState tracks cryptographic identity verification and trust policy outcome.</li>
 * </ul>
 *
 * <p>A peer can be CONNECTED (transport) and JOINED (protocol) but not yet TRUSTED (security).
 * SX.4 enforces that application-level usability requires TRUSTED, not merely JOINED.</p>
 *
 * <p>SX.4 — Trust-Aware Connection Lifecycle</p>
 */
public enum TrustState {

    /** No authentication has been attempted for this peer. */
    UNKNOWN,

    /** An authentication challenge has been issued; awaiting proof. */
    AUTHENTICATING,

    /** The peer's cryptographic proof was verified successfully. */
    AUTHENTICATED,

    /** The peer is authenticated AND has passed the trust policy. Application-usable. */
    TRUSTED,

    /** Authentication failed or trust policy rejected this peer. Not application-usable. */
    REJECTED
}
