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
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor // 스프링이 LogRepository를 자동으로 연결해줍니다.
public class AiService {

    @Value("${ai.gemini.api-key}")
    private String geminiApiKey;

    @Value("${ai.gemini.model}") // yml 파일에서 모델 이름을 동적으로 읽어옵니다.
    private String geminiModel;

    // OpenAI(gpt-5.4) 비상 전환용 API 키 (application.properties에 추가 필요)
    @Value("${ai.openai.api-key:}")
    private String openAiApiKey;

    private final ObjectMapper objectMapper = new ObjectMapper(); // JSON 변환 도구
    private final LogRepository logRepository; // DB 로그 저장소 연결

    public AiRouteResponse evaluateAndModifyRoute(String userContext, List<Place> draftRoute, String lang,
                                                  List<com.travel.planner.dto.PlanRequest.AccommodationInput> accommodations,
                                                  boolean suggestHotel, String hotelCandidates,
                                                  java.time.LocalDate startDate, int totalDays) {

        // 기본 언어 방어 로직 (null 일 경우 한국어 기본값)
        String targetLang = (lang != null && !lang.trim().isEmpty()) ? lang : "ko";

        // 1. 임시 동선을 AI가 읽기 쉽게 글자로 변환
        String draftRouteStr = draftRoute.stream()
                .map(Place::getName)
                .collect(Collectors.joining(" -> "));

        // 숙소 앵커링 및 역제안 텍스트 생성기
        StringBuilder hotelConstraints = new StringBuilder();
        hotelConstraints.append("\n\n[일자별 숙소 출발/도착 절대 규칙]\n");

        if (accommodations != null && !accommodations.isEmpty()) {
            hotelConstraints.append("사용자가 이미 예약한 숙소가 있습니다. 타임라인 생성 시 아래의 일자별 시작/종료 숙소 규칙을 100% 무조건 지키세요.\n");

            for (int i = 0; i < totalDays; i++) {
                java.time.LocalDate currentDate = startDate.plusDays(i);
                String startHotel = null;
                String endHotel = null;

                for (com.travel.planner.dto.PlanRequest.AccommodationInput acc : accommodations) {
                    if (acc.getCheckOut().isEqual(currentDate)) {
                        startHotel = acc.getName();
                    }
                    if (acc.getCheckIn().isEqual(currentDate)) {
                        endHotel = acc.getName();
                    }
                    if (currentDate.isAfter(acc.getCheckIn()) && currentDate.isBefore(acc.getCheckOut())) {
                        startHotel = acc.getName();
                        endHotel = acc.getName();
                    }
                }

                int dayNum = i + 1;
                hotelConstraints.append("- ").append(dayNum).append("일차(").append(currentDate).append("): ");
                if (startHotel != null && endHotel != null) {
                    hotelConstraints.append("일정의 시작은 [").append(startHotel).append("](체크아웃/출발), 끝은 [").append(endHotel).append("](체크인/도착) 이어야 함.\n");
                } else if (startHotel != null) {
                    hotelConstraints.append("일정의 시작은 [").append(startHotel).append("]에서 출발해야 함.\n");
                } else if (endHotel != null) {
                    hotelConstraints.append("일정의 마지막은 무조건 [").append(endHotel).append("] 체크인으로 끝나야 함.\n");
                } else {
                    hotelConstraints.append("지정된 숙소 없음.\n");
                }
            }
        } else if (suggestHotel) {
            hotelConstraints.append("사용자가 숙소를 예약하지 않아, AI가 직접 숙소를 추천해야 합니다.\n");
            hotelConstraints.append("아래 [추천 숙소 후보군] 중 동선 상 가장 효율적인 곳을 1~2개 선택하여 타임라인의 시작과 끝점(체크인/체크아웃)으로 배치하세요.\n");
            hotelConstraints.append("[추천 숙소 후보군]: ").append(hotelCandidates).append("\n");
        } else {
            hotelConstraints.append("사용자가 숙소 예약을 원하지 않습니다. 시작점과 끝점에 숙소를 포함하지 말고 오직 명소 위주로만 동선을 구성하세요.\n");
        }

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
                hotelConstraints.toString() +
                "\n\n응답은 반드시 아래의 JSON 형식으로만 출력해. 마크다운 기호는 절대 넣지 마.\n" +
                "{\n" +
                "  \"timeline\": [\n" +
                "    { \"day\": 1, \"time\": \"09:00\", \"placeName\": \"장소명1\", \"category\": \"관광/맛집/문화 등\", \"description\": \"이 장소에 대한 설명\" }\n" +
                "  ],\n" +
                "  \"reason\": \"최종 결과물 완성 상세 설명\"\n" +
                "}";

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

        // 제미나이 서버 3회 요청 및 gpt-5.4 비상 전환(Failover) 로직
        int maxRetries = 3;
        int retryCount = 0;

        while (retryCount < maxRetries) {
            try {
                ResponseEntity<String> response = restTemplate.postForEntity(url, request, String.class);
                return parseGeminiResponse(response.getBody());
            } catch (Exception e) {
                retryCount++;
                System.out.println("Gemini 호출 에러 (" + retryCount + "/3): " + e.getMessage());

                // [로그 기록] Gemini API 호출 실패 시 DB 적재
                Log errorLog = new Log();
                errorLog.setErrorType("AI_GEMINI_FAIL_" + retryCount);
                errorLog.setErrorMessage(e.getMessage() != null ? e.getMessage() : "알 수 없는 Gemini 호출 에러");
                logRepository.save(errorLog);

                if (retryCount >= maxRetries) {
                    System.out.println("Gemini 최종 3회 실패! [OpenAI gpt-5.4] 백업 모델로 즉각 전환합니다.");
                    return callFallbackGpt4o(prompt, draftRoute); // Failover 발동!
                }

                // 재시도 전 1초 대기 (API Rate Limit 방어)
                try { Thread.sleep(1000); }
                catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
            }
        }

        return createEmergencyFallbackResponse(draftRoute, "예기치 않은 오류가 발생했습니다.");
    }

