package com.tus.upload.repo;

import com.tus.upload.entity.V2Upload;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface UploadRepository extends JpaRepository<V2Upload, UUID> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select u from V2Upload u where u.id = :id")
    Optional<V2Upload> lockById(@Param("id") UUID id);
}
