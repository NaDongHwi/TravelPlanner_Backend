package com.travel.planner.service;

import com.travel.planner.dto.AiRouteResponse;
import com.travel.planner.entity.Itinerary;
import com.travel.planner.entity.Place;
import com.travel.planner.repository.TransitFeedRepository;
import com.travel.planner.repository.TransitLinkRepository;
import com.travel.planner.repository.TransitNameRepository;
import com.travel.planner.repository.TransitPatternRepository;
import com.travel.planner.repository.TransitStationRepository;
import com.travel.planner.repository.TransitStopRepository;
import com.travel.planner.service.PlanService.SimulatedItinerary;
import com.travel.planner.service.PlanService.SimulatedItinerary.Type;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 한국어로 옮긴 이름이 "보여 주는 곳"에는 모두 쓰이고, 원래 이름은 그대로 남는지 확인한다.
 * (일정 줄, 구간 안내, 일자별 개요, 여행 요약, PDF 의 장소 칸)
 */
class PlaceNameDisplayTest {

    private static final LocalDate DATE = LocalDate.of(2026, 10, 17);

    private final Place hotel = place("H1", "Hotel Associa Shizuoka", "호텔 아소시아 시즈오카", "숙소", 34.9716, 138.3890);
    private final Place park = place("P1", "駿府城公園", "슨푸성 공원", "관광지", 34.9790, 138.3830);
    private final Place shrine = place("P2", "静岡浅間神社", null, "관광지", 34.9840, 138.3760);          // 아직 옮기지 못한 곳
    private final Place garden = place("P3", "도키와 공원", null, "관광지", 34.9700, 138.3800);            // 원래 한국어인 곳

    @Test
    void timelineShowsTheKoreanNameAndKeepsTheOriginalBesideIt() {
        List<AiRouteResponse.TimelineItem> timeline = assembler().toTimeline(1, DATE, day(), "ko", "대중교통");

        assertEquals("호텔 아소시아 시즈오카", timeline.get(0).getPlaceName());
        assertEquals("Hotel Associa Shizuoka", timeline.get(0).getPlaceNameOriginal());
        assertEquals("슨푸성 공원", timeline.get(1).getPlaceName());
        assertEquals("駿府城公園", timeline.get(1).getPlaceNameOriginal());
        assertEquals("静岡浅間神社", timeline.get(2).getPlaceName(), "옮긴 이름이 없으면 원래 이름을 보여 준다");
        assertNull(timeline.get(2).getPlaceNameOriginal());
        assertEquals("도키와 공원", timeline.get(3).getPlaceName());
        assertNull(timeline.get(3).getPlaceNameOriginal());
        assertEquals("자유 시간", timeline.get(4).getPlaceName());
        assertNull(timeline.get(4).getPlaceNameOriginal());

        // 구간 안내(직전 장소 → 이 장소)의 출발·도착 이름에도 옮긴 이름이 쓰인다
        StringBuilder ends = new StringBuilder();
        for (AiRouteResponse.TimelineItem item : timeline) {
            if (item.getTransit() == null) continue;
            ends.append(item.getTransit().getSummary()).append(' ');
            for (com.travel.planner.dto.TransitLegDto.Step step : item.getTransit().getSteps()) {
                ends.append(step.getFrom()).append(" → ").append(step.getTo()).append(" | ");
            }
        }
        assertTrue(ends.toString().contains("호텔 아소시아 시즈오카 → 슨푸성 공원"), ends.toString());
        assertFalse(ends.toString().contains("駿府城公園"), ends.toString());
        assertFalse(ends.toString().contains("Hotel Associa"), ends.toString());

        // 장소 식별자와 DB 의 원래 이름은 그대로다
        assertEquals("P1", timeline.get(1).getPlaceId());
        assertEquals("駿府城公園", park.getName());
    }

    @Test
    void otherLanguagesKeepTheOriginalName() {
        // 한국어가 아닌 화면에는 한국어로 옮긴 이름을 내보내지 않는다
        GoogleMapsService noLookup = new GoogleMapsService(null) {
            @Override
            public Place getPlaceDetailsById(String placeId, String lang) { return null; }
        };
        TimelineAssembler english = new TimelineAssembler(noLookup, null, routes());
        List<AiRouteResponse.TimelineItem> timeline = english.toTimeline(1, DATE, day(), "en", "대중교통");
        assertEquals("Hotel Associa Shizuoka", timeline.get(0).getPlaceName());
        assertNull(timeline.get(0).getPlaceNameOriginal());
        assertEquals("駿府城公園", timeline.get(1).getPlaceName());
        assertNull(timeline.get(1).getPlaceNameOriginal());
    }

    @Test
    void itineraryRowKnowsBothNames() {
        SimulatedItinerary visit = new SimulatedItinerary(Type.VISIT, park, null, 10 * 60, 11 * 60, 20, park.getLatitude(), park.getLongitude());
        assertEquals("슨푸성 공원", visit.getDisplayName());
        assertEquals("駿府城公園", visit.getOriginalName());
        SimulatedItinerary free = new SimulatedItinerary(Type.FREE, null, "자유 시간", 9 * 60, 10 * 60, 0, null, null);
        assertEquals("자유 시간", free.getDisplayName());
        assertEquals("자유 시간", free.getOriginalName());
    }

