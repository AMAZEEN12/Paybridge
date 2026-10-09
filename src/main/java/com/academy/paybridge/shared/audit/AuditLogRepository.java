package com.academy.paybridge.shared.audit;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface AuditLogRepository extends JpaRepository<AuditLog, Long> {

    List<AuditLog> findByReferenceOrderByIdAsc(String reference);

    long countByAction(String action);
}
