package com.academy.paybridge.shared.audit;

import org.slf4j.MDC;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Writes the append-only audit diary.
 *
 * record(...)               joins the caller's transaction: if the business work rolls back, so does the row.
 * recordIndependently(...)  uses its own transaction: the row survives even if the caller rolls back.
 *                           Use it for refusals, blocks and failures, which are exactly the rows you need later.
 *
 * Never put passwords, PINs, tokens, secret keys or full account numbers into details.
 */
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
