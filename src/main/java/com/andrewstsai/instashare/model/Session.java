package com.andrewstsai.instashare.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.redis.core.RedisHash;
import org.springframework.data.redis.core.TimeToLive;
import org.springframework.data.redis.core.index.Indexed;

import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.Set;

@RedisHash(value = "session", timeToLive = 86400) // 24 hours
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Session {

    @Id
    private String id;

    @Indexed
    private String creatorId;

    private String title;
    private String password;
    private LocalDateTime createdAt;
    private LocalDateTime expiresAt;

    @Builder.Default
    private Set<String> activeParticipants = new HashSet<>();

    @Builder.Default
    private Boolean isLocked = false;

    private Integer maxUploads;
    private Long maxStorageBytes;

    @TimeToLive
    private Integer expiration;
}