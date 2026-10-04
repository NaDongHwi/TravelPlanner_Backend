package com.travel.planner.service;

import com.travel.planner.dto.GoogleUserInfo;
import com.travel.planner.dto.KakaoUserInfo;
import com.travel.planner.dto.NaverUserInfo;
import com.travel.planner.dto.OAuth2UserInfo;
import com.travel.planner.entity.User;
import com.travel.planner.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.client.userinfo.DefaultOAuth2UserService;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserRequest;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.user.DefaultOAuth2User;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class CustomOAuth2UserService extends DefaultOAuth2UserService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    @Override
    public OAuth2User loadUser(OAuth2UserRequest userRequest) throws OAuth2AuthenticationException {
        OAuth2User oAuth2User = super.loadUser(userRequest);
        String provider = userRequest.getClientRegistration().getRegistrationId();

        OAuth2UserInfo oAuth2UserInfo = null;
        if (provider.equals("google")) {
            oAuth2UserInfo = new GoogleUserInfo(oAuth2User.getAttributes());
        } else if (provider.equals("kakao")) {
            oAuth2UserInfo = new KakaoUserInfo(oAuth2User.getAttributes());
        } else if (provider.equals("naver")) {
            oAuth2UserInfo = new NaverUserInfo(oAuth2User.getAttributes());
        } else {
            throw new OAuth2AuthenticationException("지원하지 않는 로그인 방식입니다.");
        }

        String providerId = oAuth2UserInfo.getProviderId();
        String email = oAuth2UserInfo.getEmail();

        User user = userRepository.findByEmail(email).orElse(null);
        if (user == null) {
            user = new User();
            user.setEmail(email);
            // 소셜 회원은 임의의 난수 비밀번호를 암호화해서 부여합니다.
            user.setPassword(passwordEncoder.encode(UUID.randomUUID().toString()));
            user.setProvider(provider);
            user.setProviderId(providerId);
            // 최초 가입 시 미정 처리. 추후 마이페이지에서 수정하도록 유도
            user.setGender("미정");
            user.setAgeGroup("미정");

            userRepository.save(user);
        }

        return new DefaultOAuth2User(
                Collections.emptyList(),
                oAuth2User.getAttributes(),
                provider.equals("naver") ? "response" : (provider.equals("google") ? "sub" : "id")
        );
    }
}