    // 제미나이가 뱉은 복잡한 JSON에서 원하는 값만 빼내는 파싱 도구
    private AiRouteResponse parseGeminiResponse(String responseBody) throws Exception {
        JsonNode rootNode = objectMapper.readTree(responseBody);
        String aiText = rootNode.path("candidates").get(0).path("content").path("parts").get(0).path("text").asText();
        aiText = aiText.replace("```json", "").replace("```", "").trim();
        return objectMapper.readValue(aiText, AiRouteResponse.class);
    }

    // gpt-5.4 비상 전환 메서드
    private AiRouteResponse callFallbackGpt4o(String prompt, List<Place> draftRoute) {
        if (openAiApiKey == null || openAiApiKey.isEmpty()) {
            System.out.println("OpenAI API 키가 설정되지 않아 기본 알고리즘 경로를 반환합니다.");
            return createEmergencyFallbackResponse(draftRoute, "Gemini 장애 발생 및 GPT 백업 키 미설정");
        }

        try {
            String gptUrl = "https://api.openai.com/v1/chat/completions";

            // gpt-5.4 API 규격에 맞춘 JSON 바디 생성
            Map<String, Object> requestBody = new HashMap<>();
            requestBody.put("model", "gpt-5.4");

            Map<String, String> message = new HashMap<>();
            message.put("role", "user");
            message.put("content", prompt);
            requestBody.put("messages", Collections.singletonList(message));

            // JSON 출력 강제 설정
            Map<String, Object> responseFormat = new HashMap<>();
            responseFormat.put("type", "json_object");
            requestBody.put("response_format", responseFormat);

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.setBearerAuth(openAiApiKey); // OpenAI는 Bearer 토큰 방식 사용

            HttpEntity<Map<String, Object>> request = new HttpEntity<>(requestBody, headers);
            RestTemplate restTemplate = new RestTemplate();

            ResponseEntity<String> response = restTemplate.postForEntity(gptUrl, request, String.class);
            JsonNode rootNode = objectMapper.readTree(response.getBody());

            // OpenAI 응답에서 실제 JSON 텍스트 추출
            String gptText = rootNode.path("choices").get(0).path("message").path("content").asText().trim();

            AiRouteResponse fallbackResponse = objectMapper.readValue(gptText, AiRouteResponse.class);
            fallbackResponse.setReason("[시스템 메시지] 메인 AI 서버 장애로 인해 gpt-5.4 모델을 통해 비상 생성된 일정입니다. \n\n" + fallbackResponse.getReason());

            return fallbackResponse;

        } catch (Exception e) {
            System.out.println("백업 모델 gpt-5.4 실패했습니다: " + e.getMessage());

            // [로그 기록] 백업 GPT마저 실패하는 최악의 사태 시 DB 적재
            Log fatalLog = new Log();
            fatalLog.setErrorType("AI_FATAL_GPT_FAIL");
            fatalLog.setErrorMessage(e.getMessage() != null ? e.getMessage() : "알 수 없는 GPT 호출 에러");
            logRepository.save(fatalLog);

            return createEmergencyFallbackResponse(draftRoute, "메인 및 백업 AI 모델 동시 장애 (" + e.getMessage() + ")");
        }
    }

