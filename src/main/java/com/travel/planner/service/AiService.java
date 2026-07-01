package com.travel.planner.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.travel.planner.dto.AiRouteResponse;
import com.travel.planner.entity.Place;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.List;
import java.util.stream.Collectors;

@Service
public class AiService {

    @Value("${ai.gemini.api-key}")
    private String geminiApiKey;

    private final ObjectMapper objectMapper = new ObjectMapper(); // JSON 변환 도구

    /**
     * K-Means/TSP로 짜인 '임시 초안'과 '사용자 취향'을 AI에게 넘겨 최종본을 받아냅니다.
     */
    public AiRouteResponse evaluateAndModifyRoute(String userContext, List<Place> draftRoute) {
        // 1. 임시 동선을 AI가 읽기 쉽게 글자로 변환
        String draftRouteStr = draftRoute.stream()
                .map(Place::getName)
                .collect(Collectors.joining(" -> "));

        // 2. 제약 프롬프트 (거리 무시 방지)
        String prompt = "너는 10년 차 전문 일본 여행 플래너야. " +
                "아래 [고객 정보]와 알고리즘이 1차로 연산한 [임시 동선]을 확인해. \n\n" +
                "[고객 정보]: " + userContext + "\n" +
                "[임시 동선]: " + draftRouteStr + "\n\n" +
                "【 엄격한 제약 조건 】\n" +
                "1. 고객의 나이, 성별, 테마를 반영하여 장소를 1~2곳 교체해도 좋아.\n" +
                "2. 단, '임시 동선'의 물리적인 이동 거리와 지리적 범위(클러스터)를 절대 벗어나지 마. " +
                "이동이 불가능한 엉뚱한 지역의 장소를 추천하면 안 돼.\n" +
                "3. 가중치나 테마에 너무 치우쳐서 밥 먹을 시간도 없이 빡빡하게 짜지 마.\n\n" +
                "응답은 반드시 아래의 JSON 형식으로만 출력해. 마크다운(```json)이나 다른 설명은 절대 넣지 마.\n" +
                "{\n" +
                "  \"finalRouteNames\": [\"장소명1\", \"장소명2\", \"장소명3\"],\n" +
                "  \"reason\": \"어떤 테마를 반영했고, 왜 이 장소로 수정했는지 2~3줄로 설명\"\n" +
                "}";

        // 3. 구글 제미나이에 보낼 JSON 양식 만들기
        String requestBody = "{ \"contents\": [{ \"parts\":[{\"text\": \"" + prompt + "\"}] }] }";
        String url = "[https://generativelanguage.googleapis.com/v1beta/models/gemini-1.5-pro:generateContent?key=](https://generativelanguage.googleapis.com/v1beta/models/gemini-1.5-pro:generateContent?key=)" + geminiApiKey;

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        HttpEntity<String> request = new HttpEntity<>(requestBody, headers);
        RestTemplate restTemplate = new RestTemplate();

        try {
            // (키를 받기 전까지는 실행 시 에러가 날 수 있으므로 주석 처리하거나 예외를 잡습니다)
            // String responseJson = restTemplate.postForObject(url, request, String.class);
            // return parseGeminiResponse(responseJson);

            // 임시 테스트용 리턴 객체 (프론트엔드 테스트용)
            AiRouteResponse mockResponse = new AiRouteResponse();
            mockResponse.setFinalRouteNames(List.of("시즈오카역", "슨푸성 공원", "아오바 요코초"));
            mockResponse.setReason("20대 여성의 감성 카페 테마를 반영하여 동선을 일부 수정하였으며, 도보 이동이 가능한 거리로만 재구성했습니다.");
            return mockResponse;

        } catch (Exception e) {
            // [장애 발생 시 백업 로직] AI가 뻗으면 1차 초안(임시 동선)을 그대로 리턴합니다.
            AiRouteResponse failoverResponse = new AiRouteResponse();
            failoverResponse.setFinalRouteNames(draftRoute.stream().map(Place::getName).collect(Collectors.toList()));
            failoverResponse.setReason("기본 동선 최적화 알고리즘이 적용되었습니다. (AI 맞춤형 기능 일시 지연)");
            return failoverResponse;
        }
    }

    // 💡 제미나이가 뱉은 복잡한 JSON에서 원하는 값만 빼내는 파싱 도구
    private AiRouteResponse parseGeminiResponse(String responseBody) throws Exception {
        JsonNode rootNode = objectMapper.readTree(responseBody);
        // 구글 제미나이 응답 구조 안에서 실제 텍스트 빼오기
        String aiText = rootNode.path("candidates").get(0).path("content").path("parts").get(0).path("text").asText();

        // 텍스트를 다시 Java 객체(AiRouteResponse)로 변환
        return objectMapper.readValue(aiText, AiRouteResponse.class);
    }
}