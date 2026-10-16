package com.academy.paybridge.remittance.repository;

import com.academy.paybridge.remittance.domain.Remittance;
import com.academy.paybridge.remittance.domain.RemittanceStatus;
import com.academy.paybridge.shared.api.DayItem;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface RemittanceRepository extends JpaRepository<Remittance, Long> {

    Optional<Remittance> findByReference(String reference);

    Optional<Remittance> findByQuoteId(UUID quoteId);

    Optional<Remittance> findBySourceAccountNumberAndIdempotencyKey(String sourceAccountNumber, String idempotencyKey);

    List<Remittance> findByCustomerIdOrderByCreatedAtDesc(Long customerId, Pageable pageable);

    /** The settle step locks the row, so a webhook, a verify and the job cannot all act on one payout (R-PAY-8). */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT r FROM Remittance r WHERE r.reference = :reference")
    Optional<Remittance> lockByReference(@Param("reference") String reference);

    /** Payouts still waiting for an outcome that have not changed since {@code before} (the scheduled job). */
    @Query("SELECT r FROM Remittance r WHERE r.status IN :statuses AND r.updatedAt < :before ORDER BY r.updatedAt")
    List<Remittance> findStale(@Param("statuses") Collection<RemittanceStatus> statuses,
                               @Param("before") Instant before, Pageable pageable);

    /** Payouts created in [from, to) for the settlement batch (kind PAYOUT, in the payout currency). */
    @Query("SELECT new com.academy.paybridge.shared.api.DayItem(r.reference, 'PAYOUT', r.payoutCurrency, " +
            "r.payoutAmountMinor, CAST(r.status AS string)) FROM Remittance r " +
            "WHERE r.createdAt >= :from AND r.createdAt < :to ORDER BY r.createdAt, r.id")
    List<DayItem> dayItems(@Param("from") Instant from, @Param("to") Instant to);
}