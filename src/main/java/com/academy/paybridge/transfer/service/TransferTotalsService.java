package com.academy.paybridge.transfer.service;

import com.academy.paybridge.transfer.api.TransferStatus;
import com.academy.paybridge.transfer.api.TransferTotalsApi;
import com.academy.paybridge.transfer.repository.TransferRepository;
import org.springframework.stereotype.Service;

import java.time.Instant;

@Service
public class TransferTotalsService implements TransferTotalsApi {

    private final TransferRepository transfers;

    public TransferTotalsService(TransferRepository transfers) {
        this.transfers = transfers;
    }

    @Override
    public long usdSentSince(String sourceAccountNumber, Instant since) {
        return transfers.sumUsdSince(sourceAccountNumber, since, TransferStatus.FAILED);
    }
}