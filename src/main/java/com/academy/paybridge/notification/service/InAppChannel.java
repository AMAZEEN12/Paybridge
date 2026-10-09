package com.academy.paybridge.notification.service;

import org.springframework.stereotype.Component;

/** Saves the notification so the customer can read it in the app. */
@Component
public class InAppChannel implements NotificationChannel {

    private final NotificationService service;

    public InAppChannel(NotificationService service) {
        this.service = service;
    }

    @Override
    public void send(Long customerId, String type, String title, String message, String reference) {
        service.create(customerId, type, title, message, reference);
    }
}
