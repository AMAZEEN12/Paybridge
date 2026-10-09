package com.academy.paybridge.transfer.gateway;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A pretend bank network for local runs, tests and demos. Active unless the "paystack" profile is on.
 * The destination ACCOUNT NUMBER decides what happens:
 *
 *   starts with 00   name enquiry fails (account not found)
 *   starts with 77   the provider rejects the payout straight away  -> refund
 *   starts with 88   the request "times out" but the provider DID pay -> stays pending, verify later says success
 *   starts with 99   accepted, then fails on verify                  -> refund
 *   anything else    accepted, then succeeds on verify
 */
@Component
@Profile("!paystack")
public class FakeTransferGateway implements TransferGateway {

    private enum Outcome { SUCCESS, FAILED }

    private final Map<String, Outcome> processed = new ConcurrentHashMap<>();

    @Override
    public List<Bank> listBanks() {
        return List.of(
                new Bank("Access Bank", "044"),
                new Bank("First Bank of Nigeria", "011"),
                new Bank("First City Monument Bank (FCMB)", "214"),
                new Bank("Guaranty Trust Bank (GTBank)", "058"),
                new Bank("United Bank for Africa (UBA)", "033"),
                new Bank("Zenith Bank", "057"));
    }

    @Override
    public ResolvedAccount resolveAccount(String accountNumber, String bankCode) {
        if (accountNumber.startsWith("00")) {
            throw new GatewayRejectedException("Could not resolve account name.", 422);
        }
        return new ResolvedAccount(accountNumber, "TEST ACCOUNT " + accountNumber.substring(6));
    }

    @Override
    public String createRecipient(String accountName, String accountNumber, String bankCode) {
        return "RCP_" + bankCode + "_" + accountNumber;
    }

    @Override
    public GatewayResult initiateTransfer(String reference, long amountKobo, String recipientCode, String reason) {
        String account = recipientCode.substring(recipientCode.lastIndexOf('_') + 1);
        if (processed.containsKey(reference)) {
            // Same reference again: the provider refuses to pay twice and just reports the first one.
            return new GatewayResult(GatewayStatus.PENDING, "FAKE_" + reference, "Already received");
        }
        if (account.startsWith("77")) {
            throw new GatewayRejectedException("Provider rejected the transfer.", 400);
        }
        processed.put(reference, account.startsWith("99") ? Outcome.FAILED : Outcome.SUCCESS);
        if (account.startsWith("88")) {
            throw new GatewayUnavailableException("Timed out waiting for the provider (but it did receive the request).");
        }
        return new GatewayResult(GatewayStatus.PENDING, "FAKE_" + reference, "Queued");
    }

    @Override
    public GatewayResult verifyTransfer(String reference) {
        Outcome outcome = processed.get(reference);
        if (outcome == null) {
            return new GatewayResult(GatewayStatus.NOT_FOUND, null, "Unknown reference");
        }
        return outcome == Outcome.SUCCESS
                ? new GatewayResult(GatewayStatus.SUCCESS, "FAKE_" + reference, "Paid")
                : new GatewayResult(GatewayStatus.FAILED, "FAKE_" + reference, "Failed at the receiving bank");
    }
}
