package com.andrewstsai.instashare.dto;

import com.andrewstsai.instashare.model.ItemType;
import lombok.Data;

@Data
public class AddItemRequest {
    private ItemType type;
    private String s3Key;
    private String s3Url;
    private String content;
    private String fileName;
    private Long fileSize;
    private String uploadedBy;
    private Integer positionX;
    private Integer positionY;
}