    // Gemini, GPT 모두 죽었을 때 최후의 보루 (하드코딩 동선)
    private AiRouteResponse createEmergencyFallbackResponse(List<Place> draftRoute, String errorMessage) {
        AiRouteResponse failoverResponse = new AiRouteResponse();
        List<AiRouteResponse.TimelineItem> fallbackTimeline = new ArrayList<>();

        for (int i = 0; i < draftRoute.size(); i++) {
            AiRouteResponse.TimelineItem item = new AiRouteResponse.TimelineItem();
            item.setDay(1);
            item.setTime("시간 미정");
            item.setPlaceName(draftRoute.get(i).getName());
            item.setCategory("분류 미정");
            item.setDescription("AI 연동 지연으로 인한 기본 알고리즘 경로입니다.");
            fallbackTimeline.add(item);
        }

        failoverResponse.setTimeline(fallbackTimeline);
        failoverResponse.setReason("AI 시스템 장애로 기본 알고리즘 최적화 동선이 적용되었습니다. (" + errorMessage + ")");
        return failoverResponse;
    }

    // 오프라인 전처리 전용: 리뷰를 기반으로 장소의 테마 카테고리 자동 분류
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

            // [로그 기록] 관리자 테마 인리치먼트 백그라운드 작업 중 에러 시 DB 적재
            Log enrichmentLog = new Log();
            enrichmentLog.setErrorType("AI_ENRICHMENT_FAIL");
            enrichmentLog.setErrorMessage(e.getMessage() != null ? e.getMessage() : "테마 분류 AI 에러");
            logRepository.save(enrichmentLog);

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

            textResponse = textResponse.replace("```json", "").replace("```", "").trim();

            return objectMapper.readValue(textResponse, new TypeReference<Map<String, String>>(){});
        } catch (Exception e) {
            System.out.println("AI 카테고리 클렌징 실패: " + e.getMessage());

            // [로그 기록] 관리자 카테고리 분류 백그라운드 작업 중 에러 시 DB 적재
            Log cleansingLog = new Log();
            cleansingLog.setErrorType("AI_CLEANSING_FAIL");
            cleansingLog.setErrorMessage(e.getMessage() != null ? e.getMessage() : "카테고리 정제 AI 에러");
            logRepository.save(cleansingLog);

            return new HashMap<>();
        }
    }
}