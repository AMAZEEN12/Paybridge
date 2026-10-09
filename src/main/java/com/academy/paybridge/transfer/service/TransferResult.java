package com.academy.paybridge.transfer.service;

import com.academy.paybridge.transfer.domain.Transfer;

/** The transfer plus whether this was a replay of an earlier request with the same Idempotency-Key. */
public record TransferResult(Transfer transfer, boolean replayed) {
}
