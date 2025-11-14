package com.andrewstsai.instashare.dto;

import lombok.Data;

@Data
public class CreateSessionRequest {
    private String name;
    private Integer expirationHours;
}