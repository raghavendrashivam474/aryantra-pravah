package com.aryntra.pravah.security.trust;

import com.aryntra.pravah.security.authentication.AuthenticationResult;
import com.aryntra.pravah.security.identity.CryptographicIdentity;

/**
 * Policy boundary that evaluates whether an authenticated peer should transition to {@link TrustState#TRUSTED}.
 *
 * <p>Separating authentication from trust evaluation allows arbitrary trust rules (e.g. pinned keys,
 * certificate authorities, local whitelists, or default peer-to-peer permissive trust) without modifying
 * the cryptographic verification engine.</p>
 *
 * <p>SX.4 — Trust-Aware Connection Lifecycle</p>
 */
@FunctionalInterface
public interface TrustPolicy {

    /**
     * Evaluates trust for a peer whose cryptographic identity and proof have been verified.
     *
     * @param identity   the authenticated cryptographic identity
     * @param authResult the result of the cryptographic authentication check
     * @return {@code true} if the peer is trusted for application usage; {@code false} if rejected
     */
    boolean evaluate(CryptographicIdentity identity, AuthenticationResult authResult);

    /**
     * Default policy: any peer that successfully authenticates is trusted.
     */
    static TrustPolicy allowAllAuthenticated() {
        return (identity, authResult) -> authResult != null && authResult.isSuccess();
    }
}
