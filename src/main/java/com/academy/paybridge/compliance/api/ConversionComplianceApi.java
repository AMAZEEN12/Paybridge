package com.academy.paybridge.compliance.api;

public interface ConversionComplianceApi {

    /**
     * Evaluates conversion limits (single, daily) and velocity (R-LIM-1 to R-LIM-4).
     * Returns FraudResult with Decision.ALLOW or Decision.BLOCK.
     */
    FraudResult evaluate(ConversionContext context);

    int velocityWindowMinutes();
}
