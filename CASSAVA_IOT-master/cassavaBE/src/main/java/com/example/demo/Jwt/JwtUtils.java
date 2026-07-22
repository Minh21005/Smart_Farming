package com.example.demo.Jwt;

import io.jsonwebtoken.*;
import org.springframework.stereotype.Component;
import java.util.Date;

@Component
public class JwtUtils {
    // Secret key (Trong thực tế nên để trong file application.properties)
    private final String jwtSecret = "DayLaMotChuoiBaoMatCucKyDaiChoDuAnSmartFarmingCuaKienNhamDamBaoAnToanTuyetDoi1234567890";
    private final int jwtExpirationMs = 86400000; // 1 ngày

    // Tạo JWT từ username và role
    public String generateJwtToken(String username, boolean admin) {
        return Jwts.builder()
                .setSubject(username)
                .claim("admin", admin) // Nhúng quyền vào token
                .setIssuedAt(new Date())
                .setExpiration(new Date((new Date()).getTime() + jwtExpirationMs))
                .signWith(SignatureAlgorithm.HS512, jwtSecret)
                .compact();
    }

    // Lấy username từ JWT
    public String getUserNameFromJwtToken(String token) {
        return Jwts.parser().setSigningKey(jwtSecret).parseClaimsJws(token).getBody().getSubject();
    }

    // Lấy quyền từ JWT
    public boolean getIsAdminFromJwtToken(String token) {
        Boolean isAdmin = Jwts.parser()
                .setSigningKey(jwtSecret)
                .parseClaimsJws(token)
                .getBody()
                .get("admin", Boolean.class);
        return isAdmin != null && isAdmin; // Trả về true nếu có và bằng true, ngược lại false
    }

    // Kiểm tra Token hợp lệ không
    public boolean validateJwtToken(String authToken) {
        try {
            Jwts.parser().setSigningKey(jwtSecret).parseClaimsJws(authToken);
            return true;
        } catch (JwtException | IllegalArgumentException e) {
            System.err.println("JWT không hợp lệ: " + e.getMessage());
        }
        return false;
    }
}