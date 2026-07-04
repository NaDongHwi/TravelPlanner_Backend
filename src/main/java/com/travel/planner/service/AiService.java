package com.travel.planner.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.type.TypeReference;
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

    public AiRouteResponse evaluateAndModifyRoute(String userContext, List<Place> draftRoute, String lang) {

        // 기본 언어 방어 로직 (null 일 경우 한국어 기본값)
        String targetLang = (lang != null && !lang.trim().isEmpty()) ? lang : "ko";

        // 1. 임시 동선을 AI가 읽기 쉽게 글자로 변환
        String draftRouteStr = draftRoute.stream()
                .map(Place::getName)
                .collect(Collectors.joining(" -> "));

        String prompt = "너는 10년 차 전문 일본 여행 플래너야. " +
                "아래 [고객 정보]와 거리 기반으로 1차 계산된 [임시 동선(뼈대)], [구글 이동 시간], [장소별 영업시간 및 테마], 그리고 [실시간 날씨]를 정밀하게 분석해. \n\n" +
                "[고객 정보]: " + userContext + "\n" +
                "[임시 동선]: " + draftRouteStr + "\n\n" +
                "【 지능형 복합 최적화 및 절대 제약 조건 】\n" +
                "1. [임시 동선 구역화] 임시 동선은 단순히 '거리'만 이은 초안이야. 같은 구역은 1시간 단위로 잘게 쪼개지 말고 하나의 흐름으로 자연스럽게 묶어내.\n" +
                "2. [선형 동선 보장] 이미 거쳐 간 지역으로 저녁에 다시 돌아가는 비효율적인 지그재그 동선을 절대 짜지 마. 일직선 흐름을 유지해.\n" +
                "3. [테마 가중치 방어] 고객이 선택한 테마는 '선호도 가점'일 뿐 절대 규칙이 아니야. 억지로 테마를 맞추기 위해 물리적 동선을 파괴하지 마.\n" +
                "4. [식사 시간 보장] 점심(12:00~14:00)과 저녁(18:00~20:30) 시간에는 동선 상에 위치한 적절한 음식점이나 식당가를 무조건 1곳 이상 배치해.\n" +
                "5. [날씨/환경 가중치] 실시간 기상 정보에 '비', '눈' 등이 있다면 야외 명소 일정을 줄이고 실내 명소 비중을 높여.\n" +
                "6. [고정 일정 예외 처리] '고정일정(필수방문)'은 타임라인에 무조건 최우선으로 포함시켜.\n" +
                "7. [영업시간 절대 엄수] 전달된 오픈/마감 시간을 철저히 분석하여, 문이 닫혀있는 시간에 방문하는 오류를 내지 마.\n" +
                "8. 장소명에는 괄호 '()'나 '또는', '예:' 같은 부연 설명을 절대 포함하지 마. 오직 명사 형태의 고유명사 하나만 출력해야 해.\n" +
                "9. [지리적 이상치 제거] 제공된 장소 목록 중에 메인 목적지와 물리적으로 너무 멀리 떨어져서 이동 시간이 비상식적으로 오래 걸리는 장소는 과감히 버려.\n" +
                "10. [자율적 동적 제안 허용] 내가 제공한 데이터에 없더라도, 해당 지역 여행 시 절대 빠지면 안 되는 필수 명소가 있다면 너의 지식하에 타임라인에 자유롭게 추가해.\n\n" +
                "11.【 다국어 로컬라이징 및 번역 절대 규칙 】\n" +
                "    - 출력되는 모든 문장(`description`, `category`, `reason`)과 장소명(`placeName`)은 반드시 다음 지정된 언어로만 작성해.\n" +
                "    - [요청된 출력 언어]: " + targetLang + "\n" +
                "    - 장소명(`placeName`)은 해당 언어의 사용자가 구글 지도에서 검색하거나 읽기 가장 친숙한 명칭으로 출력해. (예: 언어가 'ko'이면 '히메지 성', 'en'이면 'Himeji Castle', 'ja'이면 '姫路城')\n" +
                "    - 번역 시 의미가 왜곡되거나 완전히 허구의 명칭을 지어내서는 안 돼.\n\n" +
                "12. [역할(Category) 절대 분리 규칙] 제공된 장소 데이터의 '카테고리' 속성을 반드시 확인해!\n" +
                "    - [교통] 카테고리(역, 공항)는 관광 일정으로 쓰지 말고, 지역 간 이동 동선의 기준점으로만 활용해.\n" +
                "    - [숙소] 카테고리(호텔, 료칸)는 일정표 중간에 끼워 넣지 말고, 무조건 체크인 / 체크아웃 목적지로만 배치해.\n" +
                "    - [식음], [쇼핑], [관광지] 카테고리 위주로만 실제 타임라인을 채워.\n\n" +
                "응답은 반드시 아래의 JSON 형식으로만 출력해. 마크다운 기호는 절대 넣지 마.\n" +
                "{\n" +
                "  \"timeline\": [\n" +
                "    { \"day\": 1, \"time\": \"09:00\", \"placeName\": \"장소명1\", \"category\": \"관광/맛집/문화 등\", \"description\": \"이 장소에 대한 설명\" }\n" +
                "  ],\n" +
                "  \"reason\": \"최종 결과물 완성 상세 설명\"\n" +
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

    // 오프라인 전처리 전용: 리뷰를 기반으로 장소의 테마 카테고리 자동 분류
    // 테마와 실내/외 여부를 한 번에 분류
    public String classifyPlaceAttributes(String placeName, String reviewsText) {
        String prompt = "너는 여행 데이터 정제 전문가야. 장소: [" + placeName + "]와 구글 리뷰를 분석해.\n\n" +
                "【 분류 절대 규칙 】\n" +
                "1. 테마: [맛집, 쇼핑, 관광, 힐링, 사진, 서브컬쳐, 문화, 자연, 야경, 온천, 액티비티, 카페] 중 가장 적합한 1~3개를 선택.\n" +
                "2. 장소 속성: 이 장소의 '메인 활동'이 이루어지는 곳을 기준으로 [실내] 또는 [실외] 중 무조건 하나만 강제로 선택해!\n" +
                "   - 건물 안에서 쇼핑/식사를 한다면 무조건 [실내] (예: 애니메이트, 스시집, 백화점)\n" +
                "   - 지붕이 없는 야외 공원, 길거리, 신사라면 무조건 [실외] (예: 센소지, 신주쿠 쿄엔, 하치코 동상)\n" +
                "   - [복합]이라는 단어는 실내랑, 실외 판단이 완벽히 5:5로 갈리는 대형 테마파크(디즈니랜드 등)가 아니면 절대 쓰지 마.\n" +
                "3. 반드시 아래의 텍스트 형식으로만 출력해! 다른 설명은 절대 쓰지 마.\n" +
                "형식: 테마1,테마2|장소속성\n" +
                "예시: 쇼핑,서브컬쳐|실내";

        Map<String, Object> requestBody = new HashMap<>();
        Map<String, Object> contents = new HashMap<>();
        Map<String, Object> parts = new HashMap<>();
        parts.put("text", prompt);
        contents.put("parts", Collections.singletonList(parts));
        requestBody.put("contents", Collections.singletonList(contents));

        String url = "https://generativelanguage.googleapis.com/v1beta/models/" + geminiModel + ":generateContent?key=" + geminiApiKey;
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        HttpEntity<Map<String, Object>> request = new HttpEntity<>(requestBody, headers);
        RestTemplate restTemplate = new RestTemplate();

        try {
            ResponseEntity<String> response = restTemplate.postForEntity(url, request, String.class);
            com.fasterxml.jackson.databind.JsonNode rootNode = objectMapper.readTree(response.getBody());
            String aiResult = rootNode.path("candidates").get(0).path("content").path("parts").get(0).path("text").asText().trim();
            return aiResult.replace("```", "").trim();
        } catch (Exception e) {
            System.out.println("AI 테마/속성 분석 에러: " + e.getMessage());
            // 쓰레기 데이터 적재를 막기 위해 억지 기본값 대신 null을 반환
            return null;
        }
    }

    // [관리자 전용] 기존 장소 데이터를 AI가 추론하여 5대 카테고리로 분류
    public Map<String, String> cleansePlaceCategories(List<Place> places) {
        StringBuilder promptBuilder = new StringBuilder();
        promptBuilder.append("너는 여행 데이터 분류 전문가야. 다음 주어진 일본 장소들의 이름(한국어/일본어/영어 혼재)을 보고, ");
        promptBuilder.append("해당 장소가 다음 5가지 카테고리 중 어디에 속하는지 추론해: [관광지, 식음, 쇼핑, 숙소, 교통].\n");
        promptBuilder.append("결과는 반드시 아래 예시처럼 장소 ID를 키(key)로, 카테고리를 값(value)으로 하는 순수 JSON 객체 하나로만 반환해. 마크다운 기호(```json)는 절대 넣지 마.\n");
        promptBuilder.append("예시: {\"ChIJ1234\": \"교통\", \"ChIJ5678\": \"식음\", \"ChIJ9012\": \"숙소\"}\n\n[분류 대상 목록]\n");

        for (Place p : places) {
            promptBuilder.append("- ID: ").append(p.getPlaceId()).append(" / 이름: ").append(p.getName()).append("\n");
        }

        Map<String, Object> requestBody = new HashMap<>();
        Map<String, Object> contents = new HashMap<>();
        Map<String, Object> parts = new HashMap<>();
        parts.put("text", promptBuilder.toString());
        contents.put("parts", Collections.singletonList(parts));
        requestBody.put("contents", Collections.singletonList(contents));

        String url = "https://generativelanguage.googleapis.com/v1beta/models/" + geminiModel + ":generateContent?key=" + geminiApiKey;

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        HttpEntity<Map<String, Object>> request = new HttpEntity<>(requestBody, headers);
        RestTemplate restTemplate = new RestTemplate();

        try {
            ResponseEntity<String> response = restTemplate.postForEntity(url, request, String.class);
            JsonNode rootNode = objectMapper.readTree(response.getBody());
            String textResponse = rootNode.path("candidates").get(0).path("content").path("parts").get(0).path("text").asText();

            // 마크다운 제거 방어 로직
            textResponse = textResponse.replace("```json", "").replace("```", "").trim();

            // JSON String을 Map으로 변환
            return objectMapper.readValue(textResponse, new TypeReference<Map<String, String>>(){});
        } catch (Exception e) {
            System.out.println("AI 카테고리 클렌징 실패: " + e.getMessage());
            return new HashMap<>();
        }
    }
}