package com.travel.planner.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.travel.planner.dto.AiRouteResponse;
import com.travel.planner.entity.Log;
import com.travel.planner.entity.Place;
import com.travel.planner.repository.LogRepository;
import com.travel.planner.util.ThemeVocabulary;
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

    @Value("${ai.openai.model:gpt-4o-mini}") // 설정 파일에 ai.openai.model 이 있으면 그 값이 쓰인다
    private String openAiModel;

    // 먼저 부를 AI. openai(기본) 또는 gemini. 실패하면 다른 쪽으로 넘어간다.
    // Gemini 무료 티어는 분당 호출 한도(429)에 자주 걸려 배치가 멈추므로 유료인 OpenAI 를 주 모델로 쓴다.
    @Value("${ai.primary:openai}")
    private String primaryProvider;

    private static final int PRIMARY_ATTEMPTS = 2;   // 주 모델 시도 횟수 (그 뒤 보조 모델 1회)

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final LogRepository logRepository;
    private final RestTemplate restTemplate;

    // ============================================================================
    // 1. 알고리즘 기반 확정 동선 -> 스토리텔링 가이드 포맷팅 도구 (유지)
    // ============================================================================
    public AiRouteResponse generateStorytellingForValidatedRoute(
            List<PlanService.SimulatedItinerary> verifiedItineraries, String lang, int totalDays) {

        String targetLang = (lang != null && !lang.trim().isEmpty()) ? lang : "ko";
        StringBuilder rigidTimeline = new StringBuilder();
        int currentDay = 1;
        int count = 0;
        int placesPerDay = Math.max(1, verifiedItineraries.size() / Math.max(1, totalDays));

        for (PlanService.SimulatedItinerary iti : verifiedItineraries) {
            count++;
            if (count > placesPerDay && currentDay < totalDays) {
                currentDay++;
                count = 1;
            }
            rigidTimeline.append(String.format("Day %d - %s : %s\n", currentDay, iti.getTime(), iti.getDisplayName()));
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

        AiRouteResponse result = callAi(prompt, AiRouteResponse.class, "STORYTELLING");
        return result != null ? result : createEmergencyFallbackResponse(verifiedItineraries, totalDays);
    }

    // ============================================================================
    // 2. 관리자용 오프라인 전처리 (리뷰 기반 테마 분류)
    // ============================================================================
    public Map<String, String> classifyPlaceAttributesBulk(List<Place> places, Map<String, String> reviewsMap) {
        StringBuilder promptBuilder = new StringBuilder();
        promptBuilder.append("너는 여행 데이터 정제 전문가야. 아래 나열된 장소들의 이름과 구글 리뷰를 분석해서, 테마·장소 속성·평균 체류 시간·세부 유형·한 줄 소개를 한 번에 정리해.\n\n");
        promptBuilder.append("【 분류 절대 규칙 】\n");
        promptBuilder.append("1. 테마: 반드시 [").append(String.join(", ", ThemeVocabulary.THEMES)).append("] 이 12개 단어 안에서만 1~3개를 선택해. 절대 다른 단어를 창조하지 마!\n");
        promptBuilder.append("2. 장소 속성: 이 장소의 '메인 활동'이 이루어지는 곳을 기준으로 무조건 [실내] 또는 [실외] 중 하나만 고정해서 적어.\n");
        promptBuilder.append("3. 체류 시간: 일반 여행자가 머무는 평균 시간을 분 단위 정수로 적어. (식당 60, 카페 45, 박물관 90, 테마파크 480 처럼)\n");
        promptBuilder.append("4. 세부 유형: 이 장소가 무엇인지 2~10자의 한국어 명사로 적어. (식당은 음식 종류: 라멘, 스시, 장어, 이자카야 / 식사가 아닌 가게는 그대로: 젤라토, 디저트, 베이커리, 찻집 / 그 외: 신사, 공원, 전망대, 미술관, 쇼핑몰 처럼)\n");
        promptBuilder.append("5. 한 줄 소개: 처음 보는 여행자가 '무엇을 하는 곳인지' 알 수 있게 한국어 한 문장(15~60자)으로 적어. ")
                .append(DESCRIPTION_RULES).append("\n");
        promptBuilder.append("6. 결과는 반드시 장소 ID를 키로, '테마1,테마2|장소속성|체류시간|세부유형|한 줄 소개' 문자열을 값으로 하는 순수 JSON 객체 하나로만 반환해. 마크다운(```json) 금지. 값 안에 '|' 는 구분자로만 써.\n");
        promptBuilder.append("예시: {\"ChIJ1234\": \"쇼핑,서브컬쳐|실내|90|애니메이션 굿즈점|애니메이션·만화 굿즈를 층별로 파는 전문 매장\", ")
                .append("\"ChIJ5678\": \"온천,힐링|실외|120|온천|노천탕에서 바다를 보며 쉴 수 있는 당일 온천\"}\n\n[분류 대상 목록]\n");

        for (Place p : places) {
            String reviews = reviewsMap.get(p.getPlaceId());
            promptBuilder.append("- ID: ").append(p.getPlaceId())
                    .append(" / 이름: ").append(p.getName())
                    .append(" / 분류: ").append(p.getCategory() != null ? p.getCategory() : "미분류")
                    .append(" / 리뷰: ").append(reviews != null ? reviews : "리뷰 없음").append("\n");
        }

        String prompt = promptBuilder.toString();
        Map<String, String> result = callAi(prompt, new TypeReference<Map<String, String>>(){}, "ENRICHMENT");
        return result != null ? result : new HashMap<>();
    }

    // 소개 문구 공통 규칙. 모델이 모르는 장소를 그럴듯하게 꾸며 쓰지 않게 하는 것이 핵심이다.
    private static final String DESCRIPTION_RULES =
            "주어진 정보(이름·분류·테마·리뷰)와 네가 확실히 아는 사실만 써. 잘 모르는 곳이면 '○○을 파는 가게', '산책하기 좋은 공원'처럼 유형 수준으로만 적고, "
                    + "연도·가격·순위·수상 경력 같은 수치나 확인되지 않은 유래는 쓰지 마. '~입니다' 없이 명사형으로 끝내고, 장소 이름을 반복하지 마.";

    // ============================================================================
    // 2-1. 관리자용 오프라인 전처리 (세부 유형·한 줄 소개만 채우기)
    //      이미 테마가 분류된 기존 장소에 소개만 추가할 때 쓴다. 구글 호출 없이 DB 정보만 보낸다.
    //      reviewsMap 에 리뷰가 있으면(선택) 함께 보내 정확도를 높인다.
    // ============================================================================
    public Map<String, String> describePlacesBulk(List<Place> places, Map<String, String> reviewsMap) {
        StringBuilder promptBuilder = new StringBuilder();
        promptBuilder.append("너는 일본 여행 가이드북 편집자야. 아래 장소마다 '세부 유형'과 '한 줄 소개'를 한국어로 써.\n\n");
        promptBuilder.append("【 작성 규칙 】\n");
        promptBuilder.append("1. 세부 유형: 이 장소가 무엇인지 2~10자의 명사로 적어. (식당은 음식 종류: 라멘, 스시, 장어, 이자카야 / 식사가 아닌 가게는 그대로: 젤라토, 디저트, 베이커리, 찻집 / 그 외: 신사, 공원, 전망대, 미술관, 쇼핑몰 처럼)\n");
        promptBuilder.append("2. 한 줄 소개: 처음 보는 여행자가 '무엇을 하는 곳인지' 알 수 있게 한 문장(15~60자)으로 적어. ").append(DESCRIPTION_RULES).append("\n");
        promptBuilder.append("3. 결과는 반드시 장소 ID를 키로, '세부유형|한 줄 소개' 문자열을 값으로 하는 순수 JSON 객체 하나로만 반환해. 마크다운(```json) 금지. 값 안에 '|' 는 구분자로만 써.\n");
        promptBuilder.append("예시: {\"ChIJ1234\": \"라멘|진한 돈코츠 국물의 라멘을 내는 현지 인기 식당\", \"ChIJ5678\": \"일본식 정원|연못을 따라 산책로가 이어지는 일본식 정원\"}\n\n[대상 목록]\n");

        for (Place p : places) {
            promptBuilder.append("- ID: ").append(p.getPlaceId())
                    .append(" / 이름: ").append(p.getName())
                    .append(" / 도시: ").append(p.getCity() != null ? p.getCity() : "미상")
                    .append(" / 분류: ").append(p.getCategory() != null ? p.getCategory() : "미분류")
                    .append(" / 테마: ").append(p.getTheme() != null && !p.getTheme().isBlank() ? p.getTheme() : "미분류");
            if (p.getAddress() != null && !p.getAddress().isBlank()) promptBuilder.append(" / 주소: ").append(p.getAddress());
            String reviews = reviewsMap == null ? null : reviewsMap.get(p.getPlaceId());
            if (reviews != null && !reviews.isBlank()) promptBuilder.append(" / 리뷰: ").append(reviews);
            promptBuilder.append("\n");
        }

        String prompt = promptBuilder.toString();
        Map<String, String> result = callAi(prompt, new TypeReference<Map<String, String>>(){}, "DESCRIBE");
        return result != null ? result : new HashMap<>();
    }

    // ============================================================================
    // 3. 관리자용 오프라인 전처리 (5대 카테고리 정제)
    // ============================================================================
    public Map<String, String> cleansePlaceCategories(List<Place> places) {
        StringBuilder promptBuilder = new StringBuilder();
        promptBuilder.append("너는 여행 데이터 분류 전문가야. 다음 주어진 일본 장소들의 이름(한국/일어/영어 혼재)을 보고, ");
        promptBuilder.append("해당 장소가 다음 6가지 카테고리 중 어디에 속하는지 추론해: [관광지, 식음, 쇼핑, 숙소, 교통, 테마파크]. ");
        promptBuilder.append("유니버설 스튜디오·디즈니랜드 같은 놀이공원만 '테마파크'로 분류해.\n");
        promptBuilder.append("결과는 반드시 장소 ID를 키(key)로, 카테고리를 값(value)으로 하는 순수 JSON 객체 하나로만 반환해. 마크다운 기호(```json)는 절대 넣지 마.\n");
        promptBuilder.append("예시: {\"ChIJ1234\": \"교통\", \"ChIJ5678\": \"식음\", \"ChIJ9012\": \"숙소\"}\n\n[분류 대상 목록]\n");

        for (Place p : places) {
            promptBuilder.append("- ID: ").append(p.getPlaceId()).append(" / 이름: ").append(p.getName()).append("\n");
        }

        String prompt = promptBuilder.toString();
        Map<String, String> result = callAi(prompt, new TypeReference<Map<String, String>>(){}, "CLEANSING");
        return result != null ? result : new HashMap<>();
    }

    // ============================================================================
    // 유틸리티 메서드 (제미나이 파싱, 오픈AI 호출, 에러 로그)
    // ============================================================================
    // Gemini 호출 주소는 한 곳에서만 만든다.
    // (복사 과정에서 "[https://...](https://...)" 마크다운 링크가 문자열에 섞여 두 메서드가 항상 실패하고 있었다)
    private String geminiUrl() {
        return "https://generativelanguage.googleapis.com/v1beta/models/" + geminiModel + ":generateContent?key=" + geminiApiKey;
    }

    /** 주 모델이 OpenAI 인가 (배치 사이 대기 시간을 정할 때 쓴다) */
    public boolean isOpenAiPrimary() {
        return !"gemini".equalsIgnoreCase(primaryProvider == null ? "" : primaryProvider.trim());
    }

    /** 배치 한 묶음을 보낸 뒤 쉬는 시간. 유료 OpenAI 는 분당 한도가 넉넉해 짧게, 무료 Gemini 는 기존 값대로 길게 쉰다. */
    public long batchPauseMillis(long geminiPauseMillis) {
        return isOpenAiPrimary() ? Math.min(2000L, geminiPauseMillis) : geminiPauseMillis;
    }

    private boolean hasOpenAiKey() { return openAiApiKey != null && !openAiApiKey.isBlank(); }
    private boolean hasGeminiKey() { return geminiApiKey != null && !geminiApiKey.isBlank(); }

    /**
     * 모든 AI 호출이 지나는 한 곳.
     * 주 모델(ai.primary, 기본 openai)을 먼저 부르고, 계속 실패하면 다른 모델을 한 번 부른다. 둘 다 실패하면 null.
     * @param typeOrClass 결과 형식 (Class 또는 TypeReference)
     * @param tag         오류 로그 구분용 이름
     */
    private <T> T callAi(String prompt, Object typeOrClass, String tag) {
        String[] order = isOpenAiPrimary() ? new String[]{"openai", "gemini"} : new String[]{"gemini", "openai"};
        for (int i = 0; i < order.length; i++) {
            String provider = order[i];
            boolean available = "openai".equals(provider) ? hasOpenAiKey() : hasGeminiKey();
            if (!available) {
                if (i == 0) System.out.println("[AI] 주 모델(" + provider + ")의 API 키가 설정되지 않아 다른 모델로 호출합니다.");
                continue;
            }
            int attempts = i == 0 ? PRIMARY_ATTEMPTS : 1;
            for (int attempt = 1; attempt <= attempts; attempt++) {
                try {
                    T result = "openai".equals(provider) ? callOpenAi(prompt, typeOrClass) : callGemini(prompt, typeOrClass);
                    if (result != null) return result;
                } catch (org.springframework.web.client.HttpStatusCodeException e) {
                    // RestTemplate 이 감춘 실제 오류 본문(한도 초과·모델명 오류 등)을 남긴다
                    saveErrorLog("AI_" + tag + "_" + provider.toUpperCase() + "_FAIL_" + attempt,
                            "HTTP " + e.getStatusCode() + " | 상세: " + e.getResponseBodyAsString());
                } catch (Exception e) {
                    saveErrorLog("AI_" + tag + "_" + provider.toUpperCase() + "_FAIL_" + attempt, e.getMessage());
                }
                if (attempt < attempts) {
                    try { Thread.sleep(2000); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); return null; }
                }
            }
            if (i == 0) System.out.println("[AI] " + provider + " 호출 실패(" + tag + ") → 보조 모델로 전환합니다.");
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private <T> T readAs(String json, Object typeOrClass) throws Exception {
        String text = json.replace("```json", "").replace("```", "").trim();
        if (typeOrClass instanceof Class) return (T) objectMapper.readValue(text, (Class<?>) typeOrClass);
        return (T) objectMapper.readValue(text, (TypeReference<?>) typeOrClass);
    }

    private <T> T callGemini(String prompt, Object typeOrClass) throws Exception {
        Map<String, Object> requestBody = new HashMap<>();
        Map<String, Object> contents = new HashMap<>();
        Map<String, Object> parts = new HashMap<>();
        parts.put("text", prompt);
        contents.put("parts", Collections.singletonList(parts));
        requestBody.put("contents", Collections.singletonList(contents));

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<String> response = restTemplate.postForEntity(geminiUrl(), new HttpEntity<>(requestBody, headers), String.class);

        JsonNode rootNode = objectMapper.readTree(response.getBody());
        String aiText = rootNode.path("candidates").path(0).path("content").path("parts").path(0).path("text").asText("");
        if (aiText.isBlank()) throw new IllegalStateException("Gemini 응답에 본문이 없습니다.");
        return readAs(aiText, typeOrClass);
    }

    private <T> T callOpenAi(String prompt, Object typeOrClass) throws Exception {
        Map<String, Object> requestBody = new HashMap<>();
        requestBody.put("model", openAiModel);

        // JSON 모드: 응답이 항상 JSON 객체 하나로 온다 (프롬프트에 "JSON" 이라는 말이 있어야 한다 → 시스템 메시지에 넣어 둔다)
        Map<String, String> system = new HashMap<>();
        system.put("role", "system");
        system.put("content", "You are a careful data-processing assistant. Always answer with a single valid JSON object and nothing else.");
        Map<String, String> message = new HashMap<>();
        message.put("role", "user");
        message.put("content", prompt);
        requestBody.put("messages", Arrays.asList(system, message));

        Map<String, Object> responseFormat = new HashMap<>();
        responseFormat.put("type", "json_object");
        requestBody.put("response_format", responseFormat);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(openAiApiKey);
        ResponseEntity<String> response = restTemplate.postForEntity(
                "https://api.openai.com/v1/chat/completions", new HttpEntity<>(requestBody, headers), String.class);

        JsonNode rootNode = objectMapper.readTree(response.getBody());
        String gptText = rootNode.path("choices").path(0).path("message").path("content").asText("").trim();
        if (gptText.isBlank()) throw new IllegalStateException("OpenAI 응답에 본문이 없습니다.");

        // {"result": {...}} 처럼 한 겹 더 감싸서 주는 경우가 있다. 'ID → 문자열' 표를 기대할 때는 안쪽 객체를 꺼내 쓴다.
        if (typeOrClass instanceof TypeReference) {
            JsonNode root = objectMapper.readTree(gptText.replace("```json", "").replace("```", "").trim());
            if (root.isObject() && root.size() == 1 && root.elements().next().isObject()) {
                gptText = root.elements().next().toString();
            }
        }
        return readAs(gptText, typeOrClass);
    }

    /**
     * 설정 확인용: 두 모델을 아주 짧은 질문으로 한 번씩 불러 보고 결과를 글로 돌려준다 (키 값은 내보내지 않는다).
     * GET /api/test/ai 에서 쓴다.
     */
    public String selfTest() {
        StringBuilder out = new StringBuilder();
        out.append("주 모델: ").append(isOpenAiPrimary() ? "openai" : "gemini").append(" (설정 ai.primary)\n");
        String prompt = "다음 JSON 을 그대로 돌려줘. 다른 말은 쓰지 마. {\"ok\": \"yes\"}";
        String[][] providers = {{"openai", openAiModel}, {"gemini", geminiModel}};
        for (String[] provider : providers) {
            boolean openai = "openai".equals(provider[0]);
            out.append("- ").append(provider[0]).append(" [모델 ").append(provider[1]).append("]: ");
            if (!(openai ? hasOpenAiKey() : hasGeminiKey())) {
                out.append("API 키가 설정되지 않았습니다.\n");
                continue;
            }
            long started = System.currentTimeMillis();
            try {
                Map<String, String> answer = openai
                        ? callOpenAi(prompt, new TypeReference<Map<String, String>>(){})
                        : callGemini(prompt, new TypeReference<Map<String, String>>(){});
                out.append("응답 성공 ").append(answer).append(" (").append(System.currentTimeMillis() - started).append("ms)\n");
            } catch (org.springframework.web.client.HttpStatusCodeException e) {
                out.append("실패 HTTP ").append(e.getStatusCode()).append(" → ").append(maskKeys(e.getResponseBodyAsString())).append("\n");
            } catch (Exception e) {
                out.append("실패 → ").append(maskKeys(String.valueOf(e.getMessage()))).append("\n");
            }
        }
        return out.toString();
    }

    /** 오류 문구에 요청 주소가 그대로 실리는 경우가 있어 API 키는 가린다 */
    private static String maskKeys(String message) {
        if (message == null) return "";
        return message.replaceAll("key=[^&\\s\"]+", "key=***").replaceAll("sk-[A-Za-z0-9_\\-]{8,}", "sk-***");
    }

    private AiRouteResponse createEmergencyFallbackResponse(List<PlanService.SimulatedItinerary> routes, int totalDays) {
        // 기존 뼈대 유지
        AiRouteResponse failoverResponse = new AiRouteResponse();
        List<AiRouteResponse.TimelineItem> fallbackTimeline = new ArrayList<>();
        int currentDay = 1;
        int count = 0;
        int placesPerDay = Math.max(1, routes.size() / Math.max(1, totalDays));

        for (PlanService.SimulatedItinerary iti : routes) {
            count++;
            if (count > placesPerDay && currentDay < totalDays) {
                currentDay++;
                count = 1;
            }
            AiRouteResponse.TimelineItem item = new AiRouteResponse.TimelineItem();
            item.setDay(currentDay);
            item.setTime(iti.getTime());
            item.setPlaceName(iti.getDisplayName());
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
            String actualMessage = message != null ? message : "Unknown Error";
            actualMessage = maskKeys(actualMessage);   // API 키가 콘솔·로그 테이블에 남지 않도록 가린다

            // 인텔리제이 콘솔창에 빨간 글씨로 출력합니다.
            System.err.println("\n[AI 통신 장애 리포트]");
            System.err.println("▶ 발생 위치 (타입): " + errorType);
            System.err.println("▶ AI 서버 응답: " + actualMessage);
            System.err.println("=========================================\n");

            Log errorLog = new Log();
            errorLog.setErrorType(errorType);
            errorLog.setErrorMessage(actualMessage);
            logRepository.save(errorLog);
        } catch (Exception ignore) {}
    }

    // ============================================================================
    // 4. 실시간 동적 라우팅용 단건 속성/체류시간 추론
    // ============================================================================
    public void inferPlaceAttributesRealTime(Place place) {
        String prompt = "너는 여행 전문가야. 방금 사용자가 일정에 [" + place.getName() + "] 장소를 추가했어.\n" +
                "이 장소의 테마를 반드시 [맛집, 쇼핑, 관광, 힐링, 사진, 서브컬쳐, 문화, 자연, 야경, 온천, 액티비티, 카페] 이 12개 단어 안에서 1~2개 추론하고, \n" +
                "장소 속성(실내/실외 중 1개), 평균적으로 머무는 체류시간(분 단위 정수)을 함께 추론해줘.\n" +
                "결과는 반드시 아래 JSON 형식으로만 반환해. 마크다운 금지.\n" +
                "{\"theme\":\"관광,사진\", \"type\":\"실내\", \"duration\":90}";

        try {
            JsonNode resultNode = callAi(prompt, JsonNode.class, "REALTIME");
            if (resultNode == null) throw new IllegalStateException("AI 응답 없음");
            // 12개 어휘에 없는 값("기본 명소" 등)은 저장하지 않는다. 비어 있으면 엔진이 이름·분류로 추정한다.
            java.util.List<String> themes = ThemeVocabulary.normalizeList(resultNode.path("theme").asText(""));
            place.setTheme(themes.isEmpty() ? null : String.join(",", themes));
            String type = resultNode.path("type").asText("");
            place.setPlaceType(type.contains("실외") ? "실외" : "실내");
            if (place.getCategory() == null) place.setCategory("관광지");
            int duration = resultNode.path("duration").asInt(90);
            place.setRecommendedDuration(duration >= 15 && duration <= 600 ? duration : 90);

            System.out.println("[AI 실시간 추론 완료] " + place.getName() + " -> " + place.getRecommendedDuration() + "분 소요 예상");

        } catch (Exception e) {
            System.out.println("[AI 실시간 추론 실패 - 기본값 부여] " + e.getMessage());
            place.setTheme(null);
            place.setPlaceType(null);       // 비워 두면 관리자 인리치먼트 배치가 나중에 다시 채운다
            if (place.getCategory() == null) place.setCategory("관광지");
            place.setRecommendedDuration(90); // 실패 시 90분 기본 할당
        }
    }
}
