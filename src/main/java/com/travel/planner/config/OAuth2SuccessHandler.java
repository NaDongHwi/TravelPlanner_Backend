package com.travel.planner.config;

import com.travel.planner.util.JwtUtil;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.security.web.authentication.SimpleUrlAuthenticationSuccessHandler;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.Map;

@Component
@RequiredArgsConstructor
public class OAuth2SuccessHandler extends SimpleUrlAuthenticationSuccessHandler {

    private final JwtUtil jwtUtil;

    // 프론트엔드 콜백 주소 (배포 환경에 맞게 설정에서 바꾼다)
    @Value("${app.oauth2.redirect-uri:http://localhost:3000/oauth2/callback}")
    private String redirectUri;

    @Override
    @SuppressWarnings("unchecked")
    public void onAuthenticationSuccess(HttpServletRequest request, HttpServletResponse response, Authentication authentication) throws IOException, ServletException {
        OAuth2User oAuth2User = (OAuth2User) authentication.getPrincipal();

        String email;
        Map<String, Object> attributes = oAuth2User.getAttributes();
        if (attributes.containsKey("kakao_account")) {
            Map<String, Object> kakaoAccount = (Map<String, Object>) attributes.get("kakao_account");
            email = (String) kakaoAccount.get("email");
            if (email == null || email.isEmpty()) {
                email = attributes.get("id") + "@kakao.user";
            }
        } else if (attributes.containsKey("response")) {
            // 네이버 구조 파싱
            Map<String, Object> responseMap = (Map<String, Object>) attributes.get("response");
            email = (String) responseMap.get("id") + "@naver.user";
        } else {
            // 구글 등 표준 구조
            email = (String) attributes.get("email");
        }

        // 기존 시스템의 JWT 토큰 발급
        String token = jwtUtil.generateToken(email);

        // 토큰을 쿼리스트링(?token=)과 URL 프래그먼트(#token=) 양쪽에 모두 실어 보낸다.
        // - ?token= : 기존 프론트(앱의 웹뷰에서 콜백 주소를 가로채 queryParameters 로 읽는 방식)가 그대로 동작한다.
        // - #token= : 웹 프론트라면 location.hash 에서 읽고 주소창에서 지울 수 있다.
        // (프래그먼트만 보내도록 바꿨더니 쿼리에서 읽던 앱의 소셜 로그인이 끊겼다)
        String separator = redirectUri.contains("?") ? "&" : "?";
        String targetUrl = redirectUri + separator + "token=" + token + "#token=" + token;
        getRedirectStrategy().sendRedirect(request, response, targetUrl);
    }
}
