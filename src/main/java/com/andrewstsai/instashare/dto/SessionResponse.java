package com.andrewstsai.instashare.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.time.LocalDateTime;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SessionResponse {
    private String id;
    private String name;
    private String shareUrl;
    private Boolean isLocked;
    private LocalDateTime createdAt;
    private LocalDateTime expiresAt;
    private List<SessionItemResponse> items;
}