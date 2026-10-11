package com.travel.planner.service;

import com.travel.planner.entity.Place;
import com.travel.planner.repository.PlaceRepository;
import com.travel.planner.service.PlanService.SimulatedItinerary;
import com.travel.planner.service.PlanService.SimulatedItinerary.Type;
import com.travel.planner.util.PlaceDescriber;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.time.LocalDate;
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
        Place airport = place(6L, "P6", "간사이 국제공항", "교통");

        int filled = service.fillMissing(List.of(garden, ramen, known, hotel, missing, garden, airport, hotel));

        assertEquals(3, filled);
        assertEquals("모미지야마 정원 소개 문장", garden.getSummary());
        assertEquals("정원", garden.getSubType());
        assertEquals("멘야 어딘가 소개 문장", ramen.getSummary());
        assertEquals("라멘", ramen.getSubType());
        assertEquals("기존 소개", known.getSummary());
        assertEquals("어딘가 호텔 소개 문장", hotel.getSummary(), "그 여행의 숙소에도 소개를 붙인다");
        assertNull(airport.getSummary(), "공항·역은 소개를 만들지 않는다");
        assertNull(missing.getSummary(), "AI 가 답하지 않은 곳은 비워 둔다 (기본 문구가 대신 나간다)");
        assertEquals(List.of("모미지야마 정원", "멘야 어딘가", "어딘가 호텔", "응답누락 공원"), ai.asked, "소개가 없는 곳만, 한 번씩만 묻는다");
        assertEquals(3, saved.size(), "채운 곳만 DB 에 저장한다");

        // 두 번째 일정: 이미 채워진 곳은 다시 묻지 않는다 (같은 숙소에 다시 묵어도 AI 를 부르지 않는다)
        ai.asked.clear();
        assertEquals(0, service.fillMissing(List.of(garden, ramen, known, hotel)));
        assertTrue(ai.asked.isEmpty());
    }

    @Test
    void lodgingOfTheTripIsDescribedButAirportsAreNot() throws Exception {
        Place airport = place(1L, "A1", "간사이 국제공항", "교통");
        Place hotel = place(2L, "H1", "난바 어딘가 호텔", "숙소");
        Place garden = place(3L, "P1", "게이타쿠엔 정원", "관광지");
        Place castle = place(4L, "P2", "오사카 성", "관광지");

        // 1일차: 공항 → 정원 → 자유 식사 → 숙소 / 2일차: 숙소 → 성 → 숙소
        PlanService.DayPlan day1 = new PlanService.DayPlan(1, LocalDate.of(2026, 10, 17), "오사카");
        day1.getItems().add(new SimulatedItinerary(Type.START, airport, null, 10 * 60, null, 0, 34.43, 135.24));
        day1.getItems().add(new SimulatedItinerary(Type.VISIT, garden, null, 12 * 60, 13 * 60, 60, 34.65, 135.51));
        day1.getItems().add(new SimulatedItinerary(Type.MEAL, null, "자유 식사", 13 * 60, 14 * 60, 0, null, null));
        SimulatedItinerary arrive = new SimulatedItinerary(Type.END, hotel, null, 15 * 60, null, 20, 34.66, 135.50);
        day1.getItems().add(arrive);
        PlanService.DayPlan day2 = new PlanService.DayPlan(2, LocalDate.of(2026, 10, 18), "오사카");
        SimulatedItinerary leave = new SimulatedItinerary(Type.START, hotel, null, 9 * 60, null, 0, 34.66, 135.50);
        day2.getItems().add(leave);
        day2.getItems().add(new SimulatedItinerary(Type.VISIT, castle, null, 10 * 60, 12 * 60, 30, 34.68, 135.52));
        day2.getItems().add(new SimulatedItinerary(Type.END, hotel, null, 13 * 60, null, 30, 34.66, 135.50));

        List<Place> targets = PlanGenerationService.placesToDescribe(List.of(day1, day2));
        assertEquals(List.of(garden, hotel, hotel, castle, hotel), targets, "방문지와 숙소만, 공항과 자유 식사는 빼고");

        FakeAi ai = new FakeAi();
        List<Place> saved = new ArrayList<>();
        assertEquals(3, service(ai, saved).fillMissing(targets));
        assertEquals(List.of("게이타쿠엔 정원", "난바 어딘가 호텔", "오사카 성"), ai.asked, "같은 숙소는 한 번만 묻는다");

        // 화면: 줄의 설명은 "무엇을 하는 줄인지" 그대로이고, 숙소가 어떤 곳인지는 한 줄 소개에 담긴다
        assertEquals("숙소 출발", TimelineAssembler.describe(leave));
        assertEquals("숙소 도착 및 휴식", TimelineAssembler.describe(arrive));
        assertEquals("난바 어딘가 호텔 소개 문장", PlaceDescriber.summaryOf(hotel));
        assertEquals("정원", PlaceDescriber.subTypeOf(hotel), "AI 가 적은 숙소 종류가 세부 유형으로 남는다");
        assertNull(PlaceDescriber.summaryOf(airport));
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
