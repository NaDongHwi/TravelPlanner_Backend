package com.travel.planner.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
public class SecurityConfig {

    // 1. 비밀번호를 복호화 불가능한 해시로 뭉개버리는 도구를 준비합니다.
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    // 2. 시큐리티가 스웨거나 회원가입 API를 막지 않도록 임시로 문을 열어줍니다.
    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
                .csrf(csrf -> csrf.disable()) // REST API 환경에서는 보통 CSRF를 끕니다.
                .authorizeHttpRequests(auth -> auth
                        // 지금은 개발 중이니 모든 API 주소("/**")를 일단 통과시켜 줍니다.
                        .requestMatchers("/**").permitAll()
                );
        return http.build();
    }
}