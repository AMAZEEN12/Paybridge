package com.academy.paybridge.account.repository;

import com.academy.paybridge.account.domain.FundingDetails;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface FundingDetailsRepository extends JpaRepository<FundingDetails, Long> {

    Optional<FundingDetails> findByWalletId(Long walletId);
}