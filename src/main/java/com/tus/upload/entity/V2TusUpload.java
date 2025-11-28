package com.tus.upload.entity;

import jakarta.persistence.*;
import lombok.Data;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "v2_tus_uploads")
@Data
public class V2TusUpload {
    @Id
    @GeneratedValue
    private UUID id;

    private String tusId;
    private Long uploadLength;
    private Long uploadOffset = 0L;
    private String metadata;
    private String filename;
    private String filetype;
    private String tempPath;
    private String finalPath;

    @Enumerated(EnumType.STRING)
    private Status status = Status.PENDING;

    private Instant createdAt;
    private Instant updatedAt;

    public enum Status { PENDING, IN_PROGRESS, COMPLETED, CANCELLED, FAILED }

    @PrePersist
    void prePersist() {
        createdAt = Instant.now();
        updatedAt = createdAt;
    }

    @PreUpdate
    void preUpdate() {
        updatedAt = Instant.now();
    }
}
