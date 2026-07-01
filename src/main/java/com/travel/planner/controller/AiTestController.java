package com.travel.planner.controller;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestTemplate;
import java.util.Map;

@RestController
@RequestMapping("/api/test")
public class AiTestController {

    // 비밀 금고(secret.yml)에서 Gemini 키를 몰래 가져옵니다.
    @Value("${ai.gemini.api-key}")
    private String geminiApiKey;

    @GetMapping("/gemini")
    public String testGemini() {
        // Gemini 2.5 Pro API 통신 주소
        String url = "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-pro:generateContent?key=" + geminiApiKey;

        // 질문 세팅 (프롬프트 테스트)
        String prompt = "일본 오사카 3박 4일 여행에서 교통 약자(휠체어)를 위한 숙소 위치를 딱 한 곳만 추천하고 그 사유를 1줄로 말해줘.";

        // HTTP 요청 만들기 (간이 테스트용)
        RestTemplate restTemplate = new RestTemplate();
        String requestBody = "{ \"contents\": [{ \"parts\":[{\"text\": \"" + prompt + "\"}] }] }";

        try {
            // 구글 서버에 질문을 던지고 답변을 받아옵니다.
            String response = restTemplate.postForObject(url, requestBody, String.class);
            return "Gemini 응답 성공: " + response;
        } catch (Exception e) {
            return "에러 발생: " + e.getMessage();
        }
    }
}