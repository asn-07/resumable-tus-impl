package com.tus.upload.controller;


import com.tus.upload.service.UploadService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.UUID;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/uploads")
public class UploadController {

    private final String finalDirectory;
    private final Path finalDir;
    private final UploadService service;

    // Use @Autowired (often optional if it's the only constructor)
    // Move the @Value annotation to the constructor parameter
    public UploadController(@Value("${app.storage.final-dir}") String finalDirectory,
                            UploadService service) {

        // 1. Dependencies are passed in
        this.finalDirectory = finalDirectory;
        this.service = service;

        // 2. Now you can safely initialize finalDir
        this.finalDir = Paths.get(this.finalDirectory);
    }

    // DTOs
    public record InitRequest(String filename, String contentType, long totalSize, int chunkSize) {}
    public record InitResponse(UUID uploadId, int totalChunks) {}
    public record StatusResponse(String status, List<Integer> missing) {}

    @PostMapping
    public ResponseEntity<InitResponse> init(@RequestBody InitRequest req) {
        if (req.filename() == null || req.totalSize() <= 0 || req.chunkSize() <= 0)
            return ResponseEntity.badRequest().build();
        var u = service.initUpload(req.filename(), req.contentType(), req.totalSize(), req.chunkSize());
        return ResponseEntity.ok(new InitResponse(u.getId(), u.getTotalChunks()));
    }

    @PutMapping("/{uploadId}/chunks/{idx}")
    public ResponseEntity<Void> putChunk(
            @PathVariable("uploadId") UUID uploadId,
            @PathVariable("idx") int idx,
            HttpServletRequest request,
            @RequestHeader(value = "X-Chunk-Size", required = false) Integer declaredSize,
            @RequestHeader(value = "X-Chunk-Checksum", required = false) String checksum
    ) throws IOException {
        try (InputStream is = request.getInputStream()) {
            service.storeChunk(uploadId, idx, is, declaredSize, checksum);
        }
        return ResponseEntity.accepted().build();
    }

    @GetMapping("/{uploadId}/status")
    public ResponseEntity<StatusResponse> status(@PathVariable("uploadId") UUID uploadId) {
        var upload = service.getUpload(uploadId);
        return ResponseEntity.ok(new StatusResponse(upload.getStatus().name(), service.missingChunks(uploadId)));
    }

    @PostMapping("/{uploadId}/commit")
    public ResponseEntity<Void> commit(@PathVariable("uploadId") UUID uploadId) throws IOException {
        service.commit(uploadId);
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/{uploadId}")
    public ResponseEntity<Void> cancel(@PathVariable("uploadId") UUID uploadId) throws IOException {
        service.cancel(uploadId);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/{filename}")
    public ResponseEntity<StreamingResponseBody> streamVideo(
            @PathVariable("filename") String filename,
            @RequestHeader(value = "Range", required = false) String rangeHeader) throws Exception {

        Path video = finalDir.resolve(filename);
        if (!Files.exists(video) || !Files.isRegularFile(video)) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }

        long fileLength = Files.size(video);
        String fileName = video.getFileName().toString();
        MediaType contentType = MediaTypeFactory.getMediaType(fileName)
                .orElse(MediaType.APPLICATION_OCTET_STREAM);

        // No Range: full content
        if (rangeHeader == null) {
            StreamingResponseBody body = out -> {
                try (InputStream in = Files.newInputStream(video)) {
                    in.transferTo(out);
                }
            };
            return ResponseEntity.ok()
                    .header(HttpHeaders.ACCEPT_RANGES, "bytes")
                    .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"" + fileName + "\"")
                    .contentType(contentType)
                    .contentLength(fileLength)
                    .body(body);
        }

        // Range present: partial content
        Range r = parseRange(rangeHeader, fileLength);
        long start = r.start, end = r.end, len = end - start + 1;

        StreamingResponseBody body = out -> {
            try (var channel = Files.newByteChannel(video, StandardOpenOption.READ)) {
                channel.position(start);
                byte[] buf = new byte[64 * 1024]; // 64KB buffer
                long remaining = len;
                while (remaining > 0) {
                    int toRead = (int) Math.min(buf.length, remaining);
                    int read = channel.read(java.nio.ByteBuffer.wrap(buf, 0, toRead));
                    if (read < 0) break;
                    out.write(buf, 0, read);
                    remaining -= read;
                }
            }
        };

        return ResponseEntity.status(HttpStatus.PARTIAL_CONTENT)
                .header(HttpHeaders.CONTENT_RANGE, "bytes " + start + "-" + end + "/" + fileLength)
                .header(HttpHeaders.ACCEPT_RANGES, "bytes")
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"" + fileName + "\"")
                .contentType(contentType)
                .contentLength(len)
                .body(body);
    }

    // Helper: range parsing (single range support)
    static class Range { long start; long end; }

    private Range parseRange(String rangeHeader, long fileLength) {
        if (!rangeHeader.startsWith("bytes=")) {
            throw new IllegalArgumentException("Invalid Range header: " + rangeHeader);
        }
        String spec = rangeHeader.substring("bytes=".length()).trim();
        String rangeSpec = spec.split(",")[0].trim(); // only first range supported

        Range r = new Range();
        if (rangeSpec.startsWith("-")) {
            long suffixLen = Long.parseLong(rangeSpec.substring(1));
            r.start = Math.max(0, fileLength - suffixLen);
            r.end = fileLength - 1;
        } else if (rangeSpec.endsWith("-")) {
            r.start = Long.parseLong(rangeSpec.substring(0, rangeSpec.length() - 1));
            r.end = fileLength - 1;
        } else {
            String[] se = rangeSpec.split("-");
            r.start = Long.parseLong(se[0]);
            r.end = Long.parseLong(se[1]);
        }

        if (r.start < 0 || r.start >= fileLength) {
            throw new IllegalArgumentException("Range start out of bounds: " + r.start);
        }
        if (r.end < r.start) {
            r.end = fileLength - 1;
        }
        r.end = Math.min(r.end, fileLength - 1);
        return r;
    }

}
