package com.academy.paybridge.fx.repository;

import com.academy.paybridge.fx.domain.Quote;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

public interface QuoteRepository extends JpaRepository<Quote, UUID> {

    /**
     * Atomically moves a CONVERSION quote from ACTIVE to EXECUTING (R-QUO-4). Only the OWNER's quote, only while it
     * is unexpired, and only a conversion quote: a transfer quote can never run as a conversion. Returns 1 if it
     * moved, 0 if not (wrong owner, wrong type, expired, or already used).
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query("UPDATE Quote q SET q.status = com.academy.paybridge.fx.domain.QuoteStatus.EXECUTING " +
            "WHERE q.id = :id AND q.customerId = :customerId " +
            "AND q.type = com.academy.paybridge.fx.domain.QuoteType.CONVERSION " +
            "AND q.status = com.academy.paybridge.fx.domain.QuoteStatus.ACTIVE " +
            "AND q.expiresAt > :now")
    int moveToExecuting(@Param("id") UUID id, @Param("customerId") Long customerId, @Param("now") Instant now);

    /** The same for a TRANSFER quote (R-XFR-7): single use, owner only, unexpired. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query("UPDATE Quote q SET q.status = com.academy.paybridge.fx.domain.QuoteStatus.EXECUTING " +
            "WHERE q.id = :id AND q.customerId = :customerId " +
            "AND q.type = com.academy.paybridge.fx.domain.QuoteType.TRANSFER " +
            "AND q.status = com.academy.paybridge.fx.domain.QuoteStatus.ACTIVE " +
            "AND q.expiresAt > :now")
    int moveTransferToExecuting(@Param("id") UUID id, @Param("customerId") Long customerId, @Param("now") Instant now);

    /** The same single-use claim for a PAYOUT quote. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query("UPDATE Quote q SET q.status = com.academy.paybridge.fx.domain.QuoteStatus.EXECUTING " +
            "WHERE q.id = :id AND q.customerId = :customerId " +
            "AND q.type = com.academy.paybridge.fx.domain.QuoteType.PAYOUT " +
            "AND q.status = com.academy.paybridge.fx.domain.QuoteStatus.ACTIVE " +
            "AND q.expiresAt > :now")
    int movePayoutToExecuting(@Param("id") UUID id, @Param("customerId") Long customerId, @Param("now") Instant now);

    /** Counts unexpired ACTIVE quotes for a customer (R-QUO-7). */
    @Query("SELECT COUNT(q) FROM Quote q WHERE q.customerId = :customerId " +
            "AND q.status = com.academy.paybridge.fx.domain.QuoteStatus.ACTIVE " +
            "AND q.expiresAt > :now")
    long countActiveQuotes(@Param("customerId") Long customerId, @Param("now") Instant now);

    /**
     * Completed and executing CONVERSIONS in USD minor units since the start of the customer's day (R-LIM-2).
     * Transfer and payout quotes are not counted: conversions keep their own total (R-LIM2-3, acceptance 21d).
     */
    @Query("SELECT COALESCE(SUM(q.usdEquivalentMinor), 0) FROM Quote q " +
            "WHERE q.customerId = :customerId " +
            "AND q.type = com.academy.paybridge.fx.domain.QuoteType.CONVERSION " +
            "AND q.status IN (com.academy.paybridge.fx.domain.QuoteStatus.COMPLETED, " +
            "com.academy.paybridge.fx.domain.QuoteStatus.EXECUTING) " +
            "AND q.createdAt >= :startOfDay")
    long sumOutgoingUsdMinorToday(@Param("customerId") Long customerId, @Param("startOfDay") Instant startOfDay);

    /** A customer's payouts in USD minor units that are EXECUTING or COMPLETED since {@code since} (R-LIM2-3). */
    @Query("SELECT COALESCE(SUM(q.usdEquivalentMinor), 0) FROM Quote q " +
            "WHERE q.customerId = :customerId " +
            "AND q.type = com.academy.paybridge.fx.domain.QuoteType.PAYOUT " +
            "AND q.status IN (com.academy.paybridge.fx.domain.QuoteStatus.COMPLETED, " +
            "com.academy.paybridge.fx.domain.QuoteStatus.EXECUTING) " +
            "AND q.createdAt >= :since")
    long sumPayoutUsdSince(@Param("customerId") Long customerId, @Param("since") Instant since);

    /** Conversions in the velocity window (R-LIM-4). */
    @Query("SELECT COUNT(q) FROM Quote q WHERE q.customerId = :customerId " +
            "AND q.type = com.academy.paybridge.fx.domain.QuoteType.CONVERSION " +
            "AND q.status IN (com.academy.paybridge.fx.domain.QuoteStatus.COMPLETED, " +
            "com.academy.paybridge.fx.domain.QuoteStatus.EXECUTING) " +
            "AND q.createdAt >= :since")
    int countRecentConversions(@Param("customerId") Long customerId, @Param("since") Instant since);

    /** Ends an EXECUTING quote as FAILED and records why. Does nothing if it is no longer EXECUTING. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query("UPDATE Quote q SET q.status = com.academy.paybridge.fx.domain.QuoteStatus.FAILED, " +
            "q.failureReason = :reason " +
            "WHERE q.id = :id AND q.status = com.academy.paybridge.fx.domain.QuoteStatus.EXECUTING")
    int markFailed(@Param("id") UUID id, @Param("reason") String reason);

    /** Clean-up: ACTIVE quotes whose time ran out become EXPIRED (R-QUO-3). One statement, no loaded entities. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query("UPDATE Quote q SET q.status = com.academy.paybridge.fx.domain.QuoteStatus.EXPIRED " +
            "WHERE q.status = com.academy.paybridge.fx.domain.QuoteStatus.ACTIVE AND q.expiresAt <= :now")
    int expireOverdue(@Param("now") Instant now);

    /**
     * Clean-up (R-QUO-8): an EXECUTING quote was confirmed before it expired, so one whose expiry passed more than
     * the stuck limit ago has been executing at least that long. Conditional, so a quote that completes in the
     * meantime is never overwritten. PAYOUT quotes are left alone: a payout can stay PENDING for a long time and
     * its quote ends with the remittance (settle or refund), not with this job.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query("UPDATE Quote q SET q.status = com.academy.paybridge.fx.domain.QuoteStatus.FAILED, " +
            "q.failureReason = :reason " +
            "WHERE q.status = com.academy.paybridge.fx.domain.QuoteStatus.EXECUTING " +
            "AND q.type <> com.academy.paybridge.fx.domain.QuoteType.PAYOUT " +
            "AND q.expiresAt < :stuckBefore")
    int failStuck(@Param("stuckBefore") Instant stuckBefore, @Param("reason") String reason);
}