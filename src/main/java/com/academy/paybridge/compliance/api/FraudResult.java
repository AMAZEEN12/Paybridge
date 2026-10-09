package com.academy.paybridge.compliance.api;

import java.util.List;

/** The answer: ALLOW, FLAG (let it through, but record and warn the owner) or BLOCK (refuse). */
public record FraudResult(Decision decision, List<String> reasons) {

    public static FraudResult allow() {
        return new FraudResult(Decision.ALLOW, List.of());
    }

    public String joinedReasons() {
        return String.join("; ", reasons);
    }
}
