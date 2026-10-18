package com.travel.planner.service;

import com.travel.planner.dto.AiRouteResponse;
import com.travel.planner.dto.PassSuggestionDto;
import com.travel.planner.dto.TransitLegDto;
import com.travel.planner.entity.Place;
import com.travel.planner.entity.TransitLink;
import com.travel.planner.entity.TransitStation;
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
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 하루 일정 → 구간별 교통 정보 → 교통비 합계 → 패스 추천까지, 일정 생성 때와 같은 순서로 이어서 돌려 본다.
 * 철도망은 DB 대신 메모리의 역·연결 행(오사카메트로 미도스지선 난바~우메다)을 쓰고, 요금·패스는 프로젝트의 기본 자료를 쓴다.
 */
class FareFlowTest {

    private static final double[] NAMBA = {34.6667, 135.5008};
    private static final double[] SHINSAIBASHI = {34.6748, 135.5012};
    private static final double[] HOMMACHI = {34.6823, 135.4988};
    private static final double[] YODOYABASHI = {34.6925, 135.5010};
    private static final double[] UMEDA = {34.7033, 135.4984};

    @Test
    void saturdayInOsakaGetsTheHolidayDayPass() {
        // 2026-10-17 은 토요일: 지하철 네 번 = 860엔, 엔조이 에코 카드(토·일·공휴일 620엔)가 240엔 이득
        AiRouteResponse response = planDay(LocalDate.of(2026, 10, 17));

        List<TransitLegDto> legs = response.getTimeline().stream().map(AiRouteResponse.TimelineItem::getTransit)
                .filter(l -> l != null).collect(Collectors.toList());
        assertEquals(4, legs.size());
        assertEquals(List.of(240, 190, 190, 240), legs.stream().map(TransitLegDto::getFareYen).collect(Collectors.toList()));
        for (TransitLegDto leg : legs) {
            assertEquals("지하철", leg.getMode());
            assertEquals("table", leg.getFareSource());
        }
        assertEquals(860, response.getTransitFareYen());
        assertEquals(860, response.getDays().get(0).getTransitFareYen());
        assertTrue(response.getTransitNote().contains("운영사 요금표"), response.getTransitNote());
        assertFalse(response.getTransitNote().contains("종류별 요금표로 계산했고"), response.getTransitNote());

        assertEquals(1, response.getPasses().size());
        PassSuggestionDto pass = response.getPasses().get(0);
        assertEquals("osaka-enjoy-eco-holiday", pass.getKey());
        assertEquals(620, pass.getPriceYen());
        assertEquals(860, pass.getCoveredFareYen());
        assertEquals(240, pass.getSavingYen());
        assertEquals(4, pass.getRideCount());
        assertEquals("2026-10-17", pass.getStartDate());
        assertNull(pass.getStartTime());
        assertFalse(pass.isEstimated(), "운영사 요금표로 구한 요금만 썼다");
        assertEquals(620, response.getTransitFareWithPassesYen());
        assertTrue(response.getReason().contains("오사카 엔조이 에코 카드"), response.getReason());
    }

    @Test
    void lodgingRowsSayWhatKindOfPlaceTheLodgingIs() {
        AiRouteResponse response = planDay(LocalDate.of(2026, 10, 17));
        AiRouteResponse.TimelineItem leave = response.getTimeline().get(0);
        AiRouteResponse.TimelineItem back = response.getTimeline().get(response.getTimeline().size() - 1);
        assertEquals("숙소 출발", leave.getDescription());
        assertEquals("숙소 도착 및 휴식", back.getDescription());
        // AI 소개가 아직 없는 숙소도 한 줄 소개가 비지 않는다 (AI 가 채우면 그 문장이 대신 나간다)
        assertEquals("여행 중 머무는 숙소", leave.getSummary());
        assertEquals("여행 중 머무는 숙소", back.getSummary());
        assertEquals("숙소", leave.getSubType());
        assertEquals("오사카시", leave.getFormattedAddress());
    }

    @Test
    void sameDayOnAWeekdayIsNotWorthThePass() {
        // 수요일에는 평일권(820엔)이라 40엔 차이뿐: 추천하지 않는다
        AiRouteResponse response = planDay(LocalDate.of(2026, 10, 14));
        assertEquals(860, response.getTransitFareYen());
        assertTrue(response.getPasses().isEmpty());
        assertNull(response.getTransitFareWithPassesYen());
        assertFalse(response.getReason().contains("패스"), response.getReason());
    }

    @Test
    void withoutFareDataTheSameDayIsEstimatedAndNoPassIsSuggested() {
        FareMemory memory = new FareMemory();     // 기본 자료를 넣지 않은 DB
        AiRouteResponse response = planDay(LocalDate.of(2026, 10, 17), memory.service);
        assertTrue(response.getTransitFareYen() > 0);
        for (AiRouteResponse.TimelineItem item : response.getTimeline()) {
            if (item.getTransit() != null) assertEquals("estimate", item.getTransit().getFareSource());
        }
        assertTrue(response.getTransitNote().contains("종류별 요금표로 계산했고"), response.getTransitNote());
        assertTrue(response.getPasses().isEmpty());
    }

