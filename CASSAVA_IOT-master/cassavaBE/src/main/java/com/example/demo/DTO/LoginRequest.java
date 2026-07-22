package com.example.demo.DTO;

public class LoginRequest {
    private String username;
    private String password;

    // Quan trọng: Phải có Constructor không tham số để Jackson có thể tạo object từ JSON
    public LoginRequest() {
    }

    public LoginRequest(String username, String password) {
        this.username = username;
        this.password = password;
    }

    // Getter và Setter (Bắt buộc phải có để Spring nạp dữ liệu vào)
    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }
}