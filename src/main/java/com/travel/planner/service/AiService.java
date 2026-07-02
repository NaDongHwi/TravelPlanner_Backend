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
                "아래 [고객 정보]와 거리 기반으로 1차 계산된 [임시 동선], 그리고 [구글 맵스 실제 이동 시간]을 확인해. \n\n" +
                "[고객 정보]: " + userContext + "\n" +
                "[임시 동선]: " + draftRouteStr + "\n\n" +
                "【 최고 수준의 지능형 최적화 제약 조건 】\n" +
                "1. [임시 동선]은 단순히 '거리'만 짧게 이은 초안일 뿐이야. 전체적인 동선의 효율성(이동 낭비 최소화)은 유지하되, 고객의 취향, 동행자, 테마(가중치)에 심각하게 맞지 않는 장소는 과감하게 삭제하고 그 지역 내의 더 알맞은 장소로 전면 교체해.\n" +
                "2. 단, 해당 지역을 방문했다면 무조건 봐야 하는 '핵심 랜드마크'라면 테마와 조금 어긋나더라도 유지하는 등, 실제 베테랑 가이드처럼 유연하고 똑똑하게 가치 판단을 해.\n" +
                "3. [고정 일정 예외 처리] 고객 정보에 명시된 '고정일정(필수방문)'은 타임라인에 무조건 최우선으로 포함시켜야 해. " +
                "단, 고정 일정 간의 물리적 이동 거리가 너무 멀거나 하루 안에 소화하기 불가능한 '논리적 충돌'이 발생할 경우, " +
                "일정에는 포함하되 `reason` 필드에 반드시 '고정 일정 간의 이동 거리가 멀어 현실적인 이동이 매우 촉박할 수 있으니 주의가 필요합니다.' 와 같은 강력한 경고 메시지를 포함해!\n" +
                "4. 숙소 위치와 구글 맵스의 실제 이동 시간을 바탕으로, 식사 시간과 휴식 시간, 장소별 적정 관람 시간까지 현실적으로 완벽하게 계산된 일자별(day) 타임라인을 짜줘.\n\n" +
                "응답은 반드시 아래의 JSON 형식으로만 출력해. 마크다운(```json)이나 다른 설명은 절대 넣지 마.\n" +
                "{\n" +
                "  \"timeline\": [\n" +
                "    { \"day\": 1, \"time\": \"09:00\", \"placeName\": \"장소명1\", \"category\": \"관광/맛집/문화 등\", \"description\": \"이 장소에 대한 1줄 설명\" },\n" +
                "    { \"day\": 1, \"time\": \"11:30\", \"placeName\": \"장소명2\", \"category\": \"관광/맛집/문화 등\", \"description\": \"이 장소에 대한 1줄 설명\" }\n" +
                "  ],\n" +
                "  \"reason\": \"임시 동선에서 어떤 가중치와 판단(랜드마크 유지, 비적합 장소 교체 등)을 거쳐 이 타임라인을 완성했는지 2~3줄로 설명\"\n" +
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