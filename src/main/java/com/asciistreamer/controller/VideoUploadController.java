package com.asciistreamer.controller;

import com.asciistreamer.model.VideoMetadata;
import com.asciistreamer.session.SessionManager;
import com.asciistreamer.session.StreamingSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.Map;
import java.util.UUID;

/**
 * REST endpoint for video file upload and session initialization.
 */
@RestController
@RequestMapping("/api")
@CrossOrigin(origins = "*")
public class VideoUploadController {

    private static final Logger log = LoggerFactory.getLogger(VideoUploadController.class);

    private final SessionManager sessionManager;

    @Value("${app.upload-dir:./uploads}")
    private String uploadDir;

    public VideoUploadController(SessionManager sessionManager) {
        this.sessionManager = sessionManager;
    }

    @PostMapping("/upload")
    public ResponseEntity<?> uploadVideo(@RequestParam("file") MultipartFile file) {
        if (file.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Uploaded file is empty"));
        }

        String originalFilename = file.getOriginalFilename();
        if (originalFilename == null) {
            originalFilename = "video.mp4";
        }

        try {
            Path targetDir = Paths.get(uploadDir).toAbsolutePath().normalize();
            if (!Files.exists(targetDir)) {
                Files.createDirectories(targetDir);
            }

            String extension = "";
            int dotIdx = originalFilename.lastIndexOf('.');
            if (dotIdx > 0) {
                extension = originalFilename.substring(dotIdx);
            }

            String uniqueFilename = UUID.randomUUID() + extension;
            Path targetPath = targetDir.resolve(uniqueFilename);

            Files.copy(file.getInputStream(), targetPath, StandardCopyOption.REPLACE_EXISTING);
            log.info("Saved uploaded video to {}", targetPath);

            // Create streaming session
            StreamingSession session = sessionManager.createSession(targetPath.toString());
            VideoMetadata metadata = session.getMetadata();

            return ResponseEntity.ok(metadata);

        } catch (Exception e) {
            log.error("Failed to process uploaded video", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Failed to process video: " + e.getMessage()));
        }
    }

    @GetMapping("/session/{sessionId}")
    public ResponseEntity<?> getSessionInfo(@PathVariable String sessionId) {
        StreamingSession session = sessionManager.getSession(sessionId);
        if (session == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Map.of("error", "Session not found or expired"));
        }
        return ResponseEntity.ok(session.getMetadata());
    }
}
