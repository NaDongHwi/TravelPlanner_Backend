package com.travel.planner.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.travel.planner.dto.AiRouteResponse;
import com.travel.planner.entity.Place;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.*;
import java.util.stream.Collectors;

@Service
public class AiService {

    @Value("${ai.gemini.api-key}")
    private String geminiApiKey;

    @Value("${ai.gemini.model}") // yml 파일에서 모델 이름을 동적으로 읽어옵니다.
    private String geminiModel;

    private final ObjectMapper objectMapper = new ObjectMapper(); // JSON 변환 도구

    public AiRouteResponse evaluateAndModifyRoute(String userContext, List<Place> draftRoute) {

        // 1. 임시 동선을 AI가 읽기 쉽게 글자로 변환
        String draftRouteStr = draftRoute.stream()
                .map(Place::getName)
                .collect(Collectors.joining(" -> "));

        String prompt = "너는 10년 차 전문 일본 여행 플래너야. " +
                "아래 [고객 정보]와 [임시 동선], [구글 맵스 실제 이동 시간], 그리고 가장 중요한 [장소별 실제 영업시간]을 확인해. \n\n" +
                "[고객 정보]: " + userContext + "\n" +
                "[임시 동선]: " + draftRouteStr + "\n\n" +
                "【 최고 수준의 지능형 최적화 제약 조건 】\n" +
                "1. [임시 동선]은 단순히 '거리'만 짧게 이은 초안일 뿐이야. 고객 취향에 맞지 않는 장소는 과감히 삭제/교체하고, 같은 구역(예: 아사쿠사 센소지/나카미세도리)은 1시간 단위로 잘게 쪼개지 말고 하나의 흐름으로 자연스럽게 묶어내.\n" +
                "2. [영업시간 절대 엄수] 함께 전달된 '장소별 실제 영업시간'을 무조건 계산해서, 오픈 전이나 마감 후(닫힌 문 앞)에 방문하는 치명적인 오류를 절대 내지 마. (예: 아침 시장은 무조건 오전에 배치)\n" +
                "3. [선형 동선 보장] 이미 거쳐 간 지역으로 저녁에 다시 돌아가는(예: 시부야 -> 하라주쿠 -> 시부야) 비효율적인 지그재그 동선을 절대 짜지 마. 야경이 목적이라면 전체 동선 흐름을 늦추어 일직선을 유지해.\n" +
                "4. [고정 일정 예외 처리] '고정일정(필수방문)'은 타임라인에 무조건 최우선으로 포함시키되, 하루 안에 소화하기 불가능한 거리가 발견되면 `reason` 필드에 강력한 경고 메시지를 적어.\n" +
                "5. 장소명에는 괄호 '()'나 '또는', '예:', '런치:' 같은 부연 설명을 절대 포함하지 마. 오직 명사 형태의 고유명사 하나만 출력해야 해.\n\n" +
                "응답은 반드시 아래의 JSON 형식으로만 출력해. 마크다운(```json)이나 다른 설명은 절대 넣지 마.\n" +
                "{\n" +
                "  \"timeline\": [\n" +
                "    { \"day\": 1, \"time\": \"09:00\", \"placeName\": \"장소명1\", \"category\": \"관광/맛집/문화 등\", \"description\": \"이 장소에 대한 1줄 설명\" },\n" +
                "    { \"day\": 1, \"time\": \"11:30\", \"placeName\": \"장소명2\", \"category\": \"관광/맛집/문화 등\", \"description\": \"이 장소에 대한 1줄 설명\" }\n" +
                "  ],\n" +
                "  \"reason\": \"임시 동선에서 어떤 영업시간과 가중치를 고려하여 이 타임라인을 완성했는지 2~3줄로 설명\"\n" +
                "}";

        // 2. 프롬프트에 줄바꿈이나 특수기호가 많으므로, Map을 써서 안전하게 JSON으로 포장합니다.
        Map<String, Object> requestBody = new HashMap<>();
        Map<String, Object> contents = new HashMap<>();
        Map<String, Object> parts = new HashMap<>();
        parts.put("text", prompt);
        contents.put("parts", Collections.singletonList(parts));
        requestBody.put("contents", Collections.singletonList(contents));

        // 3. 동적 모델명이 들어간 구글 제미나이 API 주소
        String url = "https://generativelanguage.googleapis.com/v1beta/models/" + geminiModel + ":generateContent?key=" + geminiApiKey;

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        HttpEntity<Map<String, Object>> request = new HttpEntity<>(requestBody, headers);
        RestTemplate restTemplate = new RestTemplate();

        try {
            // 4. 제미나이 서버로 요청
            ResponseEntity<String> response = restTemplate.postForEntity(url, request, String.class);
            return parseGeminiResponse(response.getBody());

        } catch (Exception e) {
            // [장애 발생 시 백업 로직] AI가 뻗으면 1차 초안(임시 동선)을 새로운 타임라인 규격에 맞춰서 리턴합니다.
            AiRouteResponse failoverResponse = new AiRouteResponse();
            List<AiRouteResponse.TimelineItem> fallbackTimeline = new ArrayList<>();

            for (int i = 0; i < draftRoute.size(); i++) {
                AiRouteResponse.TimelineItem item = new AiRouteResponse.TimelineItem();
                item.setDay(1); // 우선 임시로 모두 1일 차로 배정
                item.setTime("시간 미정");
                item.setPlaceName(draftRoute.get(i).getName());
                item.setCategory("분류 미정");
                item.setDescription("AI 연동 지연으로 인한 기본 알고리즘 경로입니다.");
                fallbackTimeline.add(item);
            }

            failoverResponse.setTimeline(fallbackTimeline);
            failoverResponse.setReason("AI 연동 지연으로 기본 알고리즘 최적화 동선이 적용되었습니다. (" + e.getMessage() + ")");
            return failoverResponse;
        }
    }

    // 제미나이가 뱉은 복잡한 JSON에서 원하는 값만 빼내는 파싱 도구
    private AiRouteResponse parseGeminiResponse(String responseBody) throws Exception {
        JsonNode rootNode = objectMapper.readTree(responseBody);

        // 구글 제미나이 응답 구조 안에서 실제 텍스트 빼오기
        String aiText = rootNode.path("candidates").get(0).path("content").path("parts").get(0).path("text").asText();

        // AI가 지시를 무시하고 ```json ... ``` 같은 마크다운 기호를 붙일 경우를 대비해 찌꺼기를 잘라냅니다.
        aiText = aiText.replace("```json", "").replace("```", "").trim();

        // 텍스트를 다시 Java 객체(AiRouteResponse)로 변환
        return objectMapper.readValue(aiText, AiRouteResponse.class);
    }
}