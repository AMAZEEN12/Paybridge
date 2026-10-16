package com.academy.paybridge.settlement.repository;

import com.academy.paybridge.settlement.domain.SettlementBatchLine;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface SettlementBatchLineRepository extends JpaRepository<SettlementBatchLine, Long> {
    List<SettlementBatchLine> findByBatchIdOrderById(Long batchId);
}