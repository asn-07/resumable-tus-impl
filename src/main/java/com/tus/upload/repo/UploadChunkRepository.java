package com.tus.upload.repo;


import com.tus.upload.entity.V2UploadChunk;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface UploadChunkRepository extends JpaRepository<V2UploadChunk, V2UploadChunk.PK> {
    @Query("select c.idx from V2UploadChunk c where c.uploadId = :uploadId")
    List<Integer> findReceivedIndexes(@Param("uploadId") UUID uploadId);

    long countByUploadId(UUID uploadId);
}