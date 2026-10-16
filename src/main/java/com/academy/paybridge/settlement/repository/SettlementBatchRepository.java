package com.academy.paybridge.settlement.repository;

import com.academy.paybridge.settlement.domain.SettlementBatch;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;

public interface SettlementBatchRepository extends JpaRepository<SettlementBatch, Long> {

    boolean existsByPartnerAndCurrencyAndPeriodDate(String partner, String currency, LocalDate periodDate);

    boolean existsByPeriodDate(LocalDate periodDate);

    List<SettlementBatch> findByPeriodDateOrderByPartnerAscCurrencyAsc(LocalDate periodDate);
}