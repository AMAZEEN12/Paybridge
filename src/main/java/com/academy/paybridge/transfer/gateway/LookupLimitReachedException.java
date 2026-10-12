package com.academy.paybridge.transfer.gateway;

/**
 * We stopped a live name lookup ourselves because today's allowance is used up (Paystack test mode only allows
 * a few per day). Nothing was sent to Paystack and nothing was debited.
 */
public class LookupLimitReachedException extends GatewayRejectedException {

    public LookupLimitReachedException(int dailyLimit) {
        super("Today's limit of " + dailyLimit + " live account lookups is used up. Try again tomorrow.", 429);
    }
}