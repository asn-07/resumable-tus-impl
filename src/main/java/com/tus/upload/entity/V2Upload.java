package com.tus.upload.entity;

import jakarta.persistence.*;
import lombok.Data;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "v2_uploads")
@Data
public class V2Upload {
    @Id
    private UUID id;

    private String filename;
    private String contentType;
    private long totalSize;
    private int chunkSize;
    private int totalChunks;

    @Enumerated(EnumType.STRING)
    private Status status = Status.PENDING;

    private Instant createdAt;
    private Instant updatedAt;

    public enum Status { PENDING, IN_PROGRESS, COMPLETED, FAILED, CANCELLED }

    @PrePersist void prePersist() {
        createdAt = Instant.now();
        updatedAt = createdAt;
    }
    @PreUpdate void preUpdate() { updatedAt = Instant.now(); }
}