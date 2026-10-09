package com.academy.paybridge.notification.listener;

import com.academy.paybridge.account.api.AccountApi;
import com.academy.paybridge.account.api.AccountStatus;
import com.academy.paybridge.account.api.AccountStatusChangedEvent;
import com.academy.paybridge.notification.service.NotificationChannel;
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
 * AFTER_COMMIT: only tell the customer about a transfer that really committed.
 * @Async: a slow or broken notifier can never slow down or fail a transfer.
 * Every failure here is caught and logged, never rethrown.
 */
@Component
public class TransferNotificationListener {

    private static final Logger log = LoggerFactory.getLogger(TransferNotificationListener.class);

    private final AccountApi accounts;
    private final List<NotificationChannel> channels;

    public TransferNotificationListener(AccountApi accounts, List<NotificationChannel> channels) {
        this.accounts = accounts;
        this.channels = channels;
    }

    @Async("notificationExecutor")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onTransfer(TransferEvent e) {
        try {
            String src = Money.maskAccount(e.sourceAccount());
            String dst = Money.maskAccount(e.destinationAccount());
            String charges = e.chargesKobo() > 0 ? " Charges " + Money.format(e.chargesKobo()) + "." : "";
            String where = e.destinationBankName() != null ? e.destinationBankName() + " " + dst : dst;
            String ref = " Ref " + e.reference() + ".";
            switch (e.stage()) {
                case COMPLETED -> {
                    tell(e.sourceAccount(), "DEBIT", "Money sent",
                            "You sent " + Money.format(e.amountKobo()) + " to " + dst + "." + charges + ref, e.reference());
                    tell(e.destinationAccount(), "CREDIT", "Money received",
                            "You received " + Money.format(e.amountKobo()) + " from " + src + "." + ref, e.reference());
                }
                case ACCEPTED -> tell(e.sourceAccount(), "DEBIT", "Payout sent",
                        "You sent " + Money.format(e.amountKobo()) + " to " + where + "." + charges
                                + " We are waiting for the bank to confirm." + ref, e.reference());
                case SUCCEEDED -> tell(e.sourceAccount(), "TRANSFER_SUCCESS", "Payout successful",
                        "Your payout of " + Money.format(e.amountKobo()) + " to " + where + " was successful." + ref,
                        e.reference());
                case FAILED -> tell(e.sourceAccount(), "REFUND", "Payout failed, money returned",
                        "Your transfer of " + Money.format(e.amountKobo()) + " to " + where + " failed. "
                                + Money.format(e.totalDebitKobo()) + " has been returned to your account." + ref,
                        e.reference());
            }
            if (e.flagged()) {
                tell(e.sourceAccount(), "FRAUD_ALERT", "Check this payment",
                        "A payment of " + Money.format(e.amountKobo()) + " to " + where
                                + " was flagged for review. If this was not you, freeze your account now.", e.reference());
            }
        } catch (RuntimeException ex) {
            log.warn("Could not create notifications for a transfer event: {}", ex.getClass().getSimpleName());
        }
    }

    @Async("notificationExecutor")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onAccountStatus(AccountStatusChangedEvent e) {
        try {
            String what = e.status() == AccountStatus.FROZEN ? "frozen" : "unfrozen";
            channelsSend(e.customerId(), "SECURITY", "Account " + what,
                    "Your account " + Money.maskAccount(e.accountNumber()) + " was " + what + ".", null);
        } catch (RuntimeException ex) {
            log.warn("Could not create a security notification: {}", ex.getClass().getSimpleName());
        }
    }

    private void tell(String accountNumber, String type, String title, String message, String reference) {
        Optional<Long> owner = accounts.ownerOf(accountNumber);
        owner.ifPresent(customerId -> channelsSend(customerId, type, title, message, reference));
    }

    private void channelsSend(Long customerId, String type, String title, String message, String reference) {
        for (NotificationChannel channel : channels) {
            try {
                channel.send(customerId, type, title, message, reference);
            } catch (RuntimeException ex) {
                log.warn("Channel {} failed: {}", channel.getClass().getSimpleName(), ex.getClass().getSimpleName());
            }
        }
    }
}
