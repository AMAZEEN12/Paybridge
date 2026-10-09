package com.academy.paybridge.transfer.repository;

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

    @Query("select coalesce(sum(t.amountKobo), 0L) from Transfer t "
            + "where t.sourceAccountNumber = :n and t.createdAt >= :since and t.status <> :failed")
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

    @Query("select coalesce(sum(t.amountKobo), 0L) from Transfer t "
            + "where t.destinationAccountNumber = :n and t.type = :type and t.status = :ok and t.createdAt >= :since")
    long sumIncomingSince(@Param("n") String account, @Param("type") TransferType type,
                          @Param("ok") TransferStatus ok, @Param("since") Instant since);
}
