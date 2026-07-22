package com.example.demo.service;

import com.example.demo.DTO.UserResponseDTO;
import com.example.demo.Jwt.JwtUtils;
import com.example.demo.entity.User;
import com.example.demo.repositories.UserRepository;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
public class UserService {
    private final JwtUtils jwtUtils; // 1. Khai báo JwtUtil
    private final UserRepository userRepository;
    private final BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

    // 2. Tiêm JwtUtils thông qua Constructor
    public UserService(UserRepository userRepository, JwtUtils jwtUtils) {
        this.userRepository = userRepository;
        this.jwtUtils = jwtUtils;
    }

    public String register(User user) {
        if (userRepository.existsByUsername(user.getUsername())) {
            return "Lỗi: Username đã tồn tại!";
        }
        if (userRepository.existsByEmail(user.getEmail())) {
            return "Lỗi: Email đã tồn tại!";
        }

        // Mã hóa mật khẩu
        user.setPassword(passwordEncoder.encode(user.getPassword()));
        userRepository.save(user);
        return "Đăng ký thành công!";
    }
    public Map<String, Object> login(String username, String password) {
        return userRepository.findByUsername(username)
                .map(user -> {
                    Map<String, Object> response = new HashMap<>();
                    if (passwordEncoder.matches(password, user.getPassword())) {
                        // 3. Tạo Token THẬT chứa quyền admin (true/false)
                        String jwtToken = jwtUtils.generateJwtToken(user.getUsername(), user.isAdmin());
                        response.put("success", true);
                        response.put("id", user.getId());
                        response.put("username", user.getUsername());
                        // Tạm thời trả về username làm token nếu bạn chưa cài JWT
                        response.put("accessToken", jwtToken); // Đã thay fake token bằng thật
                        response.put("isAdmin", user.isAdmin());
                        return response;
                    }
                    response.put("success", false);
                    response.put("message", "Sai mật khẩu!");
                    return response;
                })
                .orElseGet(() -> {
                    Map<String, Object> response = new HashMap<>();
                    response.put("success", false);
                    response.put("message", "Không tìm thấy người dùng!");
                    return response;
                });
    }
    /**
     * method getListUser
     */
    public List<UserResponseDTO> getListUser() {
       List<User> users =  userRepository.findAll();
       return users.stream().map(user -> {
           UserResponseDTO dto = new UserResponseDTO();
           dto.setId(user.getId());
           dto.setUsername(user.getUsername());
           dto.setEmail(user.getEmail());
           dto.setAdmin(user.isAdmin());
           return dto;
       }).collect(Collectors.toList());
    }
}