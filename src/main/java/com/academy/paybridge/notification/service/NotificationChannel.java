package com.academy.paybridge.notification.service;

/** One way of telling a customer something. Add a new class to add a new channel (real email, SMS). */
public interface NotificationChannel {

    void send(Long customerId, String type, String title, String message, String reference);
}
