package com.tus.upload.repo;

import com.tus.upload.entity.V2TusUpload;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface TusUploadRepository extends JpaRepository<V2TusUpload, UUID> {
    Optional<V2TusUpload> findByTusId(String tusId);
}
