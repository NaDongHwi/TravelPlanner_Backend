package com.travel.planner.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.travel.planner.dto.AiRouteResponse;
import com.travel.planner.entity.Log;
import com.travel.planner.entity.Place;
import com.travel.planner.repository.LogRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.*;

@Service
@RequiredArgsConstructor
public class AiService {

    @Value("${ai.gemini.api-key}")
    private String geminiApiKey;

    @Value("${ai.gemini.model}")
    private String geminiModel;

    @Value("${ai.openai.api-key:}")
    private String openAiApiKey;

    @Value("${ai.openai.model:gpt-3.5-turbo}") // GPT 기본 모델 지정
    private String openAiModel;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final LogRepository logRepository;
    private final RestTemplate restTemplate = new RestTemplate();

    // ============================================================================
    // 1. 알고리즘 기반 확정 동선 -> 스토리텔링 가이드 포맷팅 도구 (유지)
    // ============================================================================
    public AiRouteResponse generateStorytellingForValidatedRoute(
            List<PlanService.SimulatedItinerary> verifiedItineraries, String lang, int totalDays) {

        String targetLang = (lang != null && !lang.trim().isEmpty()) ? lang : "ko";
        StringBuilder rigidTimeline = new StringBuilder();
        int currentDay = 1;
        int count = 0;
        int placesPerDay = verifiedItineraries.size() / totalDays;

        for (PlanService.SimulatedItinerary iti : verifiedItineraries) {
            count++;
            if (count > placesPerDay && currentDay < totalDays) {
                currentDay++;
                count = 1;
            }
            rigidTimeline.append(String.format("Day %d - %s : %s\n", currentDay, iti.getTime(), iti.getPlace().getName()));
        }

        String prompt = "너는 여행 가이드야. 아래 일정은 [백엔드 자체 최적화 알고리즘]을 통해 검증이 완료된 완벽한 최종 타임라인이야.\n\n" +
                "[확정된 타임라인]\n" + rigidTimeline.toString() + "\n\n" +
                "【 절대 제약 조건 】\n" +
                "1. 내가 준 일정의 'Day', '시간(time)', '장소명(placeName)'은 1mm도 수정하거나 순서를 바꾸지 말고 그대로 출력해.\n" +
                "2. 너의 유일한 임무는 각 장소에 대한 1~2줄의 흥미로운 가이드 설명(description)과 카테고리(category)를 덧붙이는 것뿐이야.\n" +
                "3. 모든 출력 텍스트는 반드시 [" + targetLang + "] 언어로 번역해서 작성해.\n" +
                "4. 반드시 아래 JSON 형식으로만 응답해. 마크다운(```json)은 절대 쓰지 마.\n" +
                "{\n" +
                "  \"timeline\": [\n" +
                "    { \"day\": 1, \"time\": \"09:00\", \"placeName\": \"원본 장소명 그대로\", \"category\": \"관광/맛집 등\", \"description\": \"멋진 설명\" }\n" +
                "  ],\n" +
                "  \"reason\": \"자체 알고리즘 동선에 대한 총평 1줄\"\n" +
                "}";

        Map<String, Object> requestBody = buildGeminiRequest(prompt);
        String url = "[https://generativelanguage.googleapis.com/v1beta/models/](https://generativelanguage.googleapis.com/v1beta/models/)" + geminiModel + ":generateContent?key=" + geminiApiKey;

        int maxRetries = 3;
        int retryCount = 0;

        while (retryCount < maxRetries) {
            try {
                HttpHeaders headers = new HttpHeaders();
                headers.setContentType(MediaType.APPLICATION_JSON);
                HttpEntity<Map<String, Object>> request = new HttpEntity<>(requestBody, headers);

                ResponseEntity<String> response = restTemplate.postForEntity(url, request, String.class);
                return parseGeminiResponse(response.getBody(), AiRouteResponse.class);
            } catch (Exception e) {
                retryCount++;
                saveErrorLog("AI_GEMINI_STORYTELLING_FAIL_" + retryCount, e.getMessage());

                if (retryCount >= maxRetries) {
                    System.out.println("Gemini 스토리텔링 최종 실패! [OpenAI] 백업 모델 전환");
                    AiRouteResponse fallbackResponse = callFallbackOpenAi(prompt, AiRouteResponse.class);
                    return fallbackResponse != null ? fallbackResponse : createEmergencyFallbackResponse(verifiedItineraries, totalDays);
                }
                try { Thread.sleep(1000); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
            }
        }
        return createEmergencyFallbackResponse(verifiedItineraries, totalDays);
    }

    // ============================================================================
    // 2. 관리자용 오프라인 전처리 (리뷰 기반 테마 분류)
    // ============================================================================
    public Map<String, String> classifyPlaceAttributesBulk(List<Place> places, Map<String, String> reviewsMap) {
        StringBuilder promptBuilder = new StringBuilder();
        promptBuilder.append("너는 여행 데이터 정제 전문가야. 아래 나열된 장소들의 이름과 구글 리뷰를 분석해서, 테마와 장소 속성을 한 번에 분류해.\n\n");
        promptBuilder.append("【 분류 절대 규칙 】\n");
        promptBuilder.append("1. 테마: 반드시 [맛집, 쇼핑, 관광, 힐링, 사진, 서브컬쳐, 문화, 자연, 야경, 온천, 액티비티, 카페] 이 12개 단어 안에서만 1~3개를 선택해. 절대 다른 단어를 창조하지 마!\n");
        promptBuilder.append("2. 장소 속성: 이 장소의 '메인 활동'이 이루어지는 곳을 기준으로 무조건 [실내] 또는 [실외] 중 하나만 고정해서 적어.\n");
        promptBuilder.append("3. 결과는 반드시 장소 ID를 키로, '테마1,테마2|장소속성' 문자열을 값으로 하는 순수 JSON 객체 하나로만 반환해. 마크다운(```json) 금지.\n");
        promptBuilder.append("예시: {\"ChIJ1234\": \"쇼핑,서브컬쳐|실내\", \"ChIJ5678\": \"온천,힐링|실외\"}\n\n[분류 대상 목록]\n");

        for (Place p : places) {
            String reviews = reviewsMap.get(p.getPlaceId());
            promptBuilder.append("- ID: ").append(p.getPlaceId())
                    .append(" / 이름: ").append(p.getName())
                    .append(" / 리뷰: ").append(reviews != null ? reviews : "리뷰 없음").append("\n");
        }

        String prompt = promptBuilder.toString();
        Map<String, Object> requestBody = buildGeminiRequest(prompt);
        String url = "https://generativelanguage.googleapis.com/v1beta/models/" + geminiModel + ":generateContent?key=" + geminiApiKey;

        int maxRetries = 3;
        int retryCount = 0;

        while (retryCount < maxRetries) {
            try {
                HttpHeaders headers = new HttpHeaders();
                headers.setContentType(MediaType.APPLICATION_JSON);
                HttpEntity<Map<String, Object>> request = new HttpEntity<>(requestBody, headers);

                ResponseEntity<String> response = restTemplate.postForEntity(url, request, String.class);
                return parseGeminiResponse(response.getBody(), new TypeReference<Map<String, String>>(){});
            } catch (Exception e) {
                retryCount++;
                saveErrorLog("AI_ENRICHMENT_FAIL_" + retryCount, e.getMessage());

                if (retryCount >= maxRetries) {
                    System.out.println("Gemini 전처리(테마) 최종 실패! [OpenAI] 백업 모델 전환");
                    Map<String, String> fallbackResponse = callFallbackOpenAi(prompt, new TypeReference<Map<String, String>>(){});
                    return fallbackResponse != null ? fallbackResponse : new HashMap<>();
                }
                try { Thread.sleep(2000); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
            }
        }
        return new HashMap<>();
    }

    // ============================================================================
    // 3. 관리자용 오프라인 전처리 (5대 카테고리 정제)
    // ============================================================================
    public Map<String, String> cleansePlaceCategories(List<Place> places) {
        StringBuilder promptBuilder = new StringBuilder();
        promptBuilder.append("너는 여행 데이터 분류 전문가야. 다음 주어진 일본 장소들의 이름(한국/일어/영어 혼재)을 보고, ");
        promptBuilder.append("해당 장소가 다음 5가지 카테고리 중 어디에 속하는지 추론해: [관광지, 식음, 쇼핑, 숙소, 교통].\n");
        promptBuilder.append("결과는 반드시 장소 ID를 키(key)로, 카테고리를 값(value)으로 하는 순수 JSON 객체 하나로만 반환해. 마크다운 기호(```json)는 절대 넣지 마.\n");
        promptBuilder.append("예시: {\"ChIJ1234\": \"교통\", \"ChIJ5678\": \"식음\", \"ChIJ9012\": \"숙소\"}\n\n[분류 대상 목록]\n");

        for (Place p : places) {
            promptBuilder.append("- ID: ").append(p.getPlaceId()).append(" / 이름: ").append(p.getName()).append("\n");
        }

        String prompt = promptBuilder.toString();
        Map<String, Object> requestBody = buildGeminiRequest(prompt);
        String url = "[https://generativelanguage.googleapis.com/v1beta/models/](https://generativelanguage.googleapis.com/v1beta/models/)" + geminiModel + ":generateContent?key=" + geminiApiKey;

        int maxRetries = 3;
        int retryCount = 0;

        while (retryCount < maxRetries) {
            try {
                HttpHeaders headers = new HttpHeaders();
                headers.setContentType(MediaType.APPLICATION_JSON);
                HttpEntity<Map<String, Object>> request = new HttpEntity<>(requestBody, headers);

                ResponseEntity<String> response = restTemplate.postForEntity(url, request, String.class);
                return parseGeminiResponse(response.getBody(), new TypeReference<Map<String, String>>(){});
            } catch (Exception e) {
                retryCount++;
                saveErrorLog("AI_CLEANSING_FAIL_" + retryCount, e.getMessage());

                if (retryCount >= maxRetries) {
                    System.out.println("Gemini 정제(카테고리) 최종 실패! [OpenAI] 백업 모델 전환");
                    Map<String, String> fallbackResponse = callFallbackOpenAi(prompt, new TypeReference<Map<String, String>>(){});
                    return fallbackResponse != null ? fallbackResponse : new HashMap<>();
                }
                try { Thread.sleep(2000); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
            }
        }
        return new HashMap<>();
    }

    // ============================================================================
    // 유틸리티 메서드 (제미나이 파싱, 오픈AI 호출, 에러 로그)
    // ============================================================================
    private Map<String, Object> buildGeminiRequest(String prompt) {
        Map<String, Object> requestBody = new HashMap<>();
        Map<String, Object> contents = new HashMap<>();
        Map<String, Object> parts = new HashMap<>();
        parts.put("text", prompt);
        contents.put("parts", Collections.singletonList(parts));
        requestBody.put("contents", Collections.singletonList(contents));
        return requestBody;
    }

    // Class 타입(객체) 파싱
    private <T> T parseGeminiResponse(String responseBody, Class<T> valueType) throws Exception {
        JsonNode rootNode = objectMapper.readTree(responseBody);
        String aiText = rootNode.path("candidates").get(0).path("content").path("parts").get(0).path("text").asText();
        aiText = aiText.replace("```json", "").replace("```", "").trim();
        return objectMapper.readValue(aiText, valueType);
    }

    // TypeReference 타입(Map, List 등 제네릭) 파싱
    private <T> T parseGeminiResponse(String responseBody, TypeReference<T> valueTypeRef) throws Exception {
        JsonNode rootNode = objectMapper.readTree(responseBody);
        String aiText = rootNode.path("candidates").get(0).path("content").path("parts").get(0).path("text").asText();
        aiText = aiText.replace("```json", "").replace("```", "").trim();
        return objectMapper.readValue(aiText, valueTypeRef);
    }

    // 모든 API에서 공통으로 사용할 수 있는 범용 GPT 백업 호출 메서드
    private <T> T callFallbackOpenAi(String prompt, Object typeOrClass) {
        if (openAiApiKey == null || openAiApiKey.isEmpty()) return null;

        try {
            String gptUrl = "[https://api.openai.com/v1/chat/completions](https://api.openai.com/v1/chat/completions)";
            Map<String, Object> requestBody = new HashMap<>();
            requestBody.put("model", openAiModel);

            Map<String, String> message = new HashMap<>();
            message.put("role", "user");
            message.put("content", prompt);
            requestBody.put("messages", Collections.singletonList(message));

            Map<String, Object> responseFormat = new HashMap<>();
            responseFormat.put("type", "json_object");
            requestBody.put("response_format", responseFormat);

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.setBearerAuth(openAiApiKey);

            HttpEntity<Map<String, Object>> request = new HttpEntity<>(requestBody, headers);
            ResponseEntity<String> response = restTemplate.postForEntity(gptUrl, request, String.class);

            JsonNode rootNode = objectMapper.readTree(response.getBody());
            String gptText = rootNode.path("choices").get(0).path("message").path("content").asText().trim();

            if (typeOrClass instanceof Class) {
                return (T) objectMapper.readValue(gptText, (Class<?>) typeOrClass);
            } else if (typeOrClass instanceof TypeReference) {
                return (T) objectMapper.readValue(gptText, (TypeReference<?>) typeOrClass);
            }
        } catch (Exception e) {
            saveErrorLog("AI_FATAL_GPT_FAIL", e.getMessage());
        }
        return null;
    }

    private AiRouteResponse createEmergencyFallbackResponse(List<PlanService.SimulatedItinerary> routes, int totalDays) {
        // 기존 뼈대 유지
        AiRouteResponse failoverResponse = new AiRouteResponse();
        List<AiRouteResponse.TimelineItem> fallbackTimeline = new ArrayList<>();
        int currentDay = 1;
        int count = 0;
        int placesPerDay = routes.size() / totalDays;

        for (PlanService.SimulatedItinerary iti : routes) {
            count++;
            if (count > placesPerDay && currentDay < totalDays) {
                currentDay++;
                count = 1;
            }
            AiRouteResponse.TimelineItem item = new AiRouteResponse.TimelineItem();
            item.setDay(currentDay);
            item.setTime(iti.getTime());
            item.setPlaceName(iti.getPlace().getName());
            item.setCategory("시스템 분류");
            item.setDescription("자체 알고리즘에 의해 자동 생성된 기본 경로입니다. (AI 설명 지연)");
            fallbackTimeline.add(item);
        }
        failoverResponse.setTimeline(fallbackTimeline);
        failoverResponse.setReason("AI 시스템 장애로 기본 알고리즘 최적화 동선만 출력되었습니다.");
        return failoverResponse;
    }

    private void saveErrorLog(String errorType, String message) {
        try {
            Log errorLog = new Log();
            errorLog.setErrorType(errorType);
            errorLog.setErrorMessage(message != null ? message : "Unknown Error");
            logRepository.save(errorLog);
        } catch (Exception ignore) {}
    }
}