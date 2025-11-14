package com.andrewstsai.instashare.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class UserPresence {
    private String userId;
    private String username;
    private String action;
}