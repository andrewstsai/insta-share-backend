package com.andrewstsai.instashare.controller;

import com.andrewstsai.instashare.dto.JoinMessage;
import com.andrewstsai.instashare.dto.SessionResponse;
import com.andrewstsai.instashare.dto.UserPresence;
import com.andrewstsai.instashare.model.ItemType;
import com.andrewstsai.instashare.model.SessionItem;
import com.andrewstsai.instashare.service.SessionService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.SimpMessageType;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import java.time.LocalDateTime;
import java.util.*;

import org.mockito.ArgumentMatchers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SessionWebSocketControllerTest {

    @Mock private SessionService sessionService;
    @Mock private SimpMessagingTemplate messagingTemplate;

    @InjectMocks private SessionWebSocketController controller;

    @Test
    void joinSession_unlockedSession_addsUserAndSendsMessages() {
        SessionResponse session = SessionResponse.builder()
                .id("sess1").isLocked(false).items(List.of()).build();
        when(sessionService.getSession("sess1")).thenReturn(session);
        when(sessionService.getSessionItems("sess1")).thenReturn(List.of(buildItem("i1")));
        when(sessionService.getSessionUsers("sess1")).thenReturn(Set.of("alice"));

        JoinMessage msg = new JoinMessage("alice", "u1");
        SimpMessageHeaderAccessor ha = SimpMessageHeaderAccessor.create(SimpMessageType.MESSAGE);
        ha.setSessionAttributes(new HashMap<>());

        controller.joinSession("sess1", msg, ha);

        verify(sessionService).addUserToSession("sess1", "u1", "alice");
        verify(messagingTemplate).convertAndSendToUser(eq("u1"), eq("/queue/initial-state"), any());

        ArgumentCaptor<UserPresence> presenceCaptor = ArgumentCaptor.forClass(UserPresence.class);
        verify(messagingTemplate).convertAndSend(eq("/topic/session/sess1/presence"), presenceCaptor.capture());
        assertThat(presenceCaptor.getValue().getAction()).isEqualTo("joined");

        verify(messagingTemplate).convertAndSend(eq("/topic/session/sess1/users"), ArgumentMatchers.<Object>any());
    }

    @Test
    void joinSession_lockedSession_doesNotAddUser() {
        SessionResponse session = SessionResponse.builder()
                .id("sess1").isLocked(true).items(List.of()).build();
        when(sessionService.getSession("sess1")).thenReturn(session);

        JoinMessage msg = new JoinMessage("alice", "u1");
        SimpMessageHeaderAccessor ha = SimpMessageHeaderAccessor.create(SimpMessageType.MESSAGE);
        ha.setSessionAttributes(new HashMap<>());

        controller.joinSession("sess1", msg, ha);

        verify(sessionService, never()).addUserToSession(any(), any(), any());
        verify(messagingTemplate, never()).convertAndSend(anyString(), any(Object.class));
    }

    @Test
    void joinSession_sessionNotFound_swallowsException() {
        when(sessionService.getSession(any())).thenThrow(new RuntimeException("not found"));

        JoinMessage msg = new JoinMessage("alice", "u1");
        SimpMessageHeaderAccessor ha = SimpMessageHeaderAccessor.create(SimpMessageType.MESSAGE);
        ha.setSessionAttributes(new HashMap<>());

        // Should not throw
        controller.joinSession("sess1", msg, ha);

        verify(sessionService, never()).addUserToSession(any(), any(), any());
    }

    @Test
    void joinSession_storesAttributesInHeaderAccessor() {
        SessionResponse session = SessionResponse.builder()
                .id("sess1").isLocked(false).items(List.of()).build();
        when(sessionService.getSession("sess1")).thenReturn(session);
        when(sessionService.getSessionItems("sess1")).thenReturn(Collections.emptyList());
        when(sessionService.getSessionUsers("sess1")).thenReturn(Collections.emptySet());

        Map<String, Object> attrs = new HashMap<>();
        SimpMessageHeaderAccessor ha = SimpMessageHeaderAccessor.create(SimpMessageType.MESSAGE);
        ha.setSessionAttributes(attrs);

        controller.joinSession("sess1", new JoinMessage("alice", "u1"), ha);

        assertThat(attrs).containsEntry("userId", "u1")
                         .containsEntry("username", "alice")
                         .containsEntry("sessionId", "sess1");
    }

    @Test
    void leaveSession_removesUserAndBroadcasts() {
        when(sessionService.getSessionUsers("sess1")).thenReturn(Set.of("bob"));

        controller.leaveSession("sess1", new JoinMessage("alice", "u1"));

        verify(sessionService).removeUserFromSession("sess1", "u1");

        ArgumentCaptor<UserPresence> presenceCaptor = ArgumentCaptor.forClass(UserPresence.class);
        verify(messagingTemplate).convertAndSend(eq("/topic/session/sess1/presence"), presenceCaptor.capture());
        assertThat(presenceCaptor.getValue().getAction()).isEqualTo("left");

        verify(messagingTemplate).convertAndSend(eq("/topic/session/sess1/users"), ArgumentMatchers.<Object>any());
    }

    @Test
    void updateItemPosition_withValidPayload_returnsMapAndCallsService() {
        Map<String, Object> payload = new HashMap<>();
        payload.put("itemId", "item1");
        payload.put("x", 15.0);
        payload.put("y", 30.0);

        Map<String, Object> result = controller.updateItemPosition("sess1", payload);

        verify(sessionService).updateItemPosition("sess1", "item1", 15.0, 30.0);
        assertThat(result).containsEntry("itemId", "item1")
                          .containsEntry("x", 15.0)
                          .containsEntry("y", 30.0)
                          .containsKey("timestamp");
    }

    @Test
    void updateItemPosition_missingItemId_throwsIllegalArgumentException() {
        Map<String, Object> payload = new HashMap<>();
        payload.put("x", 1.0);
        payload.put("y", 2.0);

        assertThatThrownBy(() -> controller.updateItemPosition("sess1", payload))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Missing required fields");
    }

    @Test
    void updateItemPosition_missingCoordinates_throwsIllegalArgumentException() {
        Map<String, Object> payload = new HashMap<>();
        payload.put("itemId", "item1");

        assertThatThrownBy(() -> controller.updateItemPosition("sess1", payload))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Missing required fields");
    }

    private SessionItem buildItem(String id) {
        return SessionItem.builder()
                .id(id).sessionId("sess1").type(ItemType.FILE)
                .fileName("f.txt").uploadedAt(LocalDateTime.now()).build();
    }
}
