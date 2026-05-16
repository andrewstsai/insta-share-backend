package com.andrewstsai.instashare.service;

import com.andrewstsai.instashare.dto.AddItemRequest;
import com.andrewstsai.instashare.dto.CreateSessionRequest;
import com.andrewstsai.instashare.dto.SessionItemResponse;
import com.andrewstsai.instashare.dto.SessionResponse;
import com.andrewstsai.instashare.model.ItemType;
import com.andrewstsai.instashare.model.Session;
import com.andrewstsai.instashare.model.SessionItem;
import com.andrewstsai.instashare.repository.SessionItemRepository;
import com.andrewstsai.instashare.repository.SessionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.RedisTemplate;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SessionServiceTest {

    @Mock private SessionRepository sessionRepository;
    @Mock private SessionItemRepository sessionItemRepository;
    @Mock private RedisTemplate<String, Object> redisTemplate;
    @Mock private HashOperations<String, Object, Object> hashOperations;
    @Mock private S3Service s3Service;

    @InjectMocks private SessionService sessionService;

    @BeforeEach
    void setUp() {
        when(redisTemplate.opsForHash()).thenReturn(hashOperations);
    }

    @Test
    void createSession_withName_returnsResponse() {
        CreateSessionRequest request = new CreateSessionRequest();
        request.setName("My Session");

        SessionResponse response = sessionService.createSession(request);

        assertThat(response.getName()).isEqualTo("My Session");
        assertThat(response.getIsLocked()).isFalse();
        assertThat(response.getItems()).isEmpty();
        verify(sessionRepository).save(any(Session.class));
    }

    @Test
    void createSession_withNullName_usesDefault() {
        CreateSessionRequest request = new CreateSessionRequest();
        request.setName(null);

        SessionResponse response = sessionService.createSession(request);

        assertThat(response.getName()).isEqualTo("Untitled Session");
    }

    @Test
    void createSession_generatesUniqueShortId() {
        CreateSessionRequest request = new CreateSessionRequest();
        request.setName("A");

        SessionResponse r1 = sessionService.createSession(request);
        SessionResponse r2 = sessionService.createSession(request);

        assertThat(r1.getId()).isNotNull().hasSize(8);
        assertThat(r1.getId()).isNotEqualTo(r2.getId());
    }

    @Test
    void getSession_whenFound_returnsMappedResponseWithItems() {
        Session session = Session.builder().id("sess1").name("S").isLocked(false).build();
        SessionItem item1 = buildItem("i1", "sess1");
        SessionItem item2 = buildItem("i2", "sess1");

        when(sessionRepository.findById("sess1")).thenReturn(Optional.of(session));
        when(sessionItemRepository.findBySessionId("sess1")).thenReturn(List.of(item1, item2));

        SessionResponse response = sessionService.getSession("sess1");

        assertThat(response.getId()).isEqualTo("sess1");
        assertThat(response.getItems()).hasSize(2);
        assertThat(response.getItems().get(0).getId()).isEqualTo("i1");
    }

    @Test
    void getSession_whenNotFound_throwsRuntimeException() {
        when(sessionRepository.findById(any())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> sessionService.getSession("missing"))
                .isInstanceOf(RuntimeException.class)
                .hasMessage("Session not found or expired");
    }

    @Test
    void addItem_savesItemAndReturnsMappedResponse() {
        Session session = Session.builder().id("sess1").build();
        when(sessionRepository.findById("sess1")).thenReturn(Optional.of(session));

        AddItemRequest request = AddItemRequest.builder()
                .type(ItemType.IMAGE)
                .s3Key("sessions/sess1/uuid_photo.png")
                .s3Url("https://example.com/photo.png")
                .fileName("photo.png")
                .fileSize(1024L)
                .positionX(10.0)
                .positionY(20.0)
                .build();

        SessionItemResponse response = sessionService.addItem("sess1", request);

        verify(sessionItemRepository).save(any(SessionItem.class));
        assertThat(response.getType()).isEqualTo(ItemType.IMAGE);
        assertThat(response.getFileName()).isEqualTo("photo.png");
        assertThat(response.getFileSize()).isEqualTo(1024L);
        assertThat(response.getPositionX()).isEqualTo(10.0);
        assertThat(response.getPositionY()).isEqualTo(20.0);
        assertThat(response.getUploadedAt()).isNotNull();
    }

    @Test
    void addItem_whenSessionNotFound_throwsRuntimeException() {
        when(sessionRepository.findById(any())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> sessionService.addItem("missing", AddItemRequest.builder().build()))
                .isInstanceOf(RuntimeException.class)
                .hasMessage("Session not found or expired");
    }

    @Test
    void removeItem_deletesS3AndItem() {
        SessionItem item = buildItem("item1", "sess1");
        item.setS3Key("sessions/sess1/key");
        when(sessionItemRepository.findById("item1")).thenReturn(Optional.of(item));

        sessionService.removeItem("sess1", "item1");

        verify(s3Service).deleteFile("sessions/sess1/key");
        verify(sessionItemRepository).delete(item);
    }

    @Test
    void removeItem_whenNoS3Key_skipsS3Delete() {
        SessionItem item = buildItem("item1", "sess1");
        item.setS3Key(null);
        when(sessionItemRepository.findById("item1")).thenReturn(Optional.of(item));

        sessionService.removeItem("sess1", "item1");

        verify(s3Service, never()).deleteFile(any());
        verify(sessionItemRepository).delete(item);
    }

    @Test
    void removeItem_whenWrongSession_throwsRuntimeException() {
        SessionItem item = buildItem("item1", "other-session");
        when(sessionItemRepository.findById("item1")).thenReturn(Optional.of(item));

        assertThatThrownBy(() -> sessionService.removeItem("sess1", "item1"))
                .isInstanceOf(RuntimeException.class)
                .hasMessage("Item does not belong to this session");

        verify(sessionItemRepository, never()).delete(any());
    }

    @Test
    void removeItem_whenItemNotFound_throwsRuntimeException() {
        when(sessionItemRepository.findById(any())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> sessionService.removeItem("sess1", "missing"))
                .isInstanceOf(RuntimeException.class)
                .hasMessage("Item not found or expired");
    }

    @Test
    void removeItem_whenS3Throws_stillDeletesItem() {
        SessionItem item = buildItem("item1", "sess1");
        item.setS3Key("sessions/sess1/key");
        when(sessionItemRepository.findById("item1")).thenReturn(Optional.of(item));
        doThrow(new RuntimeException("S3 error")).when(s3Service).deleteFile(any());

        sessionService.removeItem("sess1", "item1");

        verify(sessionItemRepository).delete(item);
    }

    @Test
    void deleteSession_deletesItemsS3AndRedisKeys() {
        Session session = Session.builder().id("sess1").build();
        SessionItem itemWithKey = buildItem("i1", "sess1");
        itemWithKey.setS3Key("sessions/sess1/key");
        SessionItem itemNoKey = buildItem("i2", "sess1");

        when(sessionRepository.findById("sess1")).thenReturn(Optional.of(session));
        when(sessionItemRepository.findBySessionId("sess1")).thenReturn(List.of(itemWithKey, itemNoKey));
        when(redisTemplate.keys("session:sess1:*")).thenReturn(Set.of("session:sess1:users"));

        sessionService.deleteSession("sess1");

        verify(s3Service, times(1)).deleteFile("sessions/sess1/key");
        verify(sessionItemRepository).deleteBySessionId("sess1");
        verify(redisTemplate).delete(anyCollection());
        verify(sessionRepository).deleteById("sess1");
    }

    @Test
    void deleteSession_whenSessionNotFound_throwsRuntimeException() {
        when(sessionRepository.findById(any())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> sessionService.deleteSession("missing"))
                .isInstanceOf(RuntimeException.class)
                .hasMessage("Session not found");
    }

    @Test
    void deleteSession_whenRedisKeysEmpty_skipsRedisDelete() {
        Session session = Session.builder().id("sess1").build();
        when(sessionRepository.findById("sess1")).thenReturn(Optional.of(session));
        when(sessionItemRepository.findBySessionId("sess1")).thenReturn(Collections.emptyList());
        when(redisTemplate.keys("session:sess1:*")).thenReturn(Collections.emptySet());

        sessionService.deleteSession("sess1");

        verify(redisTemplate, never()).delete(anyCollection());
    }

    @Test
    void addUserToSession_putsInHashAndSetsExpiry() {
        sessionService.addUserToSession("sess1", "u1", "alice");

        verify(hashOperations).put("session:sess1:users", "u1", "alice");
        verify(redisTemplate).expire("session:sess1:users", Duration.ofHours(3));
    }

    @Test
    void removeUserFromSession_deletesFromHash() {
        sessionService.removeUserFromSession("sess1", "u1");

        verify(hashOperations).delete("session:sess1:users", "u1");
    }

    @Test
    void getSessionUsers_returnsSetFromHashValues() {
        when(hashOperations.values("session:sess1:users")).thenReturn(List.of("alice", "bob"));

        Set<Object> users = sessionService.getSessionUsers("sess1");

        assertThat(users).containsExactlyInAnyOrder("alice", "bob");
    }

    @Test
    void getSessionUserCount_returnsSizeOfHash() {
        when(hashOperations.size("session:sess1:users")).thenReturn(3L);

        assertThat(sessionService.getSessionUserCount("sess1")).isEqualTo(3);
    }

    @Test
    void getSessionItems_delegatesToRepository() {
        List<SessionItem> items = List.of(buildItem("i1", "sess1"), buildItem("i2", "sess1"), buildItem("i3", "sess1"));
        when(sessionItemRepository.findBySessionId("sess1")).thenReturn(items);

        assertThat(sessionService.getSessionItems("sess1")).hasSize(3);
        verify(sessionItemRepository).findBySessionId("sess1");
    }

    @Test
    void updateItemPosition_savesUpdatedItem() {
        SessionItem item = buildItem("item1", "sess1");
        when(sessionItemRepository.findById("item1")).thenReturn(Optional.of(item));

        sessionService.updateItemPosition("sess1", "item1", 15.0, 30.0);

        assertThat(item.getPositionX()).isEqualTo(15.0);
        assertThat(item.getPositionY()).isEqualTo(30.0);
        verify(sessionItemRepository).save(item);
    }

    @Test
    void updateItemPosition_whenItemNotFound_throwsRuntimeException() {
        when(sessionItemRepository.findById(any())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> sessionService.updateItemPosition("sess1", "missing", 1.0, 2.0))
                .isInstanceOf(RuntimeException.class)
                .hasMessage("Item not found");
    }

    @Test
    void updateItemPosition_whenWrongSession_throwsRuntimeException() {
        SessionItem item = buildItem("item1", "other-session");
        when(sessionItemRepository.findById("item1")).thenReturn(Optional.of(item));

        assertThatThrownBy(() -> sessionService.updateItemPosition("sess1", "item1", 1.0, 2.0))
                .isInstanceOf(RuntimeException.class)
                .hasMessage("Item does not belong to this session");

        verify(sessionItemRepository, never()).save(any());
    }

    @Test
    void getItemPosition_whenKeyExists_returnsParsedMap() {
        Map<Object, Object> stored = new HashMap<>();
        stored.put("x", "10.5");
        stored.put("y", "20.0");
        when(hashOperations.entries("session:sess1:item:item1:position")).thenReturn(stored);

        Map<String, Double> result = sessionService.getItemPosition("sess1", "item1");

        assertThat(result).containsEntry("x", 10.5).containsEntry("y", 20.0);
    }

    @Test
    void getItemPosition_whenMapEmpty_returnsNull() {
        when(hashOperations.entries(any())).thenReturn(Collections.emptyMap());

        assertThat(sessionService.getItemPosition("sess1", "item1")).isNull();
    }

    @Test
    void toggleSessionLock_whenUnlocked_setsLockedTrue() {
        Session session = Session.builder().id("sess1").name("S").isLocked(false).build();
        when(sessionRepository.findById("sess1")).thenReturn(Optional.of(session));
        when(sessionItemRepository.findBySessionId("sess1")).thenReturn(Collections.emptyList());

        SessionResponse response = sessionService.toggleSessionLock("sess1");

        assertThat(response.getIsLocked()).isTrue();
        verify(sessionRepository).save(session);
    }

    @Test
    void toggleSessionLock_whenLocked_setsLockedFalse() {
        Session session = Session.builder().id("sess1").name("S").isLocked(true).build();
        when(sessionRepository.findById("sess1")).thenReturn(Optional.of(session));
        when(sessionItemRepository.findBySessionId("sess1")).thenReturn(Collections.emptyList());

        SessionResponse response = sessionService.toggleSessionLock("sess1");

        assertThat(response.getIsLocked()).isFalse();
    }

    @Test
    void toggleSessionLock_whenSessionNotFound_throwsRuntimeException() {
        when(sessionRepository.findById(any())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> sessionService.toggleSessionLock("missing"))
                .isInstanceOf(RuntimeException.class)
                .hasMessage("Session not found");
    }

    private SessionItem buildItem(String id, String sessionId) {
        return SessionItem.builder()
                .id(id)
                .sessionId(sessionId)
                .type(ItemType.FILE)
                .fileName("file.txt")
                .fileSize(100L)
                .s3Url("https://example.com/" + id)
                .uploadedAt(LocalDateTime.now())
                .positionX(0.0)
                .positionY(0.0)
                .build();
    }
}
