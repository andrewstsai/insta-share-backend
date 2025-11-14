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

@RedisHash(value = "sessionItem", timeToLive = 86400)
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SessionItem {

    @Id
    private String id;

    @Indexed
    private String sessionId;

    private ItemType type;
    private String fileName;
    private String originalFileName;
    private Long fileSize;
    private String contentType;
    private String s3Key;
    private String s3Url;
    private String content;
    private Integer positionX;
    private Integer positionY;
    private Integer zIndex;
    private String uploadedBy;
    private LocalDateTime uploadedAt;

    @TimeToLive
    private Integer expiration;
}