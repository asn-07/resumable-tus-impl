package com.tus.upload.controller;

import com.tus.upload.entity.V2TusUpload;
import com.tus.upload.entity.dto.AppendResult;
import com.tus.upload.service.TusService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.io.InputStream;


@RestController
@RequestMapping("/api/v1/files")
@RequiredArgsConstructor
@Slf4j
public class TusController {

    private final TusService service;

    @Value("${app.tus.version:1.0.0}")
    private String tusVersion;

    @RequestMapping(method = RequestMethod.OPTIONS, value = { "", "/{id}" })
    public ResponseEntity<Void> options() {
        return ResponseEntity.noContent()
                .header("Tus-Resumable", tusVersion)
                .header("Tus-Version", tusVersion)
                .header("Tus-Extension", "creation,termination,metadata")
                .build();
    }

    @PostMapping
    public ResponseEntity<Void> create(HttpServletRequest req) throws Exception {
        if (!tusVersion.equals(req.getHeader("Tus-Resumable"))) {
            return ResponseEntity.status(412).build();
        }

        // Service now returns the TUS ID (String)
        String tusId = service.create(req);

        String location = ServletUriComponentsBuilder.fromRequestUri(req)
                .pathSegment(tusId)
                .build()
                .toUriString();

        log.info("Created new tus upload id={}, location={}", tusId, location);

        return ResponseEntity.status(HttpStatus.CREATED)
                .header("Location", location)
                .header("Tus-Resumable", tusVersion)
                .header("Upload-Offset", "0")
                .build();
    }

    @RequestMapping(method = RequestMethod.HEAD, value = "/{id}")
    public ResponseEntity<Void> head(@PathVariable("id") String id, HttpServletRequest req) {

        if (!tusVersion.equals(req.getHeader("Tus-Resumable"))) {
            log.warn("Tus-Resumable header mismatch on HEAD for id={}", id);
            return ResponseEntity.status(412).build();
        }

        // Service returns the V2TusUpload entity
        V2TusUpload u = service.info(id);
        log.info("HEAD for id={} -> offset={}, length={}", id, u.getUploadOffset(), u.getUploadLength());

        return ResponseEntity.noContent()
                .header("Tus-Resumable", tusVersion)
                .header("Upload-Offset", u.getUploadOffset().toString())
                .header("Upload-Length", u.getUploadLength().toString())
                .build();
    }

    @PatchMapping(value = "/{id}", consumes = "application/offset+octet-stream")
    public ResponseEntity<Void> patch(@PathVariable("id") String id, HttpServletRequest req) throws Exception {
        log.debug("PATCH /files/{} - appending chunk", id);

        if (!tusVersion.equals(req.getHeader("Tus-Resumable"))) {
            log.warn("Tus-Resumable header mismatch on PATCH for id={}", id);
            return ResponseEntity.status(412).build();
        }

        long clientOffset = Long.parseLong(req.getHeader("Upload-Offset"));
        try (InputStream body = req.getInputStream()) {

            // Service now returns our new AppendResult wrapper
            String userId = req.getHeader("X-User-Id");
            V2TusUpload result = service.append(id, clientOffset, body, userId);

            var responseBuilder = ResponseEntity.noContent()
                    .header("Tus-Resumable", tusVersion)
                    .header("Upload-Offset", result.getUploadOffset().toString());

            return responseBuilder.build();
        }
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable("id") String id, HttpServletRequest req) throws Exception {
        log.info("DELETE /files/{} - terminating upload", id);

        if (!tusVersion.equals(req.getHeader("Tus-Resumable"))) {
            log.warn("Tus-Resumable header mismatch on DELETE for id={}", id);
            return ResponseEntity.status(412).build();
        }

        service.terminate(id);
        log.info("Upload id={} terminated successfully", id);
        return ResponseEntity.noContent().build();
    }
}
