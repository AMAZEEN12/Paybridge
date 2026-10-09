package com.academy.paybridge.notification.service;

import com.academy.paybridge.customer.api.CustomerApi;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Pretends to email the customer by writing a log line. Replace with a class that really sends mail
 * (Spring Mail plus a provider) and nothing else in the app changes. The email is masked in the log.
 */
@Component
public class LoggingEmailChannel implements NotificationChannel {

    private static final Logger log = LoggerFactory.getLogger(LoggingEmailChannel.class);

    private final CustomerApi customers;

    public LoggingEmailChannel(CustomerApi customers) {
        this.customers = customers;
    }

    @Override
    public void send(Long customerId, String type, String title, String message, String reference) {
        String email = customers.get(customerId).email();
        int at = email.indexOf('@');
        String masked = at > 1 ? email.charAt(0) + "***" + email.substring(at) : "***";
        log.info("[email to {}] {}", masked, title);
    }
}
