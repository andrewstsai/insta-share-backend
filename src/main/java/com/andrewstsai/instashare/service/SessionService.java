package com.andrewstsai.instashare.service;

import com.andrewstsai.instashare.dto.AddItemRequest;
import com.andrewstsai.instashare.dto.CreateSessionRequest;
import com.andrewstsai.instashare.dto.SessionItemResponse;
import com.andrewstsai.instashare.dto.SessionResponse;
import com.andrewstsai.instashare.model.Session;
import com.andrewstsai.instashare.model.SessionItem;
import com.andrewstsai.instashare.repository.SessionItemRepository;
import com.andrewstsai.instashare.repository.SessionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class SessionService {

    private final SessionRepository sessionRepository;
    private final SessionItemRepository sessionItemRepository;
    private final RedisTemplate<String, Object> redisTemplate;
    private final S3Service s3Service;

    @Value("${app.frontend.url}")
    private String frontendUrl;

    @Value("${session.default-expiration-hours:24}")
    private int defaultExpirationHours;

    public SessionResponse createSession(CreateSessionRequest request) {
        String sessionId = generateSessionId();

        int expirationHours = request.getExpirationHours() != null
                ? request.getExpirationHours()
                : defaultExpirationHours;

        Session session = Session.builder()
                .id(sessionId)
                .creatorId(generateUserId())
                .title(request.getName() != null ? request.getName() : "Untitled Session")
                .createdAt(LocalDateTime.now())
                .expiresAt(LocalDateTime.now().plusHours(expirationHours))
                .expiration(expirationHours * 3600)
                .build();

        sessionRepository.save(session);

        log.info("Session created: {} by {}", sessionId, session.getCreatorId());

        return SessionResponse.builder()
                .id(session.getId())
                .name(session.getTitle())
                .isLocked(session.getIsLocked())
                .shareUrl(generateShareUrl(sessionId))
                .createdAt(session.getCreatedAt())
                .expiresAt(session.getExpiresAt())
                .items(List.of())
                .build();
    }

    public SessionResponse getSession(String sessionId) {
        Session session = sessionRepository.findById(sessionId)
                .orElseThrow(() -> new RuntimeException("Session not found or expired"));

        // Check if expired (defensive check, Redis should auto-delete)
        if (session.getExpiresAt() != null && session.getExpiresAt().isBefore(LocalDateTime.now())) {
            throw new RuntimeException("Session has expired");
        }

        // Fetch items separately in Redis
        List<SessionItemResponse> items = sessionItemRepository.findBySessionId(sessionId).stream()
                .map(this::mapToItemResponse)
                .collect(Collectors.toList());

        return SessionResponse.builder()
                .id(session.getId())
                .name(session.getTitle())
                .isLocked(session.getIsLocked())
                .shareUrl(generateShareUrl(sessionId))
                .createdAt(session.getCreatedAt())
                .expiresAt(session.getExpiresAt())
                .items(items)
                .build();
    }

    public SessionItemResponse addItem(String sessionId, AddItemRequest request) {
        Session session = sessionRepository.findById(sessionId)
                .orElseThrow(() -> new RuntimeException("Session not found or expired"));

        if (session.getExpiresAt() != null && session.getExpiresAt().isBefore(LocalDateTime.now())) {
            throw new RuntimeException("Session has expired");
        }

        SessionItem item = SessionItem.builder()
                .id(UUID.randomUUID().toString())
                .sessionId(sessionId)
                .type(request.getType())
                .s3Key(request.getS3Key())
                .s3Url(request.getS3Url())
                .content(request.getContent())
                .fileName(request.getFileName())
                .fileSize(request.getFileSize())
                .uploadedBy(request.getUploadedBy())
                .positionX(request.getPositionX())
                .positionY(request.getPositionY())
                .uploadedAt(LocalDateTime.now())
                .expiration(session.getExpiration())
                .build();

        sessionItemRepository.save(item);

        log.info("Item added to session {}: {} by {}", sessionId, item.getType(), item.getUploadedBy());

        return mapToItemResponse(item);
    }

    public void removeItem(String sessionId, String itemId) {
        SessionItem item = sessionItemRepository.findById(itemId)
                .orElseThrow(() -> new RuntimeException("Item not found or expired"));

        if (!item.getSessionId().equals(sessionId)) {
            throw new RuntimeException("Item does not belong to this session");
        }

        if (item.getS3Key() != null) {
            try {
                s3Service.deleteFile(item.getS3Key());
            } catch (Exception e) {
                log.error("Failed to delete file from S3: {}", item.getS3Key(), e);
            }
        }

        sessionItemRepository.delete(item);
        log.info("Item removed from session {}: {}", sessionId, itemId);
    }

    public void deleteSession(String sessionId) {
        Session session = sessionRepository.findById(sessionId)
                .orElseThrow(() -> new RuntimeException("Session not found"));

        List<SessionItem> items = sessionItemRepository.findBySessionId(sessionId);
        for (SessionItem item : items) {
            if (item.getS3Key() != null) {
                try {
                    s3Service.deleteFile(item.getS3Key());
                    log.info("Deleted S3 file: {}", item.getS3Key());
                } catch (Exception e) {
                    log.warn("Failed to delete S3 file (will be cleaned by lifecycle): {}",
                            item.getS3Key(), e);
                }
            }
        }

        sessionItemRepository.deleteBySessionId(sessionId);
        deleteRedisKeys(sessionId);
        sessionRepository.deleteById(sessionId);
        log.info("Session deleted: {}", sessionId);
    }

    private void deleteRedisKeys(String sessionId) {
        try {
            Set<String> keys = redisTemplate.keys("session:" + sessionId + ":*");
            if (!keys.isEmpty()) {
                redisTemplate.delete(keys);
            }
        } catch (Exception e) {
            log.error("Error deleting Redis keys for session: {}", sessionId, e);
        }
    }

    public void addUserToSession(String sessionId, String userId, String username) {
        String key = "session:" + sessionId + ":users";
        redisTemplate.opsForHash().put(key, userId, username);
        redisTemplate.expire(key, Duration.ofHours(defaultExpirationHours + 1));

        updateSessionActivity(sessionId);
    }

    public void removeUserFromSession(String sessionId, String userId) {
        String key = "session:" + sessionId + ":users";
        redisTemplate.opsForHash().delete(key, userId);
    }

    public Set<Object> getSessionUsers(String sessionId) {
        String key = "session:" + sessionId + ":users";
        return new HashSet<>(redisTemplate.opsForHash().values(key));
    }

    public int getSessionUserCount(String sessionId) {
        String key = "session:" + sessionId + ":users";
        Long size = redisTemplate.opsForHash().size(key);
        return size.intValue();
    }

    public void updateSessionActivity(String sessionId) {
        String key = "session:" + sessionId + ":last_activity";
        redisTemplate.opsForValue().set(key, LocalDateTime.now().toString());
        redisTemplate.expire(key, Duration.ofHours(defaultExpirationHours + 1));
    }

    private String generateSessionId() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    private String generateUserId() {
        return "user-" + UUID.randomUUID().toString().substring(0, 8);
    }

    private String generateShareUrl(String sessionId) {
        return frontendUrl + sessionId;
    }

    private SessionItemResponse mapToItemResponse(SessionItem item) {
        return SessionItemResponse.builder()
                .id(item.getId())
                .type(item.getType())
                .s3Url(item.getS3Url())
                .content(item.getContent())
                .fileName(item.getFileName())
                .fileSize(item.getFileSize())
                .uploadedBy(item.getUploadedBy())
                .uploadedAt(item.getUploadedAt())
                .positionX(item.getPositionX())
                .positionY(item.getPositionY())
                .build();
    }
}