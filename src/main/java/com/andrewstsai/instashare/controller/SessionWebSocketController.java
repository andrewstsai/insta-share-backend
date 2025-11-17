package com.andrewstsai.instashare.controller;

import com.andrewstsai.instashare.dto.*;
import com.andrewstsai.instashare.model.SessionItem;
import com.andrewstsai.instashare.service.SessionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.handler.annotation.DestinationVariable;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.handler.annotation.SendTo;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Controller;

import java.util.List;
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

            List<SessionItem> items = sessionService.getSessionItems(sessionId);

            Set<Object> users = sessionService.getSessionUsers(sessionId);

            messagingTemplate.convertAndSendToUser(
                message.getUserId(),
                "/queue/initial-state",
                Map.of(
                    "items", items,
                    "users", users
                )
            );

            messagingTemplate.convertAndSend(
                "/topic/session/" + sessionId + "/presence",
                new UserPresence(message.getUserId(), message.getUsername(), "joined")
            );

            messagingTemplate.convertAndSend(
                "/topic/session/" + sessionId + "/users",
                users
            );

            log.info("User {} joined session {} (total users: {})", username, sessionId, users.size());

        } catch (Exception e) {
            log.error("Error joining session {} for user {}: {}", sessionId, username, e.getMessage(), e);
        }
    }

    @MessageMapping("/session/{sessionId}/leave")
    public void leaveSession(
        @DestinationVariable String sessionId,
        JoinMessage message) {

        sessionService.removeUserFromSession(sessionId, message.getUserId());

        UserPresence presence = new UserPresence(message.getUserId(), message.getUsername(), "left");
        messagingTemplate.convertAndSend("/topic/session/" + sessionId + "/presence", presence);

        Set<Object> users = sessionService.getSessionUsers(sessionId);
        messagingTemplate.convertAndSend("/topic/session/" + sessionId + "/users", users);

        log.info("User {} left session {}", message.getUserId(), sessionId);
    }

    @MessageMapping("/session/{sessionId}/update-position")
    @SendTo("/topic/session/{sessionId}/position-update")
    public Map<String, Object> updateItemPosition(
        @DestinationVariable String sessionId,
        Map<String, Object> positionUpdate) {

        try {
            String itemId = (String) positionUpdate.get("itemId");
            Double x = (Double) positionUpdate.get("x");
            Double y = (Double) positionUpdate.get("y");

            if (itemId == null || x == null || y == null) {
                throw new IllegalArgumentException("Missing required fields");
            }

            sessionService.updateItemPosition(sessionId, itemId, x, y);

            log.debug("Position updated for item {} in session {}: ({}, {})",
                    itemId, sessionId, x, y);

            return Map.of(
            "itemId", itemId,
            "x", x,
            "y", y,
            "timestamp", System.currentTimeMillis()
            );

        } catch (Exception e) {
            log.error("Error updating position in session {}: {}", sessionId, e.getMessage(), e);
            throw e;
        }
    }
}