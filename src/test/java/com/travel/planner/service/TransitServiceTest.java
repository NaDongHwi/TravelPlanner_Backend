package com.travel.planner.service;

import com.travel.planner.dto.AiRouteResponse;
import com.travel.planner.dto.TransitLegDto;
import com.travel.planner.entity.Place;
import com.travel.planner.entity.TransitName;
import com.travel.planner.repository.TransitLinkRepository;
import com.travel.planner.repository.TransitNameRepository;
import com.travel.planner.repository.TransitStationRepository;
import com.travel.planner.service.PlanService.SimulatedItinerary;
import com.travel.planner.service.PlanService.SimulatedItinerary.Type;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 일정에 교통 정보를 붙이는 부분 테스트 (철도망 없이도 동작해야 하는 경로 포함). */
class TransitServiceTest {

    @Test
    void legsFollowRealLocationsAndSkipPlaceholders() {
        RailRouteService service = serviceWithoutNetwork();
        assertFalse(service.isNetworkLoaded());
        assertEquals(0, service.stationCount());

        Place hotel = place("호텔", 34.6660, 135.5020);
        Place museum = place("박물관", 34.6930, 135.5020);       // 북쪽으로 약 3km
        Place cafe = place("카페", 34.6960, 135.5020);            // 거기서 약 330m
        List<SimulatedItinerary> items = new ArrayList<>();
        items.add(new SimulatedItinerary(Type.START, hotel, null, 540, null, 0, hotel.getLatitude(), hotel.getLongitude()));
        items.add(new SimulatedItinerary(Type.VISIT, museum, null, 560, 650, 20, museum.getLatitude(), museum.getLongitude()));
        items.add(new SimulatedItinerary(Type.MEAL, null, "점심 식사 (주변 자유 식사)", 660, 720, 0, museum.getLatitude(), museum.getLongitude()));
        items.add(new SimulatedItinerary(Type.VISIT, cafe, null, 730, 780, 5, cafe.getLatitude(), cafe.getLongitude()));
        items.add(new SimulatedItinerary(Type.END, hotel, null, 800, null, 20, hotel.getLatitude(), hotel.getLongitude()));

        List<TransitLegDto> legs = service.legsFor(items, "도보 및 대중교통");

        assertEquals(items.size(), legs.size(), "줄마다 하나씩 (이동이 없으면 null)");
        assertNull(legs.get(0), "출발 줄에는 이동이 없다");
        assertEquals("버스 등", legs.get(1).getMode(), "철도망이 없으면 3km 는 노선 미확인으로 추정");
        assertEquals(20, legs.get(1).getMinutes());
        assertTrue(legs.get(1).getFareYen() > 0);
        assertNull(legs.get(2), "자유 식사는 위치가 없으므로 이동도 없다");
        assertEquals("도보", legs.get(3).getMode(), "자유 식사 다음 장소는 그 전의 실제 장소(박물관)에서 걸어온다");
        assertEquals(0, legs.get(3).getFareYen());
        assertEquals("박물관", legs.get(3).getSteps().get(0).getFrom());
        assertEquals("버스 등", legs.get(4).getMode());

        // 렌터카 여행: 요금을 만들지 않는다
        List<TransitLegDto> car = service.legsFor(items, "렌트카");
        assertEquals("자동차", car.get(1).getMode());
        assertNull(car.get(1).getFareYen());
    }

