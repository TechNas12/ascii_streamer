package com.asciistreamer.controller;

import com.asciistreamer.model.ExportProgress;
import com.asciistreamer.model.ExportRequest;
import com.asciistreamer.service.AsciiVideoExporter;
import com.asciistreamer.session.SessionManager;
import com.asciistreamer.session.StreamingSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/**
 * REST controller for triggering and downloading rendered ASCII videos with audio.
 */
@RestController
@RequestMapping("/api/export")
@CrossOrigin(origins = "*")
public class ExportController {

    private static final Logger log = LoggerFactory.getLogger(ExportController.class);

    private final AsciiVideoExporter asciiVideoExporter;
    private final SessionManager sessionManager;

    public ExportController(AsciiVideoExporter asciiVideoExporter, SessionManager sessionManager) {
        this.asciiVideoExporter = asciiVideoExporter;
        this.sessionManager = sessionManager;
    }

    @PostMapping
    public ResponseEntity<?> startExport(@RequestBody ExportRequest request) {
        if (request.getSessionId() == null || request.getSessionId().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Missing sessionId in export request"));
        }

        StreamingSession session = sessionManager.getSession(request.getSessionId());
        if (session == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Map.of("error", "Session not found or expired"));
        }

        try {
            String jobId = asciiVideoExporter.submitExportJob(session.getVideoPath(), request);
            return ResponseEntity.ok(Map.of("jobId", jobId));
        } catch (Exception e) {
            log.error("Failed to start export", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Failed to start export: " + e.getMessage()));
        }
    }

    @GetMapping("/{jobId}/status")
    public ResponseEntity<?> getStatus(@PathVariable String jobId) {
        ExportProgress progress = asciiVideoExporter.getJobStatus(jobId);
        if (progress == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Map.of("error", "Export job not found"));
        }
        return ResponseEntity.ok(progress);
    }

    @GetMapping("/{jobId}/download")
    public ResponseEntity<Resource> downloadExportedVideo(@PathVariable String jobId) {
        Path filePath = asciiVideoExporter.getExportedFilePath(jobId);
        if (filePath == null || !Files.exists(filePath)) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }

        Resource resource = new FileSystemResource(filePath.toFile());

        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("video/mp4"))
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"ascii_video.mp4\"")
                .body(resource);
    }
}
