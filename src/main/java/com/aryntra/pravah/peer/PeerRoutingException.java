package com.aryntra.pravah.peer;

import com.aryntra.pravah.core.PravahException;

/**
 * Thrown when a message cannot be routed to a logical peer.
 *
 * <p>Causes include unknown destination peer, peer has no active transport connection,
 * or underlying transport failure during dispatch.</p>
 *
 * S3.3 - Phase 3: Peer-to-Peer Routing
 */
public class PeerRoutingException extends PravahException {

    public PeerRoutingException(String message) {
        super(message);
    }

    public PeerRoutingException(String message, Throwable cause) {
        super(message, cause);
    }
}