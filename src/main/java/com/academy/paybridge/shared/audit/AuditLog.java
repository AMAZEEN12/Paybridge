package com.academy.paybridge.shared.audit;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

@Entity
@Table(name = "audit_log")
public class AuditLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(nullable = false, updatable = false)
    private String actor;

    @Column(nullable = false, updatable = false)
    private String action;

    @Column(updatable = false)
    private String reference;

    @Column(name = "request_id", updatable = false)
    private String requestId;

    @Column(updatable = false)
    private String details;

    protected AuditLog() {
    }

    public AuditLog(String actor, String action, String reference, String requestId, String details) {
        this.createdAt = Instant.now();
        this.actor = actor;
        this.action = action;
        this.reference = reference;
        this.requestId = requestId;
        this.details = details;
    }

    public Long getId() { return id; }
    public Instant getCreatedAt() { return createdAt; }
    public String getActor() { return actor; }
    public String getAction() { return action; }
    public String getReference() { return reference; }
    public String getRequestId() { return requestId; }
    public String getDetails() { return details; }
}
