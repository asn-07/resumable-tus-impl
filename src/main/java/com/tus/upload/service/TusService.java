package com.tus.upload.service;

import com.tus.upload.entity.V2TusUpload;
import com.tus.upload.repo.AssetExifNativeRepository;
import com.tus.upload.repo.AssetRepository;
import com.tus.upload.repo.TusUploadRepository;
import com.tus.upload.repo.UserRepository;
import com.tus.upload.common.entity.Asset;
import com.tus.upload.common.entity.AssetExif;
import com.tus.upload.common.entity.User;
import com.tus.upload.common.enums.AssetType;
import com.tus.upload.common.enums.AssetVisibility;
import com.tus.upload.common.utils.TusAppUtils;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.*;

@Service
@RequiredArgsConstructor
@Slf4j
public class TusService {

    private final TusUploadRepository repo;

    @Value("${app.storage.temp-dir}")
    private String tempDir;

    @Value("${app.storage.final-dir}")
    private String finalDir;

    private final RedisTemplate<String, Object> redisTemplate;
    private final UserRepository userRepository;
    private final AssetRepository assetRepository;
    private final AssetExifNativeRepository assetExifRepository;

    @Transactional
    public String create(HttpServletRequest req) throws Exception {
        Long length = Long.parseLong(req.getHeader("Upload-Length"));
        String metadata = req.getHeader("Upload-Metadata");
        Map<String, String> headerData = parseUploadMetadata(metadata);

        V2TusUpload upload = new V2TusUpload();
        upload.setTusId(UUID.randomUUID().toString());
        upload.setUploadLength(length);
        upload.setMetadata(metadata);
        upload.setFilename(headerData.get("filename"));
        upload.setFiletype(headerData.get("filetype"));

        Path temp = Path.of(tempDir, upload.getTusId() + ".bin");
        Files.createDirectories(temp.getParent());
        Files.write(temp, new byte[0], StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        upload.setTempPath(temp.toString());
        repo.save(upload);
        return upload.getTusId();
    }

    /**
     * Appends data to the temp file.
     * This method is NOT transactional to avoid holding locks during file I/O.
     */
    public V2TusUpload append(String tusId, long clientOffset, InputStream body, String userId) throws Exception {
        V2TusUpload tusUploadRecord = repo.findByTusId(tusId).orElseThrow(() -> new IllegalStateException("Upload not found: " + tusId));
        if (!Objects.equals(tusUploadRecord.getUploadOffset(), clientOffset)) {
            throw new IllegalStateException("Offset mismatch");
        }

        Path temp = Path.of(tusUploadRecord.getTempPath());
        try (FileChannel channel = FileChannel.open(temp, StandardOpenOption.WRITE)) {
            channel.position(tusUploadRecord.getUploadOffset());
            byte[] buf = new byte[128 * 1024];
            int read;
            long written = 0;
            while ((read = body.read(buf)) != -1) {
                channel.write(ByteBuffer.wrap(buf, 0, read));
                written += read;
            }
            tusUploadRecord.setUploadOffset(tusUploadRecord.getUploadOffset() + written); // Update in-memory object
        }

        if (tusUploadRecord.getUploadOffset().equals(tusUploadRecord.getUploadLength())) {
            Path tempFile = Path.of(tusUploadRecord.getTempPath());
            byte[] checksumBytes = TusAppUtils.calculateChecksum(tempFile.toFile());
            log.info("Upload complete for tusId={}, calculated checksum={}", tusId, TusAppUtils.bytesToHex(checksumBytes));
            // (DB-in) Run all DB logic in a single, fast transaction
            Asset asset = processCompletedUpload(tusUploadRecord, userId, checksumBytes);

            // Queue async jobs (Outside transaction)
            queueAsyncJobs(asset);
        } else {
            updateUploadProgress(tusUploadRecord);
        }

        return tusUploadRecord;
    }

    @Transactional
    protected void updateUploadProgress(V2TusUpload u) {
        u.setStatus(V2TusUpload.Status.IN_PROGRESS);
        repo.save(u);
    }

    /**
     * Runs all database write operations inside a single, fast transaction.
     * This is called AFTER all slow file I/O (checksum) is complete.
     */
    @Transactional
    public Asset processCompletedUpload(V2TusUpload u, String userId, byte[] checksumBytes) throws IOException {

        // ✅ --- START OPTIMIZATION ---
        // 1. Build the final, unique path *before* any DB calls, using the tusId.
        String uniqueFilename = u.getTusId() + "_" + u.getFilename();
        String finalAssetPath = buildPath(
                userId, // We need the user ID for the folder structure
                determineAssetType(u.getFiletype()).name(),
                uniqueFilename
        );
        // ✅ --- END OPTIMIZATION ---

        // 2. Create the Asset, User Quota, Exif (all DB ops)
        //    Pass the final path directly.
        Asset asset = createAssetAndDependencies(u, userId, checksumBytes, finalAssetPath);

        // 3. Move file to final location (fast rename)
        Path finalPath = Path.of(asset.getOriginalPath()); // Get the path we just saved
        Files.createDirectories(finalPath.getParent());
        Files.move(Path.of(u.getTempPath()), finalPath, StandardCopyOption.REPLACE_EXISTING);

        // 4. Update V2TusUpload state to COMPLETED
        u.setFinalPath(finalPath.toString());
        u.setStatus(V2TusUpload.Status.COMPLETED);
        repo.save(u); // Save the final state for the TUS upload

        return asset;
    }

    @Transactional(readOnly = true)
    public V2TusUpload info(String tusId) {
        return repo.findByTusId(tusId).orElseThrow(() -> new IllegalStateException("Upload not found: " + tusId));
    }

    @Transactional
    public void terminate(String tusId) throws Exception {
        V2TusUpload u = repo.findByTusId(tusId).orElseThrow(() -> new IllegalStateException("Upload not found: " + tusId));
        u.setStatus(V2TusUpload.Status.CANCELLED);
        Files.deleteIfExists(Path.of(u.getTempPath()));
        repo.save(u);
    }


    private Asset createAssetAndDependencies(V2TusUpload completedUpload, String userId, byte[] checksumBytes, String finalAssetPath) throws IOException {
        Map<String, String> headerData = parseUploadMetadata(completedUpload.getMetadata());

        UUID parsedUUID = TusAppUtils.parseUUID(Objects.requireNonNull(userId, "User ID not found in metadata"));
        User user = fetchUser(parsedUUID);
        AssetType assetType = determineAssetType(Objects.requireNonNull(completedUpload.getFiletype()));

        // All DB writes happen here
        Asset asset = saveAssetDataInTransaction(
                completedUpload.getTusId(),
                completedUpload.getUploadLength(),
                headerData,
                user,
                assetType,
                checksumBytes,
                finalAssetPath
        );

        return asset;
    }

    @Transactional
    protected Asset saveAssetDataInTransaction(String tusId, Long size, Map<String, String> headerData, User user, AssetType assetType, byte[] checksumBytes, String finalAssetPath) {
        Asset asset = createAsset(tusId, user, finalAssetPath, headerData, assetType, checksumBytes);

        user.setQuotaUsageInBytes(user.getQuotaUsageInBytes() + size);
        userRepository.save(user);

        upsertExif(asset.getId(), size);

        return asset;
    }

    public static Map<String, String> parseUploadMetadata(String header) {
        Map<String, String> map = new HashMap<>();
        if (header == null || header.isBlank()) return map;

        for (String kv : header.split(",")) {
            String[] parts = kv.trim().split(" ");
            if (parts.length == 2) {
                String key = parts[0];
                String value = new String(Base64.getDecoder().decode(parts[1]), StandardCharsets.UTF_8);
                map.put(key, value);
            }
        }
        return map;
    }

    private User fetchUser(UUID userId) {
        return userRepository.findById(userId)
                .orElseThrow(() -> new IllegalStateException("User not found"));
    }

    private String buildPath(String userId, String fileType, String uniqueFilename) {
        return Paths.get(finalDir, "originals", userId, fileType, uniqueFilename).toString();
    }

    private @NotNull Asset createAsset(String tusId, User user, String originalPath, Map<String, String> headerData, AssetType assetType, byte[] checksumBytes) {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);

        Asset asset = new Asset();
        asset.setId(TusAppUtils.parseUUID(tusId));
        asset.setOwner(user);
        asset.setOriginalPath(originalPath);
        asset.setDeviceAssetId(headerData.get("deviceAssetId"));
        asset.setDeviceId(headerData.get("deviceId"));
        asset.setChecksum(checksumBytes);
        asset.setType(assetType);
        asset.setOriginalFileName(headerData.get("filename"));

        asset.setFileCreatedAt(headerData.get("fileCreatedAt") != null
                ? OffsetDateTime.parse(headerData.get("fileCreatedAt"))
                : now);

        asset.setFileModifiedAt(headerData.get("fileModifiedAt") != null
                ? OffsetDateTime.parse(headerData.get("fileModifiedAt"))
                : now);

        asset.setLocalDateTime(headerData.get("fileCreatedAt") != null
                ? OffsetDateTime.parse(headerData.get("fileCreatedAt"))
                : now);

        asset.setIsFavorite(Boolean.TRUE.equals(headerData.get("isFavorite") != null
                ? Boolean.parseBoolean(headerData.get("isFavorite"))
                : false));

        asset.setDuration(headerData.get("duration") != null
                ? headerData.get("duration")
                : null);

        asset.setVisibility(headerData.get("visibility") != null
                ? AssetVisibility.fromValue(headerData.get("visibility"))
                : AssetVisibility.TIMELINE);

        // Initialize derivative tracking count
        // Only THUMBNAIL and PLAYBACK_VIDEO are tracked (metadata is stored in DB, not S3)
        // Images: 1 (thumbnail only)
        // Videos: 2 (thumbnail + playback_video)
        if (assetType == null) {
            throw new IllegalStateException("Asset type cannot be null during derivative tracking initialization");
        }

        switch (assetType) {
            case VIDEO:
                asset.setDerivativesPending(2);  // thumbnail + playback_video
                break;
            case IMAGE:
                asset.setDerivativesPending(1);  // thumbnail only
                break;
            default:
                log.warn("Unknown asset type: {}. Setting derivatives_pending to 1 (thumbnail)", assetType);
                asset.setDerivativesPending(1);
                break;
        }
        asset.setDerivativesCompleted(0);

        return assetRepository.saveAndFlush(asset);
    }


