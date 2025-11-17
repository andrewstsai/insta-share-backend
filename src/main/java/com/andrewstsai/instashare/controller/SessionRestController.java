package com.andrewstsai.instashare.controller;

import com.andrewstsai.instashare.dto.AddItemRequest;
import com.andrewstsai.instashare.dto.CreateSessionRequest;
import com.andrewstsai.instashare.dto.SessionItemResponse;
import com.andrewstsai.instashare.dto.SessionResponse;
import com.andrewstsai.instashare.model.ItemType;
import com.andrewstsai.instashare.service.S3Service;
import com.andrewstsai.instashare.service.SessionService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.Map;

@RestController
@RequestMapping("/api/sessions")
@RequiredArgsConstructor
public class SessionRestController {

    private final SessionService sessionService;
    private final S3Service s3Service;
    private final SimpMessagingTemplate messagingTemplate;

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
    public ResponseEntity<SessionItemResponse> uploadFile(
        @PathVariable String sessionId,
        @RequestParam("file") MultipartFile file,
        @RequestParam String positionX,
        @RequestParam String positionY) throws IOException {

        if (file.isEmpty()) {
            throw new IllegalArgumentException("File cannot be empty");
        }

        String s3Key = s3Service.uploadFile(file, sessionId);
        String s3Url = s3Service.generatePresignedUrl(s3Key);
        ItemType itemType = detectItemType(file.getContentType());

        AddItemRequest itemRequest = AddItemRequest.builder()
            .type(itemType)
            .s3Key(s3Key)
            .s3Url(s3Url)
            .fileName(file.getOriginalFilename())
            .fileSize(file.getSize())
            .positionX(Double.valueOf(positionX))
            .positionY(Double.valueOf(positionY))
            .build();

        SessionItemResponse item = sessionService.addItem(sessionId, itemRequest);

        messagingTemplate.convertAndSend("/topic/session/" + sessionId + "/items", item);

        return ResponseEntity.ok(item);
    }

    @PatchMapping("/{sessionId}/lock")
    public ResponseEntity<SessionResponse> toggleLock(@PathVariable String sessionId) {

        SessionResponse response = sessionService.toggleSessionLock(sessionId);

        messagingTemplate.convertAndSend(
            "/topic/session/" + sessionId + "/lock-status",
            Map.of(
                "isLocked", response.getIsLocked(),
                "timestamp", System.currentTimeMillis()
            )
        );

        return ResponseEntity.ok(response);
    }

    @DeleteMapping("/{sessionId}")
    public ResponseEntity<Void> deleteSession(@PathVariable String sessionId) {
        sessionService.deleteSession(sessionId);
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/{sessionId}/items/{itemId}")
    public ResponseEntity<Void> deleteItem(
            @PathVariable String sessionId,
            @PathVariable String itemId) {

        sessionService.removeItem(sessionId, itemId);

        messagingTemplate.convertAndSend(
            "/topic/session/" + sessionId + "/item-deleted",
            Map.of("itemId", itemId)
        );

        return ResponseEntity.noContent().build();
    }

    private ItemType detectItemType(String contentType) {
        if (contentType == null) {
            return ItemType.FILE;
        } else if (contentType.startsWith("image/")) {
            return ItemType.IMAGE;
        } else if (contentType.startsWith("video/")) {
            return ItemType.VIDEO;
        } else if (contentType.startsWith("audio/")) {
            return ItemType.AUDIO;
        } else if (contentType.equals("application/pdf")) {
            return ItemType.PDF;
        }

        return ItemType.FILE;
    }
}