package com.academy.paybridge.shared.audit;

import org.slf4j.MDC;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuditService {

    private final AuditLogRepository repository;

    public AuditService(AuditLogRepository repository) {

        this.repository = repository;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void record(String actor, String action, String reference, String details) {
        repository.save(new AuditLog(actor, action, reference, MDC.get("requestId"), trim(details)));
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordIndependently(String actor, String action, String reference, String details) {
        repository.save(new AuditLog(actor, action, reference, MDC.get("requestId"), trim(details)));
    }

    private static String trim(String s) {
        if (s == null) {
            return null;
        }
        return s.length() <= 1000 ? s : s.substring(0, 1000);
    }
}
