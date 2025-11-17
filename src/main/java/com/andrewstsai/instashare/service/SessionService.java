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
import java.util.*;
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

    public SessionResponse createSession(CreateSessionRequest request) {
        String sessionId = generateSessionId();

        Session session = Session.builder()
            .id(sessionId)
            .name(request.getName() != null ? request.getName() : "Untitled Session")
            .build();

        sessionRepository.save(session);

        log.info("Session created: {} by {}", sessionId, session.getCreatorId());

        return SessionResponse.builder()
            .id(session.getId())
            .name(session.getName())
            .isLocked(session.getIsLocked())
            .items(List.of())
            .build();
    }

    public SessionResponse getSession(String sessionId) {
        Session session = sessionRepository.findById(sessionId)
            .orElseThrow(() -> new RuntimeException("Session not found or expired"));

        List<SessionItemResponse> items = sessionItemRepository.findBySessionId(sessionId).stream()
            .map(this::mapToItemResponse)
            .collect(Collectors.toList());

        return SessionResponse.builder()
            .id(session.getId())
            .name(session.getName())
            .isLocked(session.getIsLocked())
            .items(items)
            .build();
    }

    public SessionItemResponse addItem(String sessionId, AddItemRequest request) {
        Session session = sessionRepository.findById(sessionId)
            .orElseThrow(() -> new RuntimeException("Session not found or expired"));

        SessionItem item = SessionItem.builder()
            .id(UUID.randomUUID().toString())
            .sessionId(sessionId)
            .type(request.getType())
            .s3Key(request.getS3Key())
            .s3Url(request.getS3Url())
            .fileName(request.getFileName())
            .fileSize(request.getFileSize())
            .positionX(request.getPositionX())
            .positionY(request.getPositionY())
            .uploadedAt(LocalDateTime.now())
            .build();

        sessionItemRepository.save(item);

        log.info("Item added to session {}: {}", sessionId, item.getType());

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
        redisTemplate.expire(key, Duration.ofHours(3));
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

    public List<SessionItem> getSessionItems(String sessionId) {
        List<SessionItem> items = sessionItemRepository.findBySessionId(sessionId);

        log.debug("Retrieved {} items from session {}", items.size(), sessionId);
        return items;
    }

    private String generateSessionId() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    public void updateItemPosition(String sessionId, String itemId, Double x, Double y) {
        SessionItem item = sessionItemRepository.findById(itemId)
            .orElseThrow(() -> new RuntimeException("Item not found"));

        if (!item.getSessionId().equals(sessionId)) {
            throw new RuntimeException("Item does not belong to this session");
        }

        item.setPositionX(x);
        item.setPositionY(y);

        sessionItemRepository.save(item);
        log.debug("Updated position for item {} in session {}: ({}, {})", itemId, sessionId, x, y);
    }

    public Map<String, Double> getItemPosition(String sessionId, String itemId) {
        String key = "session:" + sessionId + ":item:" + itemId + ":position";
        Map<Object, Object> position = redisTemplate.opsForHash().entries(key);

        if (position.isEmpty()) {
            return null;
        }

        return Map.of(
            "x", Double.parseDouble((String) position.get("x")),
            "y", Double.parseDouble((String) position.get("y"))
        );
    }

    public SessionResponse toggleSessionLock(String sessionId) {
        Session session = sessionRepository.findById(sessionId)
            .orElseThrow(() -> new RuntimeException("Session not found"));

        session.setIsLocked(!session.getIsLocked());
        sessionRepository.save(session);
        log.info("Session {} lock toggled to: {}", sessionId, session.getIsLocked());

        return mapToSessionResponse(session);
    }

    private SessionResponse mapToSessionResponse(Session session) {
        List<SessionItem> items = sessionItemRepository.findBySessionId(session.getId());

        List<SessionItemResponse> itemResponses = items.stream()
            .map(this::mapToItemResponse)
            .collect(Collectors.toList());

        return SessionResponse.builder()
            .id(session.getId())
            .name(session.getName())
            .isLocked(session.getIsLocked())
            .items(itemResponses)
            .build();
    }

    private SessionItemResponse mapToItemResponse(SessionItem item) {
        return SessionItemResponse.builder()
            .id(item.getId())
            .type(item.getType())
            .s3Url(item.getS3Url())
            .fileName(item.getFileName())
            .fileSize(item.getFileSize())
            .uploadedAt(item.getUploadedAt())
            .positionX(item.getPositionX())
            .positionY(item.getPositionY())
            .build();
    }
}