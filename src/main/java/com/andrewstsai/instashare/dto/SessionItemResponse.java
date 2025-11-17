package com.andrewstsai.instashare.dto;

import com.andrewstsai.instashare.model.ItemType;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SessionItemResponse {
    private String id;
    private ItemType type;
    private String s3Url;
    private String fileName;
    private Long fileSize;
    private LocalDateTime uploadedAt;
    private Double positionX;
    private Double positionY;
}
