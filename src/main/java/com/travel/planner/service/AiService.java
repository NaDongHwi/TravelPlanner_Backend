package com.travel.planner.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.travel.planner.dto.AiRouteResponse;
import com.travel.planner.dto.RouteInfoDto;
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

    @Value("${ai.openai.model:gpt-5.6-terra}")
    private String openAiModel;

    private final ObjectMapper objectMapper = new ObjectMapper(); // JSON 변환 도구
    private final LogRepository logRepository; // DB 로그 저장소 연결
    private final RouteOptimizationService routeOptimizationService;

    public AiRouteResponse evaluateAndModifyRoute(String userContext, List<Place> draftRoute, String lang,
                                                  List<com.travel.planner.dto.PlanRequest.AccommodationInput> accommodations,
                                                  boolean suggestHotel, String hotelCandidates,
                                                  java.time.LocalDate startDate, int totalDays,
                                                  String inCity, String outCity) {

        // 기본 언어 방어 로직 (null 일 경우 한국어 기본값)
        String targetLang = (lang != null && !lang.trim().isEmpty()) ? lang : "ko";

        // 1. 임시 동선을 AI가 읽기 쉽게 글자로 변환
        String draftRouteStr = draftRoute.stream()
                .map(p -> p.getName() + "(ID:" + p.getPlaceId() + ")")
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
                "【 지능형 복합 최적화 및 비즈니스 제약 조건 】\n" +
                "1. [임시 동선 구역화] 임시 동선은 단순히 '거리'만 이은 초안이야. 같은 구역은 1시간 단위로 잘게 쪼개지 말고 하나의 흐름으로 자연스럽게 묶어내.\n" +
                "2. [선형 동선 보장] 이미 거쳐 간 지역으로 저녁에 다시 돌아가는 비효율적인 지그재그 동선을 절대 짜지 마. 일직선 흐름을 유지해.\n" +
                "3. [테마 중심 거점(Anchor) 설계 절대 규칙] 고객이 선택한 테마는 단순한 참고용이 아니라 이번 여행의 '가장 핵심적인 목적'이야. 따라서 해당 테마를 완벽히 대표하는 지역 내 최고 랜드마크(예: 서브컬쳐 테마라면 도쿄의 아키하바라/이케부쿠로, 오사카의 덴덴타운 등)를 무조건 일자별 메인 거점(Anchor)으로 최우선 배치해. 이 '테마 거점'을 하루 일정의 중심축으로 먼저 확정한 뒤, 반드시 그 거점과 물리적으로 가까운 주변 명소들을 묶어서 매끄러운 선형 동선을 완성해야 해.\n" +
                "4. [지능형 테마 밸런스 보장] 유저가 특정 테마를 선택했을 때, 글로벌 테마파크를 일정에 포함하는 것은 좋으나, 일본 고유의 로컬 성지가 타임라인에서 완전히 누락되는 일은 절대 없어야 해. 최소 반나절 이상은 로컬 테마 일정을 반드시 보장해.\n" +
                "5. [지능형 식음료(F&B) 배치 규칙] 사용자의 테마에 '맛집'이나 '식도락'이 포함되어 있다면, 현지 맛집, 야식, 카페 등을 하루 4~5번까지 적극적으로 배치해. 단, 아무리 식도락 테마라도 3시간 이내에 무거운 식사(식당)를 연속으로 2번 욱여넣는 일정은 금지야. 맛집 테마가 아니라면 하루 2번(점심, 저녁)의 식사와 1번의 카페 정도로 제한해.\n" +
                "6. [날씨/환경 가중치] 실시간 기상 정보에 '비', '눈' 등이 있다면 야외 명소 일정을 줄이고 실내 명소 비중을 높여.\n" +
                "7. [고정 일정 예외 처리] '고정일정(필수방문)'은 타임라인에 무조건 최우선으로 포함시켜.\n" +
                "8. [영업시간 절대 엄수 및 자율 추론] 전달된 오픈/마감 시간을 철저히 지키되, '영업시간 확인 필요'인 경우 방대한 내장 지식을 활용해 통상적인 영업시간(예: 신사는 일몰 전, 이자카야는 저녁 등)을 스스로 추론하여 안전한 시간대에 배치해.\n" +
                "9. [정확한 POI 출력 절대 규칙] 장소명(`placeName`)에는 광범위한 지역명이나 뭉뚱그린 일반명사를 절대 쓰지 마! 구글 지도에 마커(Pin)를 정확히 찍을 수 있는 '구체적인 상호명이나 랜드마크 고유명사(예: 요도바시 카메라 멀티미디어 아키바)'만을 반드시 출력해야 해. 괄호 '()'나 부연 설명도 금지야.\n" +
                "10. [지능형 동선 반경 및 근교 확장] 3일 이하의 짧은 일정이라면 숙소에서 너무 먼 '지리적 이상치'는 과감히 버려. 하지만 4박 이상의 장기 일정이라면 목적지 주변의 핵심 근교 소도시(예: 오사카->교토/나라, 후쿠오카->유후인 등)를 직접 찾아 무조건 일정에 융합시켜.\n" +
                "11. [자율적 동적 제안 및 데이터 극복] 제공한 [임시 동선] 데이터 풀에 절대 얽매이지 마! 특히 후반부 일정이 도심 쇼핑몰로만 획일화되는 것을 경계해. 사용자의 테마에 부합하지만 데이터에 빠져있는 '최고의 외곽 랜드마크'가 있다면 자유롭게 추가해.\n" +
                "12. 【 다국어 로컬라이징 및 번역 절대 규칙 】\n" +
                "    - 출력되는 모든 문장(`description`, `category`, `reason`)과 장소명은 지정된 언어 [" + targetLang + "] 로만 작성해.\n" +
                "    - 장소명(`placeName`)은 해당 언어 사용자가 구글 지도에서 검색하기 가장 친숙한 명칭으로 출력해.\n" +
                "13. [역할(Category) 절대 분리 규칙]\n" +
                "    - [교통] 카테고리(역, 공항)는 관광 일정으로 쓰지 말고 이동 동선의 기준점으로만 활용해. 장소명에 '입국', '출국' 같은 모호한 행동을 적지 말고 반드시 '나리타 국제공항' 등 정확한 고유명사로만 작성해.\n" +
                "    - [숙소] 카테고리(호텔, 료칸)는 일정표 중간에 끼워 넣지 말고, 무조건 체크인 / 체크아웃 목적지로만 배치해.\n" +
                "14. [계절 및 특별 이벤트 반영] 여행 시작일(" + startDate + ")과 총 기간(" + totalDays + "일)을 정밀 분석해 해당 시기와 지역에 맞는 계절적 명소나 전통 축제가 있다면 무조건 1개 이상 적극 추가해.\n" +
                "15. [지능형 숙소 다변화 및 역제안 규칙] 4박 이상 장기 여행일 경우 '도심 내 단일 숙소 체류'가 핵심 테마와 충돌하는지 비판적으로 판단해. 목적지에 맞는 근교 특화 지역을 도출하여 1~2박을 강제 배치하거나, `reason` 칸에 근교 지역 숙박을 강력히 추천한다고 작성해.\n" +
                "16. [지능형 쇼핑/실내 일정 분배 규칙] '쇼핑' 테마가 없다면 하루에 대형 쇼핑몰을 2회 이상 연속 중복 배치하는 것을 엄격히 금지해. 실내 일정이 필요하면 쇼핑몰, 박물관, 찻집 등으로 분산시켜.\n\n" +
                "【 시공간 환각(Spatiotemporal Hallucination) 방지 및 백엔드 동기화 절대 규칙 】\n" +
                "17. [입출국 공항/도시 절대 일치] 첫날 첫 일정은 반드시 입국 도시(" + inCity + ")의 공항 또는 역에서 시작하고, 마지막 날 최종 일정은 반드시 출국 도시(" + outCity + ")의 공항 또는 역에서 종료해. AI의 일반적인 추론보다 이 변수값이 무조건 최우선됨.\n" +
                "18. [카테고리별 허용 시간표 강제] 카테고리가 '자연' 또는 '전망'인 야외 명소는 일조권이 있는 오전~오후(09:00~17:00) 사이에만 배치해. 18:00 이후 일몰 시간대(저녁)에는 '숙소', '맛집', '야경', '실내 쇼핑몰' 카테고리만 허용됨.\n" +
                "19. [K-Means 권역 제한 및 유연성] 제공된 [임시 동선]은 백엔드에서 K-Means로 물리적 권역 분할이 끝난 상태야. 타 권역의 장소를 억지로 섞거나 권역을 파괴하는 행위를 차단함. 단, 11번과 15번 규칙에 따라 네가 새롭게 추가하는 '근교 랜드마크'나 '숙소'는 해당 일차의 동선 흐름에 가장 자연스러운 위치에 논리적으로 끼워 넣어.\n" +
                "20. [시간(Time) 환각 방지] 타임라인의 time 필드에는 '12:12', '10:13', '09:00' 같은 분/시간 단위 숫자를 절대 생성하지 마. 너는 입력받은 DB 체류 시간과 이동 시간을 내부적으로만 계산하여 순서(Sequence)를 배치하고, 출력은 오직 '오전', '오후', '저녁' 3가지 텍스트 중 하나만 입력해. 정확한 분 단위 타임라인 계산은 네 응답을 받은 후 백엔드 Java 로직이 덮어씌워 완성할 거야.\n\n" +
                hotelConstraints.toString() +
                "\n\n응답은 반드시 아래의 JSON 형식으로만 출력해. 마크다운 기호는 절대 넣지 마.\n" +
                "{\n" +
                "  \"timeline\": [\n" +
                "    { \"day\": 1, \"time\": \"오전\", \"placeId\": \"ChIJ... (전달받은 ID 그대로 출력)\", \"placeName\": \"간사이 국제공항\", \"category\": \"교통\", \"description\": \"입국 및 도심으로 이동\" }\n" +
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
                AiRouteResponse aiResponse = parseGeminiResponse(response.getBody());

                // [수정] 단순 호출이 아닌 '좌표 주입 후 최적화' 전용 통합 메서드로 교체
                if (draftRoute != null && !draftRoute.isEmpty()) {
                    injectCoordinatesAndOptimize(aiResponse, draftRoute);
                }

                return aiResponse;
            } catch (Exception e) {
                retryCount++;
                System.out.println("Gemini 호출 에러 (" + retryCount + "/3): " + e.getMessage());

                Log errorLog = new Log();
                errorLog.setErrorType("AI_GEMINI_FAIL_" + retryCount);
                errorLog.setErrorMessage(e.getMessage() != null ? e.getMessage() : "알 수 없는 Gemini 호출 에러");
                logRepository.save(errorLog);

                if (retryCount >= maxRetries) {
                    System.out.println("Gemini 최종 3회 실패! OpenAI 백업 모델로 즉각 전환합니다.");
                    AiRouteResponse fallbackResponse = callFallbackOpenAi(prompt, draftRoute);

                    // [수정] GPT 응답 처리 시 catch 블록 내 중복 호출 로직 삭제 (callFallbackOpenAi 내부에서 처리하도록 일원화)
                    return fallbackResponse;
                }

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

    // gpt 비상 전환 메서드
    private AiRouteResponse callFallbackOpenAi(String prompt, List<Place> draftRoute) {
        if (openAiApiKey == null || openAiApiKey.isEmpty()) {
            System.out.println("OpenAI API 키가 설정되지 않아 기본 알고리즘 경로를 반환합니다.");
            return createEmergencyFallbackResponse(draftRoute, "Gemini 장애 발생 및 GPT 백업 키 미설정");
        }

        try {
            String gptUrl = "https://api.openai.com/v1/chat/completions";

            // gpt-5.4 API 규격에 맞춘 JSON 바디 생성
            Map<String, Object> requestBody = new HashMap<>();
            requestBody.put("model", openAiModel);

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
            headers.setBearerAuth(openAiApiKey);

            HttpEntity<Map<String, Object>> request = new HttpEntity<>(requestBody, headers);
            RestTemplate restTemplate = new RestTemplate();

            ResponseEntity<String> response = restTemplate.postForEntity(gptUrl, request, String.class);
            JsonNode rootNode = objectMapper.readTree(response.getBody());

            // OpenAI 응답에서 실제 JSON 텍스트 추출
            String gptText = rootNode.path("choices").get(0).path("message").path("content").asText().trim();

            AiRouteResponse fallbackResponse = objectMapper.readValue(gptText, AiRouteResponse.class);
            fallbackResponse.setReason(String.format("[시스템 메시지] 메인 AI 서버 장애로 인해 %s 모델을 통해 비상 생성된 일정입니다. \n\n%s",
                    openAiModel, fallbackResponse.getReason()));

            if (draftRoute != null && !draftRoute.isEmpty()) {
                injectCoordinatesAndOptimize(fallbackResponse, draftRoute);
            }

            return fallbackResponse;

        } catch (Exception e) {
            System.out.println("백업 모델(" + openAiModel + ") 실패했습니다: " + e.getMessage());

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
    public Map<String, String> classifyPlaceAttributesBulk(List<Place> places, Map<String, String> reviewsMap) {
        StringBuilder promptBuilder = new StringBuilder();

        promptBuilder.append("너는 여행 데이터 정제 전문가야. 아래 나열된 여러 장소들의 이름과 구글 리뷰를 정밀하게 분석해서, 각각의 테마와 장소 속성을 한 번에 분류해.\n\n");
        promptBuilder.append("【 분류 절대 규칙 】\n");
        promptBuilder.append("1. 테마: [맛집, 쇼핑, 관광, 힐링, 사진, 서브컬쳐, 문화, 자연, 야경, 온천, 액티비티, 카페] 중 가장 적합한 1~3개를 선택.\n");
        promptBuilder.append("2. 장소 속성: 이 장소의 '메인 활동'이 이루어지는 곳을 기준으로 [실내] 또는 [실외] 중 무조건 하나만 강제로 선택해!\n");
        promptBuilder.append("   - 건물 안에서 쇼핑/식사를 한다면 무조건 [실내] (예: 애니메이트, 스시집, 백화점)\n");
        promptBuilder.append("   - 지붕이 없는 야외 공원, 길거리, 신사라면 무조건 [실외] (예: 센소지, 신주쿠 쿄엔, 하치코 동상)\n");
        promptBuilder.append("   - [복합]이라는 단어는 실내랑, 실외 판단이 완벽히 5:5로 갈리는 대형 테마파크(디즈니랜드 등)가 아니면 절대 쓰지 마.\n");
        promptBuilder.append("3. 평균 체류 시간: 해당 장소의 특성과 리뷰를 종합하여 일반적인 관광객이 머무는 시간을 '분(Minute)' 단위 숫자(예: 30, 60, 90, 120, 180, 240)로 도출해.\n");
        promptBuilder.append("4. 결과는 반드시 아래 예시처럼 장소 ID를 키(key)로, '테마|장소속성|체류시간' 문자열을 값(value)으로 하는 순수 JSON 객체 하나로만 반환해. 마크다운 기호(```json)는 절대 넣지 마.\n");
        promptBuilder.append("예시: {\"ChIJ1234\": \"쇼핑,서브컬쳐|실내|120\", \"ChIJ5678\": \"자연,힐링|실외|45\"}\n\n[분류 대상 목록]\n");

        for (Place p : places) {
            String reviews = reviewsMap.get(p.getPlaceId());

            promptBuilder.append("- ID: ").append(p.getPlaceId())
                    .append(" / 이름: ").append(p.getName())
                    .append(" / 리뷰: ").append(reviews != null ? reviews : "리뷰 없음").append("\n");
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

        int maxRetries = 3;
        int retryCount = 0;

        while (retryCount < maxRetries) {
            try {
                ResponseEntity<String> response = restTemplate.postForEntity(url, request, String.class);
                JsonNode rootNode = objectMapper.readTree(response.getBody());
                String textResponse = rootNode.path("candidates").get(0).path("content").path("parts").get(0).path("text").asText();

                textResponse = textResponse.replace("```json", "").replace("```", "").trim();
                return objectMapper.readValue(textResponse, new TypeReference<Map<String, String>>(){});

            } catch (Exception e) {
                retryCount++;
                System.out.println("Gemini 벌크 분류 에러 (" + retryCount + "/3): " + e.getMessage());

                if (retryCount >= maxRetries) {
                    System.out.println("Gemini 한도 초과! GPT 백업 모델로 즉각 전환합니다.");

                    // [로그 기록] Gemini 완전 실패 기록
                    Log enrichmentLog = new Log();
                    enrichmentLog.setErrorType("AI_ENRICHMENT_GEMINI_FAIL");
                    enrichmentLog.setErrorMessage("Gemini 한도초과로 GPT 전환: " + e.getMessage());
                    logRepository.save(enrichmentLog);

                    // GPT 대체 호출 후 그 결과를 그대로 반환
                    return callFallbackOpenAiForBulk(promptBuilder.toString());
                }

                // 429 에러 방어를 위해 재시도 전 1.5초 대기
                try { Thread.sleep(1500); }
                catch (InterruptedException ie) { Thread.currentThread().interrupt(); break; }
            }
        }

        return new HashMap<>(); // 여길 타게 되면 AdminAsyncService가 1분 휴식을 발동시킴
    }

    // 관리자 벌크 정제용 GPT 5.6 Terra 비상 전환 메서드
    private Map<String, String> callFallbackOpenAiForBulk(String prompt) {
        if (openAiApiKey == null || openAiApiKey.isEmpty()) {
            System.out.println("OpenAI API 키가 설정되지 않아 데이터 정제를 일시 중단합니다.");
            return new HashMap<>();
        }

        try {
            String gptUrl = "https://api.openai.com/v1/chat/completions";

            Map<String, Object> requestBody = new HashMap<>();
            requestBody.put("model", openAiModel);

            Map<String, String> message = new HashMap<>();
            message.put("role", "user");
            message.put("content", prompt);
            requestBody.put("messages", Collections.singletonList(message));

            // JSON 출력 강제 (프롬프트 무시하고 헛소리 방지)
            Map<String, Object> responseFormat = new HashMap<>();
            responseFormat.put("type", "json_object");
            requestBody.put("response_format", responseFormat);

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.setBearerAuth(openAiApiKey);

            HttpEntity<Map<String, Object>> request = new HttpEntity<>(requestBody, headers);
            RestTemplate restTemplate = new RestTemplate();

            ResponseEntity<String> response = restTemplate.postForEntity(gptUrl, request, String.class);
            JsonNode rootNode = objectMapper.readTree(response.getBody());

            String gptText = rootNode.path("choices").get(0).path("message").path("content").asText().trim();

            return objectMapper.readValue(gptText, new TypeReference<Map<String, String>>(){});

        } catch (Exception e) {
            System.out.println("백업 GPT 에러: " + e.getMessage());

            // [로그 기록] GPT마저 죽은 상황
            Log fatalLog = new Log();
            fatalLog.setErrorType("AI_ENRICHMENT_FATAL_GPT_FAIL");
            fatalLog.setErrorMessage(e.getMessage());
            logRepository.save(fatalLog);

            return new HashMap<>(); // 빈 맵 반환 -> AdminAsyncService가 1분 대기 모드 돌입
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

        // ▼ 3회 재시도 및 GPT 전환 로직 적용 ▼
        int maxRetries = 3;
        int retryCount = 0;

        while (retryCount < maxRetries) {
            try {
                ResponseEntity<String> response = restTemplate.postForEntity(url, request, String.class);
                JsonNode rootNode = objectMapper.readTree(response.getBody());
                String textResponse = rootNode.path("candidates").get(0).path("content").path("parts").get(0).path("text").asText();

                textResponse = textResponse.replace("```json", "").replace("```", "").trim();
                return objectMapper.readValue(textResponse, new TypeReference<Map<String, String>>(){});

            } catch (Exception e) {
                retryCount++;
                System.out.println("Gemini 카테고리 클렌징 에러 (" + retryCount + "/3): " + e.getMessage());

                if (retryCount >= maxRetries) {
                    System.out.println("Gemini 한도 초과! GPT 백업 모델로 즉각 전환합니다. (카테고리 클렌징)");

                    // [로그 기록] Gemini 완전 실패 기록
                    Log cleansingLog = new Log();
                    cleansingLog.setErrorType("AI_CLEANSING_GEMINI_FAIL");
                    cleansingLog.setErrorMessage("Gemini 한도초과로 GPT 전환: " + e.getMessage());
                    logRepository.save(cleansingLog);

                    // GPT 대체 호출 후 그 결과를 그대로 반환
                    return callFallbackOpenAiForCleansing(promptBuilder.toString());
                }

                // 429 에러 방어를 위해 재시도 전 1.5초 대기
                try { Thread.sleep(1500); }
                catch (InterruptedException ie) { Thread.currentThread().interrupt(); break; }
            }
        }

        return new HashMap<>(); // 여길 타게 되면 AdminAsyncService가 1분 휴식을 발동시킴
    }

    // 관리자 카테고리 클렌징 전용 GPT 비상 전환 메서드
    private Map<String, String> callFallbackOpenAiForCleansing(String prompt) {
        if (openAiApiKey == null || openAiApiKey.isEmpty()) {
            System.out.println("OpenAI API 키가 설정되지 않아 카테고리 클렌징을 일시 중단합니다.");
            return new HashMap<>();
        }

        try {
            String gptUrl = "https://api.openai.com/v1/chat/completions";

            Map<String, Object> requestBody = new HashMap<>();
            requestBody.put("model", openAiModel);

            Map<String, String> message = new HashMap<>();
            message.put("role", "user");
            message.put("content", prompt);
            requestBody.put("messages", Collections.singletonList(message));

            // JSON 출력 강제 (프롬프트 무시하고 헛소리 방지)
            Map<String, Object> responseFormat = new HashMap<>();
            responseFormat.put("type", "json_object");
            requestBody.put("response_format", responseFormat);

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.setBearerAuth(openAiApiKey);

            HttpEntity<Map<String, Object>> request = new HttpEntity<>(requestBody, headers);
            RestTemplate restTemplate = new RestTemplate();

            ResponseEntity<String> response = restTemplate.postForEntity(gptUrl, request, String.class);
            JsonNode rootNode = objectMapper.readTree(response.getBody());

            String gptText = rootNode.path("choices").get(0).path("message").path("content").asText().trim();

            return objectMapper.readValue(gptText, new TypeReference<Map<String, String>>(){});

        } catch (Exception e) {
            System.out.println("백업 GPT 에러 (카테고리 클렌징): " + e.getMessage());

            // [로그 기록] GPT마저 죽은 상황
            Log fatalLog = new Log();
            fatalLog.setErrorType("AI_CLEANSING_FATAL_GPT_FAIL");
            fatalLog.setErrorMessage(e.getMessage());
            logRepository.save(fatalLog);

            return new HashMap<>(); // 빈 맵 반환 -> AdminAsyncService가 1분 대기 모드 돌입
        }
    }

    private void injectCoordinatesAndOptimize(AiRouteResponse responseDto, List<Place> draftRoute) {
        if (responseDto.getTimeline() == null || responseDto.getTimeline().isEmpty()) return;

        // 1. placeId를 기준으로 원본 Place 객체들을 빠르게 찾을 수 있도록 Map 구성
        Map<String, Place> placeMap = draftRoute.stream()
                .filter(p -> p.getPlaceId() != null)
                .collect(Collectors.toMap(Place::getPlaceId, p -> p, (p1, p2) -> p1));

        // 2. AI가 생성한 타임라인을 순회하며 원본 좌표(위도/경도) 주입
        for (AiRouteResponse.TimelineItem item : responseDto.getTimeline()) {
            if (item.getPlaceId() != null && placeMap.containsKey(item.getPlaceId())) {
                Place originalPlace = placeMap.get(item.getPlaceId());
                item.setLatitude(originalPlace.getLatitude());
                item.setLongitude(originalPlace.getLongitude());
            }
        }

        // 3. 좌표가 주입된 후 Navitime 교통 최적화 호출
        AiRouteResponse.TimelineItem firstItem = responseDto.getTimeline().get(0);
        AiRouteResponse.TimelineItem lastItem = responseDto.getTimeline().get(responseDto.getTimeline().size() - 1);

        // 이제 좌표가 null이 아니므로 정상적으로 최적화 통신이 수행됨
        if (firstItem.getLatitude() != null && lastItem.getLatitude() != null) {
            RouteInfoDto routeInfo = routeOptimizationService.getOptimizedRoute(
                    firstItem.getLatitude(), firstItem.getLongitude(),
                    lastItem.getLatitude(), lastItem.getLongitude(),
                    draftRoute.get(0).getCity()
            );
            responseDto.setTransportOptimization(routeInfo);
        }
    }
}