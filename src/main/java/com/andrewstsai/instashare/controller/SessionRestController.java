package com.andrewstsai.instashare.controller;

import com.andrewstsai.instashare.dto.CreateSessionRequest;
import com.andrewstsai.instashare.dto.SessionResponse;
import com.andrewstsai.instashare.service.S3Service;
import com.andrewstsai.instashare.service.SessionService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/sessions")
@RequiredArgsConstructor
public class SessionRestController {

    private final SessionService sessionService;
    private final S3Service s3Service;

    @PostMapping
    public ResponseEntity<SessionResponse> createSession(@RequestBody CreateSessionRequest request) {
        SessionResponse response = sessionService.createSession(request);
        return ResponseEntity.ok(response);
    }

    @GetMapping("/{sessionId}")
    public ResponseEntity<SessionResponse> getSession(@PathVariable String sessionId) {
        SessionResponse response = sessionService.getSession(sessionId);
        return ResponseEntity.ok(response);
    }

    @PostMapping("/{sessionId}/upload")
    public ResponseEntity<Map<String, String>> uploadFile(
            @PathVariable String sessionId,
            @RequestParam("file") MultipartFile file) throws IOException {

        if (file.isEmpty()) {
            throw new IllegalArgumentException("File cannot be empty");
        }

        String s3Key = s3Service.uploadFile(file, sessionId);
        String s3Url = s3Service.generatePresignedUrl(s3Key);

        Map<String, String> response = new HashMap<>();
        response.put("s3Key", s3Key);
        response.put("s3Url", s3Url);
        response.put("fileName", file.getOriginalFilename());
        response.put("fileSize", String.valueOf(file.getSize()));

        return ResponseEntity.ok(response);
    }

    @DeleteMapping("/{sessionId}")
    public ResponseEntity<Void> deleteSession(@PathVariable String sessionId) {
        sessionService.deleteSession(sessionId);
        return ResponseEntity.noContent().build();
    }
}