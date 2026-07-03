package com.travel.planner.controller;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestTemplate;

@RestController
@RequestMapping("/api/test")
public class AiTestController {

    // 1. API 키 불러오기
    @Value("${ai.gemini.api-key}")
    private String geminiApiKey;

    // 2. 모델명도 yml에서 불러오기
    @Value("${ai.gemini.model}")
    private String geminiModel;

    @GetMapping("/gemini")
    public String testGemini() {
        // 3. 하드코딩된 pro/flash 글자를 지우고 변수로 조립
        String url = "https://generativelanguage.googleapis.com/v1beta/models/" + geminiModel + ":generateContent?key=" + geminiApiKey;

        String prompt = "일본 오사카 3박 4일 여행에서 교통 약자(휠체어)를 위한 숙소 위치를 딱 한 곳만 추천하고 그 사유를 1줄로 말해줘.";
        String requestBody = "{ \"contents\": [{ \"parts\":[{\"text\": \"" + prompt + "\"}] }] }";

        try {
            RestTemplate restTemplate = new RestTemplate();
            String response = restTemplate.postForObject(url, requestBody, String.class);
            return "Gemini 응답 성공: " + response;
        } catch (Exception e) {
            return "에러 발생: " + e.getMessage();
        }
    }
}