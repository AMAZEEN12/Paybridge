package com.academy.paybridge.notification.service;

import com.academy.paybridge.notification.domain.Notification;
import com.academy.paybridge.notification.repository.NotificationRepository;
import com.academy.paybridge.shared.exception.ApiException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;

@Service
public class NotificationService {

    private final NotificationRepository repository;
    private final Clock clock;

    public NotificationService(NotificationRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    /** Own transaction: the listener runs after the transfer's transaction has finished. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void create(Long customerId, String type, String title, String message, String reference) {
        repository.save(new Notification(customerId, type, title, message, reference));
    }

    @Transactional(readOnly = true)
    public Page<Notification> list(Long customerId, boolean unreadOnly, int page, int size) {
        PageRequest pageable = PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), 100));
        return unreadOnly
                ? repository.findByCustomerIdAndReadAtIsNullOrderByIdDesc(customerId, pageable)
                : repository.findByCustomerIdOrderByIdDesc(customerId, pageable);
    }

    @Transactional(readOnly = true)
    public long unreadCount(Long customerId) {
        return repository.countByCustomerIdAndReadAtIsNull(customerId);
    }

    @Transactional
    public void markRead(Long customerId, Long id) {
        Notification n = repository.findByIdAndCustomerId(id, customerId)
                .orElseThrow(() -> ApiException.notFound("NOTIFICATION_NOT_FOUND", "Notification not found."));
        if (n.getReadAt() == null) {
            n.setReadAt(clock.instant());
            repository.save(n);
        }
    }

    @Transactional
    public int markAllRead(Long customerId) {
        return repository.markAllRead(customerId, clock.instant());
    }
}
