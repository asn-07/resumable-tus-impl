package com.tus.upload.service;


import com.tus.upload.entity.V2Upload;
import com.tus.upload.entity.V2UploadChunk;
import com.tus.upload.repo.UploadChunkRepository;
import com.tus.upload.repo.UploadRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.*;
import java.util.stream.Stream;

@Service
public class UploadService {
    private final UploadRepository uploads;
    private final UploadChunkRepository chunks;
    private final Path tempDir;
    private final Path finalDir;

    public UploadService(
            UploadRepository uploads,
            UploadChunkRepository chunks,
            @Value("${app.storage.temp-dir}") String tempDir,
            @Value("${app.storage.final-dir}") String finalDir
    ) throws IOException {
        this.uploads = uploads;
        this.chunks = chunks;
        this.tempDir = Path.of(tempDir);
        this.finalDir = Path.of(finalDir);
        Files.createDirectories(this.tempDir);
        Files.createDirectories(this.finalDir);
    }

    @Transactional
    public V2Upload initUpload(String filename, String contentType, long totalSize, int chunkSize) {
        int totalChunks = Math.toIntExact((totalSize + chunkSize - 1) / chunkSize);
        V2Upload u = new V2Upload();
        u.setId(UUID.randomUUID());
        u.setFilename(filename);
        u.setContentType(contentType);
        u.setTotalSize(totalSize);
        u.setChunkSize(chunkSize);
        u.setTotalChunks(totalChunks);
        u.setStatus(V2Upload.Status.PENDING);
        uploads.save(u);
        return u;
    }

    @Transactional
    public void storeChunk(UUID uploadId, int idx, InputStream bodyStream, Integer declaredSize, String checksum) throws IOException {
        V2Upload u = uploads.lockById(uploadId).orElseThrow(() -> new NoSuchElementException("Upload not found"));
        if (u.getStatus() == V2Upload.Status.COMPLETED || u.getStatus() == V2Upload.Status.CANCELLED) {
            throw new IllegalStateException("Upload not accepting chunks");
        }
        if (idx < 0 || idx >= u.getTotalChunks()) throw new IllegalArgumentException("Invalid chunk index");

        // Avoid duplicate chunk writes
        V2UploadChunk.PK pk = new V2UploadChunk.PK();
        pk.setUploadId(uploadId);
        pk.setIdx(idx);
        if (chunks.existsById(pk)) return; // idempotent

        Path sessionDir = tempDir.resolve(uploadId.toString());
        Files.createDirectories(sessionDir);

        Path chunkPath = sessionDir.resolve("chunk-" + idx);
        try (OutputStream os = Files.newOutputStream(chunkPath, StandardOpenOption.CREATE_NEW)) {
            int total = bodyStream.transferTo(os) >= 0 ? (int) Files.size(chunkPath) : 0;
            if (declaredSize != null && declaredSize != total) {
                Files.deleteIfExists(chunkPath);
                throw new IllegalArgumentException("Chunk size mismatch");
            }
        }

        V2UploadChunk c = new V2UploadChunk();
        c.setUploadId(uploadId);
        c.setIdx(idx);
        c.setSize((int) Files.size(chunkPath));
        c.setChecksum(checksum);
        c.setStoredPath(chunkPath.toString());
        chunks.save(c);

        // Move status to IN_PROGRESS on first chunk
        if (u.getStatus() == V2Upload.Status.PENDING) {
            u.setStatus(V2Upload.Status.IN_PROGRESS);
            uploads.save(u);
        }
    }

    @Transactional(readOnly = true)
    public List<Integer> missingChunks(UUID uploadId) {
        V2Upload u = uploads.findById(uploadId).orElseThrow();
        Set<Integer> received = new HashSet<>(chunks.findReceivedIndexes(uploadId));
        List<Integer> missing = new ArrayList<>();
        for (int i = 0; i < u.getTotalChunks(); i++) {
            if (!received.contains(i)) missing.add(i);
        }
        return missing;
    }

    @Transactional
    public void commit(UUID uploadId) throws IOException {
        V2Upload u = uploads.lockById(uploadId)
                .orElseThrow(() -> new NoSuchElementException("Upload not found"));

        // Step 1: Verify all chunks exist
        List<Integer> missing = missingChunks(uploadId);
        if (!missing.isEmpty()) {
            throw new IllegalStateException("Cannot commit. Missing chunks: " + missing);
        }

        Path sessionDir = tempDir.resolve(uploadId.toString());
        Path finalPath = finalDir.resolve(u.getFilename());

        // Step 2: Assemble chunks in order
        try (OutputStream out = Files.newOutputStream(finalPath,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
            for (int i = 0; i < u.getTotalChunks(); i++) {
                Path chunkPath = sessionDir.resolve("chunk-" + i);
                if (!Files.exists(chunkPath)) {
                    throw new IllegalStateException("Chunk file missing on disk: " + chunkPath);
                }
                try (InputStream in = Files.newInputStream(chunkPath)) {
                    in.transferTo(out);
                }
            }
        }

        // Step 3: Validate final size
        long finalSize = Files.size(finalPath);
        if (finalSize != u.getTotalSize()) {
            u.setStatus(V2Upload.Status.FAILED);
            uploads.save(u);
            throw new IllegalStateException(
                    "Final size mismatch. Expected " + u.getTotalSize() + " but got " + finalSize
            );
        }

        // Step 4: Mark complete and clean up
        u.setStatus(V2Upload.Status.COMPLETED);
        uploads.save(u);

        try (Stream<Path> paths = Files.list(sessionDir)) {
            paths.forEach(p -> { try { Files.deleteIfExists(p); } catch (IOException ignored) {} });
        }
        Files.deleteIfExists(sessionDir);
    }


    @Transactional
    public void cancel(UUID uploadId) throws IOException {
        V2Upload u = uploads.lockById(uploadId).orElseThrow();
        u.setStatus(V2Upload.Status.CANCELLED);
        uploads.save(u);
        Path sessionDir = tempDir.resolve(uploadId.toString());
        if (Files.exists(sessionDir)) {
            try (Stream<Path> paths = Files.list(sessionDir)) {
                paths.forEach(p -> { try { Files.deleteIfExists(p); } catch (IOException ignored) {} });
            }
            Files.deleteIfExists(sessionDir);
        }
    }

    @Transactional(readOnly = true)
    public V2Upload getUpload(UUID id) { return uploads.findById(id).orElseThrow(); }
}
