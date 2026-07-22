package com.example.demo.service;

import com.auth0.jwt.JWT;
import com.auth0.jwt.algorithms.Algorithm;
import com.example.demo.entity.User;
import org.springframework.stereotype.Service;

import java.util.Date;

@Service
public class JwtService {
    private static final String Secret_key = "123";
    public String generateToken(User user) {
        Algorithm algorithm = Algorithm.HMAC256(Secret_key.getBytes());
        return JWT.create()
                .withSubject(user.getEmail())
                .withExpiresAt(new Date(System.currentTimeMillis() + 50*60*1000))
                .sign(algorithm);
    }
}
