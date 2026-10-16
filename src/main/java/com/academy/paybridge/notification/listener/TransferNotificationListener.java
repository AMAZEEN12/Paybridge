package com.academy.paybridge.notification.listener;

import com.academy.paybridge.account.api.AccountApi;
import com.academy.paybridge.account.api.AccountStatus;
import com.academy.paybridge.account.api.AccountStatusChangedEvent;
import com.academy.paybridge.notification.service.NotificationChannel;
import com.academy.paybridge.shared.money.CurrencyRegistry;
import com.academy.paybridge.shared.money.Money;
import com.academy.paybridge.transfer.api.TransferEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.List;
import java.util.Optional;

/**
 * Turns transfer events into notifications.
 *
 * AFTER_COMMIT: only notify customers about transfers whose transactions committed. fallbackExecution lets an event
 * published with no transaction open (a failed cross-currency transfer, after its rollback) go out at once.
 * @Async: a slow or broken notifier cannot slow down the transfer.
 * Every failure is caught and logged, never rethrown.
 */
@Component
public class TransferNotificationListener {

    private static final Logger log =
            LoggerFactory.getLogger(TransferNotificationListener.class);

    private final AccountApi accounts;
    private final List<NotificationChannel> channels;
    private final CurrencyRegistry currencies;

    public TransferNotificationListener(
            AccountApi accounts,
            List<NotificationChannel> channels,
            CurrencyRegistry currencies) {
        this.accounts = accounts;
        this.channels = channels;
        this.currencies = currencies;
    }

    /**
     * Naira keeps its existing formatting; other currencies use their own
     * currency symbol and decimal precision.
     */
    private String show(long minor, String currency) {
        return "NGN".equals(currency)
                ? Money.format(minor)
                : currencies.format(minor, currency);
    }

    @Async("notificationExecutor")
    @TransactionalEventListener(
            phase = TransactionPhase.AFTER_COMMIT,
            fallbackExecution = true)
    public void onTransfer(TransferEvent e) {
        try {
            String src = Money.maskAccount(e.sourceAccount());
            String dst = Money.maskAccount(e.destinationAccount());

            String charges = e.chargesMinor() > 0
                    ? " Charges " + show(e.chargesMinor(), e.currency()) + "."
                    : "";

            String where = e.destinationBankName() != null
                    ? e.destinationBankName() + " " + dst
                    : dst;

            String ref = " Ref " + e.reference() + ".";

            boolean cross = e.targetCurrency() != null;

            switch (e.stage()) {
                case COMPLETED -> {
                    String sent;
                    String received;

                    if (cross) {
                        // The sender paid totalDebit (the fee is inside it); the recipient got the other currency.
                        sent = "You sent " + show(e.totalDebitMinor(), e.currency())
                                + " to " + dst + ". They received "
                                + show(e.targetAmountMinor(), e.targetCurrency()) + "."
                                + (e.chargesMinor() > 0
                                ? " Fee " + show(e.chargesMinor(), e.currency()) + " included."
                                : "")
                                + ref;
                        received = "You received " + show(e.targetAmountMinor(), e.targetCurrency())
                                + " from " + src + "." + ref;
                    } else {
                        sent = "You sent " + show(e.amountMinor(), e.currency())
                                + " to " + dst + "." + charges + ref;
                        received = "You received " + show(e.amountMinor(), e.currency())
                                + " from " + src + "." + ref;
                    }

                    tell(e.sourceAccount(), "DEBIT", "Money sent", sent, e.reference());
                    tell(e.destinationAccount(), "CREDIT", "Money received", received, e.reference());
                }

                case ACCEPTED -> tell(
                        e.sourceAccount(),
                        "DEBIT",
                        "Payout sent",
                        "You sent " + show(e.amountMinor(), e.currency())
                                + " to " + where + "." + charges
                                + " We are waiting for the bank to confirm."
                                + ref,
                        e.reference());

                case SUCCEEDED -> tell(
                        e.sourceAccount(),
                        "TRANSFER_SUCCESS",
                        "Payout successful",
                        "Your payout of " + show(e.amountMinor(), e.currency())
                                + " to " + where + " was successful." + ref,
                        e.reference());

                case FAILED -> tell(
                        e.sourceAccount(),
                        "REFUND",
                        "Payout failed, money returned",
                        "Your transfer of " + show(e.amountMinor(), e.currency())
                                + " to " + where + " failed. "
                                + show(e.totalDebitMinor(), e.currency())
                                + " has been returned to your account." + ref,
                        e.reference());

                // R-NTF-2: one message for the sender; it says nothing moved and never says "refunded".
                case NOT_SENT -> tell(
                        e.sourceAccount(),
                        "TRANSFER_FAILED",
                        "Transfer did not go through",
                        "Your transfer of " + show(e.totalDebitMinor(), e.currency())
                                + " to " + dst + " did not go through. No money moved." + ref,
                        e.reference());
            }

            if (e.flagged()) {
                tell(
                        e.sourceAccount(),
                        "FRAUD_ALERT",
                        "Check this payment",
                        "A payment of " + show(e.amountMinor(), e.currency())
                                + " to " + where
                                + " was flagged for review. "
                                + "If this was not you, freeze your account now.",
                        e.reference());
            }

        } catch (RuntimeException ex) {
            log.warn(
                    "Could not create notifications for a transfer event: {}",
                    ex.getClass().getSimpleName());
        }
    }

    @Async("notificationExecutor")
    @TransactionalEventListener(
            phase = TransactionPhase.AFTER_COMMIT,
            fallbackExecution = true)
    public void onAccountStatus(AccountStatusChangedEvent e) {
        try {
            String what = e.status() == AccountStatus.FROZEN
                    ? "frozen"
                    : "unfrozen";

            channelsSend(
                    e.customerId(),
                    "SECURITY",
                    "Account " + what,
                    "Your account "
                            + Money.maskAccount(e.accountNumber())
                            + " was " + what + ".",
                    null);

        } catch (RuntimeException ex) {
            log.warn(
                    "Could not create a security notification: {}",
                    ex.getClass().getSimpleName());
        }
    }

    private void tell(
            String accountNumber,
            String type,
            String title,
            String message,
            String reference) {

        Optional<Long> owner = accounts.ownerOf(accountNumber);

        owner.ifPresent(customerId ->
                channelsSend(customerId, type, title, message, reference));
    }

    private void channelsSend(
            Long customerId,
            String type,
            String title,
            String message,
            String reference) {

        for (NotificationChannel channel : channels) {
            try {
                channel.send(customerId, type, title, message, reference);

            } catch (RuntimeException ex) {
                log.warn(
                        "Channel {} failed: {}",
                        channel.getClass().getSimpleName(),
                        ex.getClass().getSimpleName());
            }
        }
    }
}