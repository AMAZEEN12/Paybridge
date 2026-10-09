package com.academy.paybridge.transfer.gateway;

import java.util.List;

/**
 * The one door to the outside world. The transfer code only knows this interface. Paystack is one
 * implementation; the fake one used in tests and local runs is another.
 */
public interface TransferGateway {

    List<Bank> listBanks();

    /** Name enquiry. Throws GatewayRejectedException if the account cannot be found. */
    ResolvedAccount resolveAccount(String accountNumber, String bankCode);

    /** Registers the beneficiary with the provider and returns its recipient code. */
    String createRecipient(String accountName, String accountNumber, String bankCode);

    /** Starts the payout. The reference is OURS, so a retry with the same reference can never pay twice. */
    GatewayResult initiateTransfer(String reference, long amountKobo, String recipientCode, String reason);

    GatewayResult verifyTransfer(String reference);
}
