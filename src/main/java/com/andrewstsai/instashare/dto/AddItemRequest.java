package com.andrewstsai.instashare.dto;

import com.andrewstsai.instashare.model.ItemType;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AddItemRequest {
    private ItemType type;
    private String s3Key;
    private String s3Url;
    private String fileName;
    private Long fileSize;
    private Double positionX;
    private Double positionY;
}