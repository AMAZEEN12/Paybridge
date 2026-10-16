package com.academy.paybridge.fx.repository;

import com.academy.paybridge.fx.domain.QuoteDestination;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface QuoteDestinationRepository extends JpaRepository<QuoteDestination, UUID> {
}