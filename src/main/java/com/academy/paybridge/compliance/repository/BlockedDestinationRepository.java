package com.academy.paybridge.compliance.repository;

import com.academy.paybridge.compliance.domain.BlockedDestination;
import org.springframework.data.jpa.repository.JpaRepository;

public interface BlockedDestinationRepository extends JpaRepository<BlockedDestination, Long> {

    boolean existsByDestinationKey(String destinationKey);
}
