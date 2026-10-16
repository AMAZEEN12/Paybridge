package com.academy.paybridge.transfer.service;

import com.academy.paybridge.shared.api.DayItem;
import com.academy.paybridge.transfer.api.TransferDayApi;
import com.academy.paybridge.transfer.repository.TransferRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

@Service
public class TransferDayService implements TransferDayApi {

    private final TransferRepository transfers;

    public TransferDayService(TransferRepository transfers) {
        this.transfers = transfers;
    }

    @Override
    @Transactional(readOnly = true)
    public List<DayItem> internalTransfersBetween(Instant from, Instant to) {
        return transfers.internalDayItems(from, to);
    }
}