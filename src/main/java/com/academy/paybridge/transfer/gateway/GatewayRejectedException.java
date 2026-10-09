package com.academy.paybridge.transfer.gateway;

/** The provider clearly said no (a 4xx answer). We know the money did NOT go, so a refund is safe. */
public class GatewayRejectedException extends RuntimeException {

    private final int httpStatus;

    public GatewayRejectedException(String message, int httpStatus) {
        super(message);
        this.httpStatus = httpStatus;
    }

    public int getHttpStatus() {
        return httpStatus;
    }
}
