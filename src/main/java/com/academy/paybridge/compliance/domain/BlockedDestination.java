package com.academy.paybridge.compliance.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

@Entity
@Table(name = "blocked_destinations")
public class BlockedDestination {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "destination_key", nullable = false)
    private String destinationKey;

    private String reason;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    protected BlockedDestination() {
    }

    public BlockedDestination(String destinationKey, String reason) {
        this.destinationKey = destinationKey;
        this.reason = reason;
    }

    public Long getId() { return id; }
    public String getDestinationKey() { return destinationKey; }
    public String getReason() { return reason; }
    public Instant getCreatedAt() { return createdAt; }
}
