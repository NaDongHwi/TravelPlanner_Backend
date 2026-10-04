package com.travel.planner.dto;

import java.util.Map;

public class NaverUserInfo implements OAuth2UserInfo {
    private Map<String, Object> attributes;

    public NaverUserInfo(Map<String, Object> attributes) {
        this.attributes = (Map<String, Object>) attributes.get("response");
    }

    @Override
    public String getProviderId() { return (String) attributes.get("id"); }

    @Override
    public String getProvider() { return "naver"; }

    @Override
    public String getEmail() {
        // 네이버가 주는 이메일은 중복 충돌 위험이 있으므로, 무시하고 고유 ID로 이메일을 강제 생성
        return getProviderId() + "@naver.user";
    }

    @Override
    public String getName() { return (String) attributes.get("name"); }
}