    @Test
    void totalsAreAddedToDayOverviewAndTripSummary() {
        AiRouteResponse response = new AiRouteResponse();
        response.setReason("오사카 2박 3일 여행입니다.");
        List<AiRouteResponse.TimelineItem> timeline = new ArrayList<>();
        timeline.add(item(1, null));
        timeline.add(item(1, leg("지하철", 240, "rail-network")));
        timeline.add(item(1, leg("도보", 0, "walk")));
        timeline.add(item(2, leg("버스 등", 320, "distance")));
        timeline.add(item(2, leg("JR", 190, "rail-network")));
        response.setTimeline(timeline);
        List<AiRouteResponse.DaySummary> days = new ArrayList<>();
        for (int d = 1; d <= 3; d++) {
            AiRouteResponse.DaySummary day = new AiRouteResponse.DaySummary();
            day.setDay(d);
            day.setSummary(d + "일차입니다.");
            days.add(day);
        }
        response.setDays(days);

        TransitTotals.apply(response, true);

        assertEquals(240, days.get(0).getTransitFareYen());
        assertEquals(510, days.get(1).getTransitFareYen());
        assertNull(days.get(2).getTransitFareYen(), "이동이 없는 날은 비워 둔다");
        assertEquals("1일차입니다. 교통비는 약 240엔(성인 1인, 추정)입니다.", days.get(0).getSummary());
        assertEquals("3일차입니다.", days.get(2).getSummary());
        assertEquals(750, response.getTransitFareYen());
        assertTrue(response.getReason().endsWith("교통비는 합계 약 750엔(성인 1인, 추정)입니다."), response.getReason());
        assertTrue(response.getTransitNote().contains("1개 구간은"), "노선 미확인 구간 수를 알린다: " + response.getTransitNote());
        assertEquals("1,240엔", TransitTotals.yen(1240));
    }

    @Test
    void walkingOnlyTripHasNoFareSentenceAndMissingNetworkIsExplained() {
        AiRouteResponse walking = new AiRouteResponse();
        walking.setReason("요약.");
        walking.setTimeline(List.of(item(1, leg("도보", 0, "walk"))));
        TransitTotals.apply(walking, true);
        assertNull(walking.getTransitFareYen());
        assertEquals("요약.", walking.getReason());

        AiRouteResponse noNetwork = new AiRouteResponse();
        noNetwork.setReason("요약.");
        noNetwork.setTimeline(List.of(item(1, leg("버스 등", 320, "distance"))));
        TransitTotals.apply(noNetwork, false);
        assertEquals(320, noNetwork.getTransitFareYen());
        assertTrue(noNetwork.getTransitNote().contains("철도망 데이터를 아직 가져오지 않아"), noNetwork.getTransitNote());
    }

    @Test
    void translatedNamesAreCleaned() {
        assertEquals("신주쿠", TransitNameService.clean(TransitName.STATION, " \"신주쿠역\" "));
        assertEquals("야마노테선", TransitNameService.clean(TransitName.LINE, "야마노테선"));
        assertNull(TransitNameService.clean(TransitName.STATION, "新宿"), "한글이 없는 답(번역 안 됨)은 버린다");
        assertNull(TransitNameService.clean(TransitName.STATION, ""));
        assertNull(TransitNameService.clean(TransitName.STATION, null));
        assertNull(TransitNameService.clean(TransitName.LINE, "가".repeat(80)), "문장을 통째로 넣은 답은 버린다");
    }

    // ---------------------------------------------------------------- 준비

    /** 철도망·번역 표가 비어 있는 상태의 서비스 */
    private static RailRouteService serviceWithoutNetwork() {
        TransitStationRepository stations = empty(TransitStationRepository.class);
        TransitLinkRepository links = empty(TransitLinkRepository.class);
        TransitNameRepository names = empty(TransitNameRepository.class);
        return new RailRouteService(stations, links, new TransitNameService(names, stations, null));
    }

    @SuppressWarnings("unchecked")
    private static <T> T empty(Class<T> type) {
        return (T) Proxy.newProxyInstance(TransitServiceTest.class.getClassLoader(), new Class<?>[]{type},
                (proxy, method, args) -> method.getName().equals("findAll") ? new ArrayList<>() : null);
    }

    private static Place place(String name, double lat, double lng) {
        Place p = new Place();
        p.setPlaceId("P_" + name);
        p.setName(name);
        p.setCategory("관광지");
        p.setLatitude(lat);
        p.setLongitude(lng);
        return p;
    }

    private static AiRouteResponse.TimelineItem item(int day, TransitLegDto leg) {
        AiRouteResponse.TimelineItem item = new AiRouteResponse.TimelineItem();
        item.setDay(day);
        item.setTransit(leg);
        return item;
    }

    private static TransitLegDto leg(String mode, int fare, String source) {
        TransitLegDto leg = new TransitLegDto();
        leg.setMode(mode);
        leg.setFareYen(fare);
        leg.setSource(source);
        leg.setEstimated(true);
        return leg;
    }
}
