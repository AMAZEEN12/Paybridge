package com.academy.paybridge.transfer.gateway;

/**
 * We do not know what happened: a timeout, a dropped connection, or a 5xx. The provider may or may not
 * have paid. NEVER refund on this. Leave the transfer pending and verify later.
 */
public class GatewayUnavailableException extends RuntimeException {

    public GatewayUnavailableException(String message) {
        super(message);
    }

    public GatewayUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
