package com.andrewstsai.instashare.dto;

import lombok.*;

@Data
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class JoinMessage {
    private String username;
    private String userId;
}