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

    /** Locks one wallet row, any currency, parked or not. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select w from Wallet w where w.account.accountNumber = :number and w.currency = :currency")
    Optional<Wallet> findForUpdate(@Param("number") String number, @Param("currency") String currency);

    Optional<Wallet> findByAccountAccountNumberAndCurrency(String number, String currency);

    /** Rows: [account_number, currency, balance_minor] for every wallet of every account, parked or not. */
    @Query("select w.account.accountNumber, w.currency, w.balanceMinor from Wallet w")
    List<Object[]> allBalances();
}