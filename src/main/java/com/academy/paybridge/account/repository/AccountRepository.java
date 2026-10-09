package com.academy.paybridge.account.repository;

import com.academy.paybridge.account.domain.Account;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface AccountRepository extends JpaRepository<Account, Long> {

    Optional<Account> findByAccountNumber(String accountNumber);

    boolean existsByAccountNumber(String accountNumber);

    long countByCustomerIdAndType(Long customerId, com.academy.paybridge.account.api.AccountType type);

    List<Account> findByCustomerIdOrderByIdAsc(Long customerId);

    /** SELECT ... FOR UPDATE. Anyone else asking for this row waits until our transaction ends. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from Account a where a.accountNumber = :number")
    Optional<Account> findForUpdate(@Param("number") String number);
}
