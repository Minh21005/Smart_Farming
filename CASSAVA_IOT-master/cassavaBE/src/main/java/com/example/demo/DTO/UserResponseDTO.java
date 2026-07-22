package com.example.demo.DTO;

import lombok.Data;

@Data
public class UserResponseDTO {
    private String id;
    private String username;
    private String email;
    private boolean admin;
    // Cố tình không cho trường password vào đây
}