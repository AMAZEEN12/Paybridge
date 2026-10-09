package com.academy.paybridge.notification.repository;

import com.academy.paybridge.notification.domain.Notification;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Optional;

public interface NotificationRepository extends JpaRepository<Notification, Long> {

    Page<Notification> findByCustomerIdOrderByIdDesc(Long customerId, Pageable pageable);

    Page<Notification> findByCustomerIdAndReadAtIsNullOrderByIdDesc(Long customerId, Pageable pageable);

    long countByCustomerIdAndReadAtIsNull(Long customerId);

    Optional<Notification> findByIdAndCustomerId(Long id, Long customerId);

    @Modifying
    @Query("update Notification n set n.readAt = :now where n.customerId = :customerId and n.readAt is null")
    int markAllRead(@Param("customerId") Long customerId, @Param("now") Instant now);
}
