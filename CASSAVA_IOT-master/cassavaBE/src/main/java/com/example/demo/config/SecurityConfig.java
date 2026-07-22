/**package com.example.demo.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import java.util.Arrays;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Bean
    public BCryptPasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
                // 1. Cấu hình CORS để cho phép React (localhost:3000) truy cập
                .cors(cors -> cors.configurationSource(request -> {
                    CorsConfiguration config = new CorsConfiguration();
                    config.setAllowedOrigins(Arrays.asList("http://localhost:5173")); // Link của React
                    config.setAllowedMethods(Arrays.asList("GET", "POST", "PUT", "DELETE", "OPTIONS"));
                    config.setAllowedHeaders(Arrays.asList("*"));
                    config.setAllowCredentials(true);
                    return config;
                }))

                // 2. Tắt CSRF (Bắt buộc cho API POST/PUT)
                .csrf(csrf -> csrf.disable())

                // 3. Cho phép tất cả request để test nhanh
                .authorizeHttpRequests(auth -> auth
                        .anyRequest().permitAll()
                );

        return http.build();
    }
}
 */
package com.example.demo.config;

import com.example.demo.Jwt.JwtAuthFilter;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

@Configuration
@EnableWebSecurity
@EnableMethodSecurity // Cho phép dùng annotation @PreAuthorize ở Controller
public class SecurityConfig {

    @Autowired
    private JwtAuthFilter jwtAuthFilter;

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
                .csrf(AbstractHttpConfigurer::disable) // Tắt CSRF vì mình dùng JWT
                .cors(cors -> cors.configure(http))    // Mở CORS cho React gọi
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        // Cho phép vào thẳng API đăng nhập không cần token
                        .requestMatchers("/api/auth/login","/api/auth/register").permitAll()

                        // Trạm thời tiết: User hoặc Admin đều xem được
                        .requestMatchers("/api/weather/**").hasAnyRole("USER", "ADMIN")

                        // Quản lý cánh đồng: Chỉ Admin mới được can thiệp
                                .requestMatchers("/api/auth/list",
                                        "/mongo/field/createField",
                                        "/mongo/field/updateField/**",
                                        "/mongo/field/clone/**",
                                        "/mongo/field/delete/**",
                                        "/mongo/field/resetCrop/**").hasRole("ADMIN")
                        // SỬA Ở ĐÂY: Thêm cả "/mongo/field" (không gạch chéo) và "/mongo/field/**" (có gạch chéo)
                       // .requestMatchers("mongo/irrigation-history/**","/simulation/**","/api/sensor-values/**","/field/**","/mongo/**","/mongo/field", "/mongo/field/**").permitAll()

                        // Các API khác yêu cầu phải đăng nhập
                     //  .anyRequest().authenticated()
                        .anyRequest().permitAll()

                );

        // Chèn cái Filter gác cổng của mình lên trước Filter mặc định của Spring
        http.addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }
}