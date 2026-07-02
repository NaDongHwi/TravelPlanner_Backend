package com.travel.planner.util;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.security.Key;
import java.util.Date;

@Component
public class JwtUtil {

    private final Key key;
    // 출입증 유효기간: 24시간 (원하는 대로 밀리초 단위로 조절 가능)
    private final long accessTokenExpTime = 1000 * 60 * 60 * 24;

    // application.properties에 적어둔 비밀 도장을 가져와서 진짜 열쇠로 만듭니다.
    public JwtUtil(@Value("${jwt.secret}") String secretKey) {
        this.key = Keys.hmacShaKeyFor(secretKey.getBytes());
    }

    // 프론트엔드에게 줄 출입증(토큰)을 인쇄하는 메서드
    public String generateToken(String email) {
        return Jwts.builder()
                .setSubject(email) // 토큰 주인의 이름(이메일)
                .claim("role", "USER") // 이 사람의 권한 (일반 유저)
                .setIssuedAt(new Date()) // 발급 시간
                .setExpiration(new Date(System.currentTimeMillis() + accessTokenExpTime)) // 만료 시간
                .signWith(key, SignatureAlgorithm.HS256)
                .compact(); // 압축해서 문자열로 완성
    }

    // 토큰을 열어서 주인의 이름(이메일)을 꺼내는 기능
    public String extractEmail(String token) {
        return Jwts.parserBuilder()
                .setSigningKey(key)
                .build()
                .parseClaimsJws(token)
                .getBody()
                .getSubject();
    }

    // 이 토큰이 우리가 만든 진짜 토큰인지, 유효기간은 안 지났는지 검사하는 기능
    public boolean isTokenValid(String token) {
        try {
            Jwts.parserBuilder().setSigningKey(key).build().parseClaimsJws(token);
            return true; // 정상 토큰
        } catch (Exception e) {
            return false; // 위조되었거나 만료된 토큰
        }
    }
}