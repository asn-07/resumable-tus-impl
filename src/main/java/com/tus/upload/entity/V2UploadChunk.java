package com.tus.upload.entity;

import jakarta.persistence.*;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.io.Serializable;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "v2_upload_chunks")
@IdClass(V2UploadChunk.PK.class)
@Data
public class V2UploadChunk {
    @Id
    private UUID uploadId;
    @Id
    private int idx;

    private int size;
    private String checksum;
    private String storedPath;
    private Instant receivedAt;

    @PrePersist
    void prePersist() {
        receivedAt = Instant.now();
    }


    @Data
    @EqualsAndHashCode
    public static class PK implements Serializable {
        private UUID uploadId;
        private int idx;
    }


}