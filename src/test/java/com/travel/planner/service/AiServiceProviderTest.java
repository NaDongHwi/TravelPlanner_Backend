package com.travel.planner.service;

import com.travel.planner.entity.Place;
import com.travel.planner.repository.LogRepository;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestTemplate;

import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 주 AI(OpenAI)와 보조 AI(Gemini)를 부르는 순서 테스트.
 * 실제 통신 대신, 어느 주소로 몇 번 불렸는지 기록하는 가짜 RestTemplate 을 쓴다.
 */
class AiServiceProviderTest {

    private static final String OPENAI_OK = "{\"choices\":[{\"message\":{\"content\":\"{\\\"P1\\\":\\\"식음\\\"}\"}}]}";
    private static final String OPENAI_WRAPPED = "{\"choices\":[{\"message\":{\"content\":\"{\\\"result\\\":{\\\"P1\\\":\\\"식음\\\"}}\"}}]}";
    private static final String GEMINI_OK = "{\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"```json\\n{\\\"P1\\\":\\\"관광지\\\"}\\n```\"}]}}]}";

    /** 주소별로 정해 둔 응답을 돌려주고 호출 순서를 기록한다. 응답이 null 이면 통신 오류를 던진다. */
    static class FakeRestTemplate extends RestTemplate {
        final List<String> calls = new ArrayList<>();
        String openAiBody = OPENAI_OK;
        String geminiBody = GEMINI_OK;

        @Override
        @SuppressWarnings("unchecked")
        public <T> ResponseEntity<T> postForEntity(String url, Object request, Class<T> type, Object... vars) {
            boolean openai = url.contains("api.openai.com");
            calls.add(openai ? "openai" : "gemini");
            String body = openai ? openAiBody : geminiBody;
            if (body == null) throw new IllegalStateException("429 Too Many Requests");
            return (ResponseEntity<T>) ResponseEntity.ok(body);
        }
    }

    @Test
    void openAiIsCalledFirstAndGeminiIsNotTouchedWhenItAnswers() throws Exception {
        FakeRestTemplate http = new FakeRestTemplate();
        Map<String, String> result = service(http, "openai", "sk-test", "g-test").cleansePlaceCategories(places());
        assertEquals("식음", result.get("P1"));
        assertEquals(List.of("openai"), http.calls);
    }

    @Test
    void geminiTakesOverWhenOpenAiKeepsFailing() throws Exception {
        FakeRestTemplate http = new FakeRestTemplate();
        http.openAiBody = null;
        Map<String, String> result = service(http, "openai", "sk-test", "g-test").cleansePlaceCategories(places());
        assertEquals("관광지", result.get("P1"), "보조 모델(Gemini)의 답을 쓴다");
        assertEquals(List.of("openai", "openai", "gemini"), http.calls, "주 모델 2회 시도 뒤 보조 모델 1회");
    }

    @Test
    void emptyResultWhenBothFail() throws Exception {
        FakeRestTemplate http = new FakeRestTemplate();
        http.openAiBody = null;
        http.geminiBody = null;
        assertTrue(service(http, "openai", "sk-test", "g-test").cleansePlaceCategories(places()).isEmpty());
        assertEquals(List.of("openai", "openai", "gemini"), http.calls);
    }

    @Test
    void primaryCanBeSwitchedBackToGemini() throws Exception {
        FakeRestTemplate http = new FakeRestTemplate();
        AiService gemini = service(http, "gemini", "sk-test", "g-test");
        assertFalse(gemini.isOpenAiPrimary());
        assertEquals("관광지", gemini.cleansePlaceCategories(places()).get("P1"));
        assertEquals(List.of("gemini"), http.calls);
        assertEquals(30000L, gemini.batchPauseMillis(30000L), "Gemini 가 주 모델이면 기존 대기 시간을 그대로 쓴다");
        assertEquals(2000L, service(http, "openai", "sk-test", "g-test").batchPauseMillis(30000L));
    }

    @Test
    void missingOpenAiKeyFallsThroughToGemini() throws Exception {
        FakeRestTemplate http = new FakeRestTemplate();
        assertEquals("관광지", service(http, "openai", "", "g-test").cleansePlaceCategories(places()).get("P1"));
        assertEquals(List.of("gemini"), http.calls, "키가 없는 모델은 부르지 않는다");
    }

    @Test
    void answerWrappedInOneExtraObjectIsUnwrapped() throws Exception {
        FakeRestTemplate http = new FakeRestTemplate();
        http.openAiBody = OPENAI_WRAPPED;
        assertEquals("식음", service(http, "openai", "sk-test", "g-test").cleansePlaceCategories(places()).get("P1"));
        assertEquals(List.of("openai"), http.calls);
    }

    // ---------------------------------------------------------------- 준비

    private static List<Place> places() {
        Place p = new Place();
        p.setPlaceId("P1");
        p.setName("테스트 식당");
        return List.of(p);
    }

    private static AiService service(RestTemplate http, String primary, String openAiKey, String geminiKey) throws Exception {
        // 오류 로그 저장은 무시하는 가짜 저장소
        LogRepository logs = (LogRepository) Proxy.newProxyInstance(AiServiceProviderTest.class.getClassLoader(),
                new Class<?>[]{LogRepository.class}, (proxy, method, args) -> null);
        AiService service = new AiService(logs, http);
        set(service, "primaryProvider", primary);
        set(service, "openAiApiKey", openAiKey);
        set(service, "openAiModel", "test-model");
        set(service, "geminiApiKey", geminiKey);
        set(service, "geminiModel", "test-model");
        return service;
    }

    private static void set(Object target, String field, Object value) throws Exception {
        Field f = AiService.class.getDeclaredField(field);
        f.setAccessible(true);
        f.set(target, value);
    }
}
