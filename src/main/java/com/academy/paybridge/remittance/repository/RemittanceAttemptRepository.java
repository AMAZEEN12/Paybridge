package com.academy.paybridge.remittance.repository;

import com.academy.paybridge.remittance.domain.RemittanceAttempt;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface RemittanceAttemptRepository extends JpaRepository<RemittanceAttempt, Long> {

    List<RemittanceAttempt> findByRemittanceIdOrderByAttemptAsc(Long remittanceId);

    Optional<RemittanceAttempt> findByCustomerReference(String customerReference);
}