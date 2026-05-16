package com.andrewstsai.instashare.controller;

import com.andrewstsai.instashare.dto.UserPresence;
import com.andrewstsai.instashare.service.SessionService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.messaging.Message;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.messaging.SessionDisconnectEvent;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import org.mockito.ArgumentMatchers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class WebSocketEventListenerTest {

    @Mock private SimpMessagingTemplate messagingTemplate;
    @Mock private SessionService sessionService;

    @InjectMocks private WebSocketEventListener listener;

    @Test
    void handleDisconnect_removesUserAndBroadcasts() {
        when(sessionService.getSessionUserCount("sess1")).thenReturn(2);
        when(sessionService.getSessionUsers("sess1")).thenReturn(Set.of("bob"));

        listener.handleWebSocketDisconnectListener(buildEvent("u1", "alice", "sess1"));

        verify(sessionService).removeUserFromSession("sess1", "u1");

        ArgumentCaptor<UserPresence> presenceCaptor = ArgumentCaptor.forClass(UserPresence.class);
        verify(messagingTemplate).convertAndSend(eq("/topic/session/sess1/presence"), presenceCaptor.capture());
        assertThat(presenceCaptor.getValue().getAction()).isEqualTo("left");
        assertThat(presenceCaptor.getValue().getUserId()).isEqualTo("u1");

        verify(messagingTemplate).convertAndSend(eq("/topic/session/sess1/users"), ArgumentMatchers.<Object>any());
        verify(sessionService, never()).deleteSession(any());
    }

    @Test
    void handleDisconnect_lastUser_deletesSession() {
        when(sessionService.getSessionUserCount("sess1")).thenReturn(0);
        when(sessionService.getSessionUsers("sess1")).thenReturn(Set.of());

        listener.handleWebSocketDisconnectListener(buildEvent("u1", "alice", "sess1"));

        verify(sessionService).deleteSession("sess1");
    }

    @Test
    void handleDisconnect_withNullUserId_doesNothing() {
        listener.handleWebSocketDisconnectListener(buildEvent(null, "alice", "sess1"));

        verify(sessionService, never()).removeUserFromSession(any(), any());
        verify(messagingTemplate, never()).convertAndSend(anyString(), any(Object.class));
    }

    @Test
    void handleDisconnect_withNullSessionId_doesNothing() {
        listener.handleWebSocketDisconnectListener(buildEvent("u1", "alice", null));

        verify(sessionService, never()).removeUserFromSession(any(), any());
        verify(messagingTemplate, never()).convertAndSend(anyString(), any(Object.class));
    }

    private SessionDisconnectEvent buildEvent(String userId, String username, String sessionId) {
        Map<String, Object> attrs = new HashMap<>();
        attrs.put("userId", userId);
        attrs.put("username", username);
        attrs.put("sessionId", sessionId);

        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.DISCONNECT);
        accessor.setSessionAttributes(attrs);
        accessor.setSessionId("ws-session-id");

        Message<byte[]> message = MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());
        return new SessionDisconnectEvent(this, message, "ws-session-id", CloseStatus.NORMAL);
    }
}
