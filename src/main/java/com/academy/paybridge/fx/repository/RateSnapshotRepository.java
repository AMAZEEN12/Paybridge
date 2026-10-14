package com.academy.paybridge.fx.repository;

import com.academy.paybridge.fx.domain.RateSnapshot;

import java.util.Optional;

public interface RateSnapshotRepository {

    /** Saves the snapshot in its own short transaction and returns it with its new id. */
    RateSnapshot save(RateSnapshot snapshot);

    Optional<RateSnapshot> findLatest();
}