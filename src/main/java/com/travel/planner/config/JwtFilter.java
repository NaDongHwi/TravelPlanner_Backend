package com.travel.planner.config;

import com.travel.planner.util.JwtUtil;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

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
            String email = jwtUtil.extractEmail(token);
            // 토큰의 권한(USER/ADMIN)을 시큐리티 권한으로 옮긴다. 관리자 API 는 ROLE_ADMIN 만 통과한다.
            String role = jwtUtil.extractRole(token);
            UsernamePasswordAuthenticationToken authenticationToken =
                    new UsernamePasswordAuthenticationToken(email, null, List.of(new SimpleGrantedAuthority("ROLE_" + role)));
            SecurityContextHolder.getContext().setAuthentication(authenticationToken);
        } else {
            // 토큰이 유효하지 않으면 401 에러를 프론트엔드로 확실하게 쏴주고 여기서 끝냄
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setContentType("text/plain;charset=UTF-8");
            response.getWriter().write("토큰이 만료되었거나 유효하지 않습니다. 다시 로그인해주세요.");
            return; // 다음 필터로 안 넘어가게 강제 종료!
        }

        // 5. 다음 단계로 보냅니다.
        filterChain.doFilter(request, response);
    }
}
