package com.academy.paybridge.settlement.repository;

import com.academy.paybridge.settlement.domain.PartnerStatementLine;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface PartnerStatementLineRepository extends JpaRepository<PartnerStatementLine, Long> {
    List<PartnerStatementLine> findByBatchIdOrderById(Long batchId);
}