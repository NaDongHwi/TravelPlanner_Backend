package com.travel.planner.service;

import com.travel.planner.entity.Place;
import com.travel.planner.repository.PlaceRepository;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 일정 생성 때 "AI 소개가 없는 장소만" 채우는 동작 테스트. 실제 AI 대신 정해 둔 답을 돌려주는 가짜를 쓴다. */
class PlaceDescriptionServiceTest {

    /** 받은 장소마다 "유형|소개"를 돌려주고, 무엇을 물었는지 기록한다. */
    static class FakeAi extends AiService {
        final List<String> asked = Collections.synchronizedList(new ArrayList<>());
        long delayMillis = 0;

        FakeAi() { super(null, null); }

        @Override
        public Map<String, String> describePlacesBulk(List<Place> places, Map<String, String> reviewsMap) {
            try { Thread.sleep(delayMillis); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            Map<String, String> out = new HashMap<>();
            for (Place p : places) {
                asked.add(p.getName());
                if (!p.getName().contains("응답누락")) out.put(p.getPlaceId(), "정원|" + p.getName() + " 소개 문장");
            }
            return out;
        }
    }

    @Test
    void onlyPlacesWithoutSummaryAreAskedAndTheAnswerIsSaved() throws Exception {
        FakeAi ai = new FakeAi();
        List<Place> saved = new ArrayList<>();
        PlaceDescriptionService service = service(ai, saved);

        Place garden = place(1L, "P1", "모미지야마 정원", "관광지");
        Place ramen = place(2L, "P2", "멘야 어딘가", "식음");
        ramen.setSubType("라멘");                                   // 이미 있는 세부 유형은 덮어쓰지 않는다
        Place known = place(3L, "P3", "이미 소개가 있는 곳", "관광지");
        known.setSummary("기존 소개");
        Place hotel = place(4L, "P4", "어딘가 호텔", "숙소");
        Place missing = place(5L, "P5", "응답누락 공원", "관광지");

        int filled = service.fillMissing(List.of(garden, ramen, known, hotel, missing, garden));

        assertEquals(2, filled);
        assertEquals("모미지야마 정원 소개 문장", garden.getSummary());
        assertEquals("정원", garden.getSubType());
        assertEquals("멘야 어딘가 소개 문장", ramen.getSummary());
        assertEquals("라멘", ramen.getSubType());
        assertEquals("기존 소개", known.getSummary());
        assertNull(hotel.getSummary(), "숙소는 소개를 만들지 않는다");
        assertNull(missing.getSummary(), "AI 가 답하지 않은 곳은 비워 둔다 (기본 문구가 대신 나간다)");
        assertEquals(List.of("모미지야마 정원", "멘야 어딘가", "응답누락 공원"), ai.asked, "소개가 없는 방문지만, 한 번씩만 묻는다");
        assertEquals(2, saved.size(), "채운 곳만 DB 에 저장한다");

        // 두 번째 일정: 이미 채워진 곳은 다시 묻지 않는다
        ai.asked.clear();
        assertEquals(0, service.fillMissing(List.of(garden, ramen, known)));
        assertTrue(ai.asked.isEmpty());
    }

    @Test
    void slowAiDoesNotHoldThePlanBeyondTheTimeLimit() throws Exception {
        FakeAi ai = new FakeAi();
        ai.delayMillis = 2500;
        PlaceDescriptionService service = service(ai, new ArrayList<>());
        set(service, "timeoutSeconds", 1);
        Place garden = place(1L, "P1", "느린 정원", "관광지");

        long started = System.currentTimeMillis();
        int filled = service.fillMissing(List.of(garden));
        long waited = System.currentTimeMillis() - started;

        assertEquals(0, filled);
        assertTrue(waited < 2200, "제한 시간(1초)만 기다려야 한다. 실제 " + waited + "ms");
        assertNull(garden.getSummary());

        // 늦게 온 답은 버리지 않고 반영한다 (다음 일정부터 쓰인다)
        for (int i = 0; i < 40 && garden.getSummary() == null; i++) Thread.sleep(100);
        assertEquals("느린 정원 소개 문장", garden.getSummary());
    }

    @Test
    void canBeTurnedOff() throws Exception {
        FakeAi ai = new FakeAi();
        PlaceDescriptionService service = service(ai, new ArrayList<>());
        set(service, "enabled", false);
        assertEquals(0, service.fillMissing(List.of(place(1L, "P1", "정원", "관광지"))));
        assertTrue(ai.asked.isEmpty());
    }

    // ---------------------------------------------------------------- 준비

    private static PlaceDescriptionService service(AiService ai, List<Place> saved) {
        PlaceRepository repository = (PlaceRepository) Proxy.newProxyInstance(PlaceDescriptionServiceTest.class.getClassLoader(),
                new Class<?>[]{PlaceRepository.class}, (proxy, method, args) -> {
                    if (method.getName().equals("save")) {
                        synchronized (saved) { saved.add((Place) args[0]); }
                        return args[0];
                    }
                    return null;
                });
        return new PlaceDescriptionService(ai, repository);
    }

    private static Place place(Long id, String placeId, String name, String category) {
        Place p = new Place();
        p.setId(id);
        p.setPlaceId(placeId);
        p.setName(name);
        p.setCategory(category);
        return p;
    }

    private static void set(Object target, String field, Object value) throws Exception {
        Field f = PlaceDescriptionService.class.getDeclaredField(field);
        f.setAccessible(true);
        f.set(target, value);
    }
}
