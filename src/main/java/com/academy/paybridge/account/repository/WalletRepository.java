package com.academy.paybridge.account.repository;

import com.academy.paybridge.account.domain.Wallet;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface WalletRepository extends JpaRepository<Wallet, Long> {

    /** Locks the account's NGN wallet row. Callers lock in sorted account-number order. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select w from Wallet w where w.account.accountNumber = :number and w.currency = 'NGN'")
    Optional<Wallet> findNgnForUpdate(@Param("number") String number);

    /** Rows: [account_number, balance_minor] for every NGN wallet. Plain rows, so no entities are loaded. */
    @Query("select w.account.accountNumber, w.balanceMinor from Wallet w where w.currency = 'NGN'")
    List<Object[]> ngnBalances();
}