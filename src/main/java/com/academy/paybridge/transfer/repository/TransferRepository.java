package com.academy.paybridge.transfer.repository;

import com.academy.paybridge.shared.api.DayItem;
import com.academy.paybridge.transfer.api.TransferStatus;
import com.academy.paybridge.transfer.api.TransferType;
import com.academy.paybridge.transfer.domain.Transfer;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface TransferRepository extends JpaRepository<Transfer, Long> {

    Optional<Transfer> findByReference(String reference);

    /** Locks the transfer row, so a webhook, a verify call and the scheduler cannot finalise it twice. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select t from Transfer t where t.reference = :reference")
    Optional<Transfer> findForUpdate(@Param("reference") String reference);

    Optional<Transfer> findBySourceAccountNumberAndIdempotencyKey(String sourceAccountNumber, String idempotencyKey);

    @Query("select t from Transfer t where t.sourceAccountNumber = :n or t.destinationAccountNumber = :n order by t.id desc")
    Page<Transfer> history(@Param("n") String accountNumber, Pageable pageable);

    List<Transfer> findTop50ByStatusAndTypeAndCreatedAtBeforeOrderByCreatedAtAsc(
            TransferStatus status, TransferType type, Instant before);

    // ----- facts for the fraud rules -----
// The fraud thresholds are in kobo, so these two sums count Naira rows only (plan 002-004 A1, R-DAT-3).
    @Query("select coalesce(sum(t.amountMinor), 0L) from Transfer t "
            + "where t.sourceAccountNumber = :n and t.currency = 'NGN' "
            + "and t.createdAt >= :since and t.status <> :failed")
    long sumOutgoingSince(@Param("n") String account, @Param("since") Instant since,
                          @Param("failed") TransferStatus failed);

    @Query("select count(t) from Transfer t "
            + "where t.sourceAccountNumber = :n and t.createdAt >= :since and t.status <> :failed")
    long countOutgoingSince(@Param("n") String account, @Param("since") Instant since,
                            @Param("failed") TransferStatus failed);

    @Query("select count(t) from Transfer t where t.sourceAccountNumber = :src "
            + "and t.destinationAccountNumber = :dst and coalesce(t.destinationBankCode, '') = :bank "
            + "and t.status <> :failed")
    long countPreviousTo(@Param("src") String source, @Param("dst") String destination,
                         @Param("bank") String bankCodeOrEmpty, @Param("failed") TransferStatus failed);

    // ... countOutgoingSince and countPreviousTo are unchanged ...

    @Query("select coalesce(sum(t.amountMinor), 0L) from Transfer t "
            + "where t.destinationAccountNumber = :n and t.currency = 'NGN' "
            + "and t.type = :type and t.status = :ok and t.createdAt >= :since")
    long sumIncomingSince(@Param("n") String account, @Param("type") TransferType type,
                          @Param("ok") TransferStatus ok, @Param("since") Instant since);

    /**
     * Non-NGN transfers the customer's account has sent since the start of the day, in USD cents (R-LIM2-3).
     * Failed transfers do not count. NGN-to-NGN rows hold 0, so they add nothing.
     */
    @Query("select coalesce(sum(t.usdEquivalentMinor), 0L) from Transfer t "
            + "where t.sourceAccountNumber = :n and t.createdAt >= :since and t.status <> :failed")
    long sumUsdSince(@Param("n") String account, @Param("since") Instant since,
                     @Param("failed") TransferStatus failed);

    /** Internal transfers created in [from, to) for the settlement batch. */
    @Query("SELECT new com.academy.paybridge.shared.api.DayItem(t.reference, 'TRANSFER', t.currency, " +
            "t.amountMinor, CAST(t.status AS string)) FROM Transfer t " +
            "WHERE t.type = com.academy.paybridge.transfer.api.TransferType.INTERNAL " +
            "AND t.createdAt >= :from AND t.createdAt < :to ORDER BY t.createdAt, t.id")
    List<DayItem> internalDayItems(@Param("from") Instant from, @Param("to") Instant to);
}
