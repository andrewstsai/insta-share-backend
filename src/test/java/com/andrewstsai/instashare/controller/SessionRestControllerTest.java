package com.andrewstsai.instashare.controller;

import com.andrewstsai.instashare.dto.SessionItemResponse;
import com.andrewstsai.instashare.dto.SessionResponse;
import com.andrewstsai.instashare.model.ItemType;
import com.andrewstsai.instashare.service.S3Service;
import com.andrewstsai.instashare.service.SessionService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;
import java.util.List;

import org.mockito.ArgumentMatchers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(SessionRestController.class)
@TestPropertySource(locations = "classpath:application-test.properties")
class SessionRestControllerTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;

    @MockitoBean private SessionService sessionService;
    @MockitoBean private S3Service s3Service;
    @MockitoBean private SimpMessagingTemplate messagingTemplate;

    @Test
    void createSession_returnsOk() throws Exception {
        SessionResponse response = SessionResponse.builder()
                .id("abc12345").name("Test Session").isLocked(false).items(List.of()).build();
        when(sessionService.createSession(any())).thenReturn(response);

        mockMvc.perform(post("/api/sessions")
                        .contentType("application/json")
                        .content("{\"name\":\"Test Session\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value("abc12345"))
                .andExpect(jsonPath("$.name").value("Test Session"))
                .andExpect(jsonPath("$.isLocked").value(false))
                .andExpect(jsonPath("$.items").isEmpty());
    }

    @Test
    void createSession_withNullName_usesDefault() throws Exception {
        SessionResponse response = SessionResponse.builder()
                .id("abc12345").name("Untitled Session").isLocked(false).items(List.of()).build();
        when(sessionService.createSession(any())).thenReturn(response);

        mockMvc.perform(post("/api/sessions")
                        .contentType("application/json")
                        .content("{\"name\":null}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Untitled Session"));
    }

    @Test
    void getSession_whenFound_returnsOk() throws Exception {
        SessionItemResponse item = SessionItemResponse.builder()
                .id("item1").type(ItemType.FILE).fileName("a.txt").build();
        SessionResponse response = SessionResponse.builder()
                .id("sess1").name("S").isLocked(false).items(List.of(item)).build();
        when(sessionService.getSession("sess1")).thenReturn(response);

        mockMvc.perform(get("/api/sessions/sess1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value("sess1"))
                .andExpect(jsonPath("$.items.length()").value(1));
    }

    @Test
    void getSession_whenNotFound_propagatesException() {
        when(sessionService.getSession(any())).thenThrow(new RuntimeException("Session not found or expired"));

        assertThatThrownBy(() -> mockMvc.perform(get("/api/sessions/missing")))
                .hasRootCauseInstanceOf(RuntimeException.class)
                .hasRootCauseMessage("Session not found or expired");
    }

    @Test
    void uploadFile_withImageFile_returnsOkAndBroadcasts() throws Exception {
        MockMultipartFile file = new MockMultipartFile(
                "file", "photo.png", "image/png", "data".getBytes());
        SessionItemResponse item = SessionItemResponse.builder()
                .id("i1").type(ItemType.IMAGE).fileName("photo.png")
                .uploadedAt(LocalDateTime.now()).build();

        when(s3Service.uploadFile(any(), eq("sess1"))).thenReturn("sessions/sess1/uuid_photo.png");
        when(s3Service.generatePresignedUrl(any())).thenReturn("https://example.com/photo.png");
        when(sessionService.addItem(eq("sess1"), any())).thenReturn(item);

        mockMvc.perform(multipart("/api/sessions/sess1/upload")
                        .file(file)
                        .param("positionX", "10.5")
                        .param("positionY", "20.0"))
                .andExpect(status().isOk());

        verify(messagingTemplate).convertAndSend(eq("/topic/session/sess1/items"), ArgumentMatchers.<Object>any());
    }

    @Test
    void uploadFile_withEmptyFile_throwsIllegalArgument() {
        MockMultipartFile empty = new MockMultipartFile("file", "x.txt", "text/plain", new byte[0]);

        assertThatThrownBy(() -> mockMvc.perform(multipart("/api/sessions/sess1/upload")
                        .file(empty)
                        .param("positionX", "0.0")
                        .param("positionY", "0.0")))
                .hasRootCauseInstanceOf(IllegalArgumentException.class)
                .hasRootCauseMessage("File cannot be empty");
    }

    @Test
    void uploadFile_detectsImageType() throws Exception {
        assertDetectedType("image/png", ItemType.IMAGE);
    }

    @Test
    void uploadFile_detectsPdfType() throws Exception {
        assertDetectedType("application/pdf", ItemType.PDF);
    }

    @Test
    void uploadFile_detectsVideoType() throws Exception {
        assertDetectedType("video/mp4", ItemType.VIDEO);
    }

    @Test
    void uploadFile_detectsAudioType() throws Exception {
        assertDetectedType("audio/mpeg", ItemType.AUDIO);
    }

    @Test
    void uploadFile_unknownContentType_defaultsToFile() throws Exception {
        assertDetectedType("application/zip", ItemType.FILE);
    }

    @Test
    void toggleLock_returnsOkAndBroadcasts() throws Exception {
        SessionResponse response = SessionResponse.builder()
                .id("sess1").name("S").isLocked(true).items(List.of()).build();
        when(sessionService.toggleSessionLock("sess1")).thenReturn(response);

        mockMvc.perform(patch("/api/sessions/sess1/lock"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.isLocked").value(true));

        verify(messagingTemplate).convertAndSend(eq("/topic/session/sess1/lock-status"), ArgumentMatchers.<Object>any());
    }

    @Test
    void deleteSession_returnsNoContent() throws Exception {
        mockMvc.perform(delete("/api/sessions/sess1"))
                .andExpect(status().isNoContent());

        verify(sessionService).deleteSession("sess1");
    }

    @Test
    void deleteItem_returnsNoContentAndBroadcasts() throws Exception {
        mockMvc.perform(delete("/api/sessions/sess1/items/item1"))
                .andExpect(status().isNoContent());

        verify(sessionService).removeItem("sess1", "item1");
        verify(messagingTemplate).convertAndSend(eq("/topic/session/sess1/item-deleted"), ArgumentMatchers.<Object>any());
    }

    private void assertDetectedType(String contentType, ItemType expected) throws Exception {
        MockMultipartFile file = new MockMultipartFile(
                "file", "upload.bin", contentType, "data".getBytes());
        SessionItemResponse item = SessionItemResponse.builder()
                .id("i1").type(expected).uploadedAt(LocalDateTime.now()).build();

        when(s3Service.uploadFile(any(), any())).thenReturn("key");
        when(s3Service.generatePresignedUrl(any())).thenReturn("https://example.com/url");
        when(sessionService.addItem(any(), any())).thenReturn(item);

        ArgumentCaptor<com.andrewstsai.instashare.dto.AddItemRequest> captor =
                ArgumentCaptor.forClass(com.andrewstsai.instashare.dto.AddItemRequest.class);

        mockMvc.perform(multipart("/api/sessions/sess1/upload")
                        .file(file)
                        .param("positionX", "0.0")
                        .param("positionY", "0.0"))
                .andExpect(status().isOk());

        verify(sessionService, atLeastOnce()).addItem(any(), captor.capture());
        assertThat(captor.getValue().getType()).isEqualTo(expected);

        reset(sessionService, s3Service);
    }
}