    public void upsertExif(UUID assetId, long fileSizeInByte) {
        AssetExif exif = new AssetExif();
        exif.setAssetId(assetId);
        exif.setFileSizeInByte(fileSizeInByte);
        exif.setUpdateId(UUID.randomUUID());
        assetExifRepository.upsertExif(exif);
    }

    private void queueAsyncJobs(Asset asset) {
        if (asset.getType() != null && asset.getType() == AssetType.VIDEO) {
            redisTemplate.opsForList().rightPush("video-transcode-queue", asset.getId().toString());
        }
        redisTemplate.opsForList().rightPush("thumbnail-queue", asset.getId().toString());
        redisTemplate.opsForList().rightPush("metadata-queue", asset.getId().toString());

        // Queue S3 sync job for the original file
        // Derivatives (thumbnails, playback videos) will queue independently with their own IDs
        redisTemplate.opsForList().rightPush("s3-sync-queue", asset.getId().toString());
        log.debug("Queued S3 sync for Asset: id={}", asset.getId());
    }

    public static AssetType determineAssetType(String mimeType) {
        return switch (mimeType) {
            case null -> {
                log.warn("Mime type is null, defaulting to OTHER");
                yield AssetType.OTHER;
            }
            case String s when s.startsWith("image/") -> AssetType.IMAGE;
            case String s when s.startsWith("video/") -> AssetType.VIDEO;
            case String s when s.startsWith("audio/") -> AssetType.AUDIO;
            case String s when s.contains("pdf") || s.contains("document") || s.contains("text/plain") -> AssetType.DOCUMENT;
            default -> {
                log.warn("Unknown mime type: {}, defaulting to OTHER", mimeType);
                yield AssetType.OTHER;
            }
        };
    }
}