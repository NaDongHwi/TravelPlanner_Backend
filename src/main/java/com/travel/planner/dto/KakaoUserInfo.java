package com.travel.planner.dto;

import java.util.Map;

public class KakaoUserInfo implements OAuth2UserInfo {
    private Map<String, Object> attributes;
    private Map<String, Object> kakaoAccount;

    public KakaoUserInfo(Map<String, Object> attributes) {
        this.attributes = attributes;
        this.kakaoAccount = (Map<String, Object>) attributes.get("kakao_account");
    }

    @Override
    public String getProviderId() { return String.valueOf(attributes.get("id")); }

    @Override
    public String getProvider() { return "kakao"; }

    @Override
    public String getEmail() {
        String email = (String) kakaoAccount.get("email");
        // 비즈앱이 아니라서 이메일을 못 받아와서 고유 ID로 가짜 이메일 생성
        if (email == null || email.isEmpty()) {
            return getProviderId() + "@kakao.user"; // 예: 3456789012@kakao.user
        }
        return email;
    }

    @Override
    public String getName() {
        Map<String, Object> profile = (Map<String, Object>) kakaoAccount.get("profile");
        return profile != null ? (String) profile.get("nickname") : null;
    }
}