package com.travel.planner.util;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Date;
import java.util.Set;
import java.util.stream.Collectors;

@Component
public class JwtUtil {

    public static final String ROLE_USER = "USER";
    public static final String ROLE_ADMIN = "ADMIN";

    private final SecretKey key;
    // 출입증 유효기간: 24시간 (원하는 대로 밀리초 단위로 조절 가능)
    private final long accessTokenExpTime = 1000 * 60 * 60 * 24;

    /** 관리자 계정 이메일 목록 (application 설정의 app.admin-emails, 쉼표 구분). 여기에 있는 계정만 관리자 API 를 쓸 수 있다. */
    private final Set<String> adminEmails;

    // application.properties에 적어둔 비밀 도장을 가져와서 진짜 열쇠로 만듭니다.
    public JwtUtil(@Value("${jwt.secret}") String secretKey,
                   @Value("${app.admin-emails:}") String adminEmails) {
        this.key = Keys.hmacShaKeyFor(secretKey.getBytes(StandardCharsets.UTF_8));
        this.adminEmails = Arrays.stream(adminEmails.split(","))
                .map(s -> s.trim().toLowerCase())
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toSet());
    }

    public String roleOf(String email) {
        return email != null && adminEmails.contains(email.toLowerCase()) ? ROLE_ADMIN : ROLE_USER;
    }

    // 프론트엔드에게 줄 출입증(토큰)을 인쇄하는 메서드
    public String generateToken(String email) {
        return Jwts.builder()
                .subject(email) // 토큰 주인의 이름(이메일)
                .claim("role", roleOf(email)) // 권한 (USER / ADMIN)
                .issuedAt(new Date()) // 발급 시간
                .expiration(new Date(System.currentTimeMillis() + accessTokenExpTime)) // 만료 시간
                .signWith(key)
                .compact(); // 압축해서 문자열로 완성
    }

    private Claims claims(String token) {
        return Jwts.parser()
                .verifyWith(key)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    // 토큰을 열어서 주인의 이름(이메일)을 꺼내는 기능
    public String extractEmail(String token) {
        return claims(token).getSubject();
    }

    /**
     * 토큰의 권한. 토큰에 적힌 값만 믿지 않고 현재 관리자 목록과도 대조한다
     * (목록에서 뺀 계정의 기존 토큰이 만료 전까지 관리자 권한을 유지하지 않도록).
     */
    public String extractRole(String token) {
        Claims claims = claims(token);
        String role = claims.get("role", String.class);
        return ROLE_ADMIN.equals(role) && ROLE_ADMIN.equals(roleOf(claims.getSubject())) ? ROLE_ADMIN : ROLE_USER;
    }

    // 이 토큰이 우리가 만든 진짜 토큰인지, 유효기간은 안 지났는지 검사하는 기능
    public boolean isTokenValid(String token) {
        try {
            claims(token);
            return true; // 정상 토큰
        } catch (Exception e) {
            return false; // 위조되었거나 만료된 토큰
        }
    }
}
