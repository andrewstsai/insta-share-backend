package com.andrewstsai.instashare.controller;

import com.andrewstsai.instashare.dto.*;
import com.andrewstsai.instashare.service.SessionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.handler.annotation.DestinationVariable;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.handler.annotation.SendTo;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Controller;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.Set;

@Controller
@RequiredArgsConstructor
@Slf4j
public class SessionWebSocketController {

    private final SessionService sessionService;
    private final SimpMessagingTemplate messagingTemplate;

    @MessageMapping("/session/{sessionId}/join")
    public void joinSession(
            @DestinationVariable String sessionId,
            JoinMessage message,
            SimpMessageHeaderAccessor headerAccessor) {

        String userId = message.getUserId();
        String username = message.getUsername();

        try {
            SessionResponse session = sessionService.getSession(sessionId);

            if (session == null) {
                log.warn("User {} attempted to join non-existent session {}", username, sessionId);
                return;
            }

            if (session.getExpiresAt() != null && session.getExpiresAt().isBefore(LocalDateTime.now())) {
                log.warn("User {} attempted to join expired session {}", username, sessionId);
                return;
            }

            if (session.getIsLocked()) {
                log.warn("User {} attempted to join locked session {}", username, sessionId);
                return;
            }

            Map<String, Object> sessionAttributes = headerAccessor.getSessionAttributes();
            if (sessionAttributes != null) {
                sessionAttributes.put("userId", userId);
                sessionAttributes.put("username", username);
                sessionAttributes.put("sessionId", sessionId);

                log.info("Stored session attributes: userId={}, username={}, sessionId={}",
                        userId, username, sessionId);
            } else {
                log.warn("Session attributes map is null for user {}", username);
            }

            sessionService.addUserToSession(sessionId, userId, username);
            sessionService.updateSessionActivity(sessionId);

            UserPresence presence = new UserPresence(userId, username, "joined");
            messagingTemplate.convertAndSend("/topic/session/" + sessionId + "/presence", presence);

            Set<Object> users = sessionService.getSessionUsers(sessionId);
            messagingTemplate.convertAndSend("/topic/session/" + sessionId + "/users", users);

            log.info("User {} joined session {} (total users: {})", username, sessionId, users.size());

        } catch (Exception e) {
            log.error("Error joining session {} for user {}: {}", sessionId, username, e.getMessage(), e);
        }
    }

    @MessageMapping("/session/{sessionId}/leave")
    public void leaveSession(
            @DestinationVariable String sessionId,
            String userId) {

        sessionService.removeUserFromSession(sessionId, userId);
        sessionService.updateSessionActivity(sessionId);

        UserPresence presence = new UserPresence(userId, null, "left");
        messagingTemplate.convertAndSend("/topic/session/" + sessionId + "/presence", presence);

        Set<Object> users = sessionService.getSessionUsers(sessionId);
        messagingTemplate.convertAndSend("/topic/session/" + sessionId + "/users", users);

        log.info("User {} left session {}", userId, sessionId);
    }

    @MessageMapping("/session/{sessionId}/add-item")
    @SendTo("/topic/session/{sessionId}/items")
    public SessionItemResponse addItem(@DestinationVariable String sessionId, AddItemRequest request) {

        sessionService.updateSessionActivity(sessionId);

        SessionItemResponse item = sessionService.addItem(sessionId, request);
        log.info("Item added to session {} by {}", sessionId, request.getUploadedBy());
        return item;
    }

    @MessageMapping("/session/{sessionId}/remove-item")
    @SendTo("/topic/session/{sessionId}/item-removed")
    public String removeItem(@DestinationVariable String sessionId, String itemId) {

        sessionService.updateSessionActivity(sessionId);

        sessionService.removeItem(sessionId, itemId);
        log.info("Item {} removed from session {}", itemId, sessionId);
        return itemId;
    }

    @MessageMapping("/session/{sessionId}/update-position")
    @SendTo("/topic/session/{sessionId}/position-update")
    public Map<String, Object> updateItemPosition(
            @DestinationVariable String sessionId,
            Map<String, Object> positionUpdate) {

        sessionService.updateSessionActivity(sessionId);

        String itemId = (String) positionUpdate.get("itemId");
        Integer x = (Integer) positionUpdate.get("x");
        Integer y = (Integer) positionUpdate.get("y");

        log.debug("Position updated for item {} in session {}: ({}, {})",
                itemId, sessionId, x, y);

        return positionUpdate;
    }

    @MessageMapping("/session/{sessionId}/heartbeat")
    public void heartbeat(@DestinationVariable String sessionId) {
        sessionService.updateSessionActivity(sessionId);
    }
}