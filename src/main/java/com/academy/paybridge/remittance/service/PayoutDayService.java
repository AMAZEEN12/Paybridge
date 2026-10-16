package com.academy.paybridge.remittance.service;

import com.academy.paybridge.remittance.api.PayoutDayApi;
import com.academy.paybridge.remittance.repository.RemittanceRepository;
import com.academy.paybridge.shared.api.DayItem;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

@Service
public class PayoutDayService implements PayoutDayApi {

    private final RemittanceRepository remittances;

    public PayoutDayService(RemittanceRepository remittances) {
        this.remittances = remittances;
    }

    @Override
    @Transactional(readOnly = true)
    public List<DayItem> payoutsBetween(Instant from, Instant to) {
        return remittances.dayItems(from, to);
    }
}