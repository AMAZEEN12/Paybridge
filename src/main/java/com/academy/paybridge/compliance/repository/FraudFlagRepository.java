package com.academy.paybridge.compliance.repository;

import com.academy.paybridge.compliance.domain.FraudFlag;
import org.springframework.data.jpa.repository.JpaRepository;

public interface FraudFlagRepository extends JpaRepository<FraudFlag, Long> {

    long countByAccountNumber(String accountNumber);
}