    // ---------------------------------------------------------------- 일정 생성과 같은 순서

    private static AiRouteResponse planDay(LocalDate date) {
        return planDay(date, FareMemory.bundledService());
    }

    private static AiRouteResponse planDay(LocalDate date, FareDataService fareData) {
        TransitNameService names = new TransitNameService(empty(TransitNameRepository.class), empty(TransitStationRepository.class),
                empty(TransitStopRepository.class), empty(TransitPatternRepository.class), null);
        RailRouteService routes = new RailRouteService(stations(), links(), empty(TransitStopRepository.class), empty(TransitPatternRepository.class),
                empty(TransitFeedRepository.class), names, fareData, new BusStopService());
        TimelineAssembler assembler = new TimelineAssembler(null, null, routes);

        Place hotel = place("난바 호텔", "숙소", NAMBA);
        Place sky = place("우메다 전망대", "관광지", UMEDA);
        Place museum = place("혼마치 박물관", "관광지", HOMMACHI);
        Place mall = place("우메다 백화점", "쇼핑", UMEDA);
        List<SimulatedItinerary> items = new ArrayList<>();
        items.add(new SimulatedItinerary(Type.START, hotel, null, 9 * 60, null, 0, hotel.getLatitude(), hotel.getLongitude()));
        items.add(new SimulatedItinerary(Type.VISIT, sky, null, 9 * 60 + 30, 11 * 60, 30, sky.getLatitude(), sky.getLongitude()));
        items.add(new SimulatedItinerary(Type.VISIT, museum, null, 11 * 60 + 30, 13 * 60, 30, museum.getLatitude(), museum.getLongitude()));
        items.add(new SimulatedItinerary(Type.VISIT, mall, null, 13 * 60 + 30, 16 * 60, 30, mall.getLatitude(), mall.getLongitude()));
        items.add(new SimulatedItinerary(Type.END, hotel, null, 16 * 60 + 30, null, 30, hotel.getLatitude(), hotel.getLongitude()));

        AiRouteResponse response = new AiRouteResponse();
        response.setReason("오사카 하루 여행입니다.");
        AiRouteResponse.DaySummary day = new AiRouteResponse.DaySummary();
        day.setDay(1);
        day.setDate(date.toString());
        day.setSummary("오사카 시내를 돌아봅니다.");
        response.setDays(new ArrayList<>(List.of(day)));
        response.setTimeline(assembler.toTimeline(1, date, items, "ko", "대중교통"));
        TransitTotals.apply(response, routes.isNetworkLoaded());
        new PassAdvisorService(fareData, names).apply(response);
        return response;
    }

    private static Place place(String name, String category, double[] at) {
        Place p = new Place();
        p.setPlaceId("P_" + name);
        p.setName(name);
        p.setCategory(category);
        p.setAddress("오사카시");          // 주소가 있으면 외부 조회를 하지 않는다
        p.setLatitude(at[0]);
        p.setLongitude(at[1]);
        return p;
    }

    // ---------------------------------------------------------------- 메모리의 철도망

    private static TransitStationRepository stations() {
        List<TransitStation> rows = new ArrayList<>();
        Object[][] data = {{"難波", NAMBA}, {"心斎橋", SHINSAIBASHI}, {"本町", HOMMACHI}, {"淀屋橋", YODOYABASHI}, {"梅田", UMEDA}};
        long id = 1;
        for (Object[] d : data) {
            TransitStation s = new TransitStation();
            s.setId(id);
            s.setCode("M" + id);
            s.setGroupCode("G" + id);
            s.setName((String) d[0]);
            s.setOperator("大阪市高速電気軌道");
            s.setLine("1号線(御堂筋線)");
            s.setCategory("SUBWAY");
            s.setLatitude(((double[]) d[1])[0]);
            s.setLongitude(((double[]) d[1])[1]);
            rows.add(s);
            id++;
        }
        return rows(TransitStationRepository.class, rows);
    }

    private static TransitLinkRepository links() {
        List<TransitLink> rows = new ArrayList<>();
        double[] km = {0.9, 1.0, 0.9, 1.3};
        for (int i = 0; i < km.length; i++) {
            TransitLink link = new TransitLink();
            link.setId((long) (i + 1));
            link.setFromStationId((long) (i + 1));
            link.setToStationId((long) (i + 2));
            link.setDistanceKm(km[i]);
            rows.add(link);
        }
        return rows(TransitLinkRepository.class, rows);
    }

    @SuppressWarnings("unchecked")
    private static <T> T rows(Class<T> type, List<?> rows) {
        return (T) Proxy.newProxyInstance(FareFlowTest.class.getClassLoader(), new Class<?>[]{type},
                (proxy, method, args) -> method.getName().equals("findAll") ? new ArrayList<>(rows) : null);
    }

    private static <T> T empty(Class<T> type) {
        return rows(type, new ArrayList<>());
    }
}
