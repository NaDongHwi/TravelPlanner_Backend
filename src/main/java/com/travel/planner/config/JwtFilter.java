package com.travel.planner.config;

import com.travel.planner.util.JwtUtil;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Collections;

@Component
@RequiredArgsConstructor
public class JwtFilter extends OncePerRequestFilter {

    private final JwtUtil jwtUtil;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {

        // 1. 요청 헤더에서 "Authorization" 칸을 찾아 출입증을 꺼냅니다.
        String authorizationHeader = request.getHeader("Authorization");

        // 2. 출입증이 없거나, "Bearer "로 시작하지 않으면 그냥 통과시킵니다. (나중에 시큐리티가 알아서 막아줌)
        if (authorizationHeader == null || !authorizationHeader.startsWith("Bearer ")) {
            filterChain.doFilter(request, response);
            return;
        }

        // 3. "Bearer " 글자를 떼어내고 순수 암호문(토큰)만 남깁니다.
        String token = authorizationHeader.substring(7);

        // 4. 문지기가 토큰이 진짜인지 확인합니다.
        if (jwtUtil.isTokenValid(token)) {
            // 진짜라면 토큰에서 이메일을 뽑아냅니다.
            String email = jwtUtil.extractEmail(token);

            // 스프링 시큐리티에게 "이 사람 신분 확인됐어 통과시켜 줘" 라고 보고합니다.
            UsernamePasswordAuthenticationToken authenticationToken =
                    new UsernamePasswordAuthenticationToken(email, null, Collections.emptyList());
            SecurityContextHolder.getContext().setAuthentication(authenticationToken);
        }

        // 5. 다음 단계로 보냅니다.
        filterChain.doFilter(request, response);
    }
}