    @Test
    void summariesUseTheKoreanName() {
        PlanService.DayPlan plan = new PlanService.DayPlan(1, DATE, "시즈오카");
        plan.getItems().addAll(day());
        AiRouteResponse.DaySummary summary = PlanSummarizer.summarizeDays(List.of(plan)).get(0);

        assertTrue(summary.getHighlights().contains("슨푸성 공원"), summary.getHighlights().toString());
        assertFalse(summary.getHighlights().contains("駿府城公園"));
        assertTrue(summary.getSummary().contains("슨푸성 공원"), summary.getSummary());
        assertFalse(summary.getSummary().contains("駿府城公園"), summary.getSummary());
    }

    @Test
    void pdfRowsUseTheKoreanName() {
        Itinerary visit = new Itinerary();
        visit.setPlace(park);
        assertEquals("슨푸성 공원", PdfExportService.placeTitle(visit));

        Itinerary untranslated = new Itinerary();
        untranslated.setPlace(shrine);
        assertEquals("静岡浅間神社", PdfExportService.placeTitle(untranslated));

        Itinerary free = new Itinerary();
        free.setPlace(place("DUMMY_FREE", "자유 시간", null, "자유시간", 0, 0));
        free.setCustomTitle("자유 시간 (주변 산책·휴식)");
        assertEquals("자유 시간 (주변 산책·휴식)", PdfExportService.placeTitle(free), "자유 시간·식사·고정 일정은 저장된 표시 이름 그대로");

        assertEquals("-", PdfExportService.placeTitle(new Itinerary()));
    }

    @Test
    void everyPlaceOfThePlanIsOfferedForNaming() {
        Place airport = place("AIRPORT_FSZ", "Mt. Fuji Shizuoka Airport", null, "교통", 34.7960, 138.1890);
        PlanService.DayPlan plan = new PlanService.DayPlan(1, DATE, "시즈오카");
        plan.getItems().add(new SimulatedItinerary(Type.START, airport, null, 9 * 60, null, 0, airport.getLatitude(), airport.getLongitude()));
        plan.getItems().addAll(day().subList(1, 5));
        plan.getItems().add(new SimulatedItinerary(Type.END, hotel, null, 18 * 60, null, 20, hotel.getLatitude(), hotel.getLongitude()));

        assertEquals(List.of(airport, park, shrine, garden, hotel), PlanGenerationService.placesInPlan(List.of(plan)),
                "공항·방문지·숙소 모두 (자유 시간처럼 장소가 없는 줄은 뺀다)");
    }

    // ---------------------------------------------------------------- 준비

    /** 숙소 출발 → 공원 → 신사 → 공원 → 자유 시간 → 숙소 도착 */
    private List<SimulatedItinerary> day() {
        List<SimulatedItinerary> items = new ArrayList<>();
        items.add(new SimulatedItinerary(Type.START, hotel, null, 9 * 60, null, 0, hotel.getLatitude(), hotel.getLongitude()));
        items.add(new SimulatedItinerary(Type.VISIT, park, null, 9 * 60 + 20, 11 * 60, 20, park.getLatitude(), park.getLongitude()));
        items.add(new SimulatedItinerary(Type.VISIT, shrine, null, 11 * 60 + 20, 12 * 60 + 30, 20, shrine.getLatitude(), shrine.getLongitude()));
        items.add(new SimulatedItinerary(Type.VISIT, garden, null, 13 * 60, 14 * 60, 30, garden.getLatitude(), garden.getLongitude()));
        items.add(new SimulatedItinerary(Type.FREE, null, "자유 시간", 14 * 60, 16 * 60, 0, null, null));
        items.add(new SimulatedItinerary(Type.END, hotel, null, 16 * 60 + 30, null, 30, hotel.getLatitude(), hotel.getLongitude()));
        return items;
    }

    private static TimelineAssembler assembler() {
        return new TimelineAssembler(null, null, routes());
    }

    private static RailRouteService routes() {
        TransitNameService names = new TransitNameService(empty(TransitNameRepository.class), empty(TransitStationRepository.class),
                empty(TransitStopRepository.class), empty(TransitPatternRepository.class), null);
        RailRouteService routes = new RailRouteService(empty(TransitStationRepository.class), empty(TransitLinkRepository.class),
                empty(TransitStopRepository.class), empty(TransitPatternRepository.class), empty(TransitFeedRepository.class), names,
                FareMemory.emptyService(), new BusStopService());
        return routes;
    }

    @SuppressWarnings("unchecked")
    private static <T> T empty(Class<T> type) {
        return (T) Proxy.newProxyInstance(PlaceNameDisplayTest.class.getClassLoader(), new Class<?>[]{type},
                (proxy, method, args) -> method.getName().equals("findAll") ? new ArrayList<>() : null);
    }

    private static Place place(String placeId, String name, String nameKo, String category, double lat, double lng) {
        Place p = new Place();
        p.setPlaceId(placeId);
        p.setName(name);
        p.setNameKo(nameKo);
        p.setCategory(category);
        p.setAddress("시즈오카시");          // 주소가 있으면 외부 조회를 하지 않는다
        p.setLatitude(lat);
        p.setLongitude(lng);
        return p;
    }
}
