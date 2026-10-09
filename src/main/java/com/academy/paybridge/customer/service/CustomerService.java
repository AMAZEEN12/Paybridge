package com.academy.paybridge.customer.service;

import com.academy.paybridge.customer.api.CustomerApi;
import com.academy.paybridge.customer.api.CustomerView;
import com.academy.paybridge.customer.domain.Customer;
import com.academy.paybridge.customer.repository.CustomerRepository;
import com.academy.paybridge.shared.audit.AuditService;
import com.academy.paybridge.shared.exception.ApiException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

@Service
public class CustomerService implements CustomerApi {

    private final CustomerRepository customers;
    private final PasswordEncoder encoder;
    private final AuditService audit;
    private final Clock clock;
    private final int pinMaxAttempts;
    private final int pinLockMinutes;

    public CustomerService(CustomerRepository customers, PasswordEncoder encoder, AuditService audit, Clock clock,
                           @Value("${paybridge.fraud.pin-max-attempts:3}") int pinMaxAttempts,
                           @Value("${paybridge.fraud.pin-lock-minutes:30}") int pinLockMinutes) {
        this.customers = customers;
        this.encoder = encoder;
        this.audit = audit;
        this.clock = clock;
        this.pinMaxAttempts = pinMaxAttempts;
        this.pinLockMinutes = pinLockMinutes;
    }

    @Override
    @Transactional(readOnly = true)
    public CustomerView get(Long customerId) {
        Customer c = customers.findById(customerId)
                .orElseThrow(() -> ApiException.notFound("CUSTOMER_NOT_FOUND", "Customer not found."));
        return view(c);
    }

    @Override
    @Transactional(noRollbackFor = ApiException.class)
    public void verifyPin(Long customerId, String pin) {
        Customer c = customers.findById(customerId)
                .orElseThrow(() -> ApiException.forbidden("PIN_INVALID", "Incorrect PIN."));
        Instant now = clock.instant();
        if (c.getPinLockedUntil() != null && c.getPinLockedUntil().isAfter(now)) {
            throw ApiException.forbidden("PIN_LOCKED", "Too many wrong PIN attempts. Try again later.");
        }
        // "No PIN set" and "wrong PIN" give the same answer, so nothing is leaked.
        if (c.getPinHash() == null || pin == null || !encoder.matches(pin, c.getPinHash())) {
            if (c.getPinHash() != null) {
                int attempts = c.getPinFailedAttempts() + 1;
                if (attempts >= pinMaxAttempts) {
                    c.setPinLockedUntil(now.plus(pinLockMinutes, ChronoUnit.MINUTES));
                    c.setPinFailedAttempts(0);
                    audit.recordIndependently("customer:" + customerId, "PIN_LOCKED", null, "Too many wrong PIN attempts");
                } else {
                    c.setPinFailedAttempts(attempts);
                }
                customers.save(c);
            }
            throw ApiException.forbidden("PIN_INVALID", "Incorrect PIN.");
        }
        if (c.getPinFailedAttempts() != 0 || c.getPinLockedUntil() != null) {
            c.setPinFailedAttempts(0);
            c.setPinLockedUntil(null);
            customers.save(c);
        }
    }

    /**
     * Sets or changes the PIN. The caller (the controller) must already have checked the current PIN
     * with verifyPin() when one exists, so that a wrong attempt is counted in its own transaction.
     */
    @Transactional
    public void setPin(Long customerId, String newPin) {
        Customer c = customers.findById(customerId)
                .orElseThrow(() -> ApiException.notFound("CUSTOMER_NOT_FOUND", "Customer not found."));
        c.setPinHash(encoder.encode(newPin));
        c.setPinFailedAttempts(0);
        c.setPinLockedUntil(null);
        customers.save(c);
        audit.record("customer:" + customerId, "PIN_SET", null, null);
    }

    static CustomerView view(Customer c) {
        return new CustomerView(c.getId(), c.getFullName(), c.getEmail(), c.getPinHash() != null);
    }
}
