package com.andrewstsai.instashare.controller;

import com.andrewstsai.instashare.dto.UserPresence;
import com.andrewstsai.instashare.service.SessionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.messaging.SessionDisconnectEvent;

import java.util.Set;

@Component
@RequiredArgsConstructor
@Slf4j
public class WebSocketEventListener {

    private final SimpMessagingTemplate messagingTemplate;
    private final SessionService sessionService;

    @EventListener
    public void handleWebSocketDisconnectListener(SessionDisconnectEvent event) {
        StompHeaderAccessor headerAccessor = StompHeaderAccessor.wrap(event.getMessage());
        String userId = (String) headerAccessor.getSessionAttributes().get("userId");
        String username = (String) headerAccessor.getSessionAttributes().get("username");
        String sessionId = (String) headerAccessor.getSessionAttributes().get("sessionId");

        if (userId != null && sessionId != null) {
            log.info("User {} ({}) disconnected from session {}", username, userId, sessionId);

            sessionService.removeUserFromSession(sessionId, userId);

            int remainingUsers = sessionService.getSessionUserCount(sessionId);
            UserPresence presence = new UserPresence(userId, username, "left");
            messagingTemplate.convertAndSend("/topic/session/" + sessionId + "/presence", presence);

            Set<Object> users = sessionService.getSessionUsers(sessionId);
            messagingTemplate.convertAndSend("/topic/session/" + sessionId + "/users", users);

            if (remainingUsers == 0) {
                log.info("All users left session. Deleting session: {}", sessionId);
                sessionService.deleteSession(sessionId);
            } else {
                log.info("User {} left session {}. Remaining users: {}", username, sessionId, remainingUsers);
            }
        } else {
            log.debug("WebSocket disconnected without session info");
        }
    }
}