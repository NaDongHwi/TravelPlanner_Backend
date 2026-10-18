package com.travel.planner.service;

import com.travel.planner.entity.Place;
import com.travel.planner.repository.PlaceRepository;
import com.travel.planner.repository.TransitFeedRepository;
import com.travel.planner.repository.TransitLinkRepository;
import com.travel.planner.repository.TransitNameRepository;
import com.travel.planner.repository.TransitPatternRepository;
import com.travel.planner.repository.TransitStationRepository;
import com.travel.planner.repository.TransitStopRepository;
import com.travel.planner.transit.BusStopIndex;
import com.travel.planner.transit.TransitLegPlanner;
import com.travel.planner.util.Hiking;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 등산하는 곳의 입구(대중교통·주차장)와 후지산 다루기 */
class HikeAccessTest {

    static final double LAT = 34.97;
    static final double LNG = 138.39;

    /** 주차장 검색을 흉내 낸다: 정해 둔 답을 돌려주고 몇 번 불렸는지 센다 */
    static class FakeGoogle extends GoogleMapsService {
        Object[] answer;
        int calls;
        FakeGoogle(Object[] answer) { super(null); this.answer = answer; }
        @Override public Object[] findNearestParking(double lat, double lng, double radiusMeters) { calls++; return answer; }
    }

    @Test
    void parkingIsLookedUpOnceOnlyForSummitsWithoutTransit() {
        RailRouteService routes = routes();
        List<Object[]> saved = new ArrayList<>();
        FakeGoogle google = new FakeGoogle(new Object[]{LAT + 0.2 - 0.002, LNG, "정상 주차장"});
        HikeAccessService service = new HikeAccessService(routes, google, repository(saved));

        Place remote = summit(1L, "SUMMIT", LAT + 0.2, LNG);          // 근처에 정류장 없음
        Place takao = summit(2L, "TAKAO", LAT + 0.005, LNG);          // 정상 0.6km 안에 정류장(시험용 정류장 옆)
        service.markTransitAccess(List.of(remote, takao));
        assertEquals(Boolean.FALSE, remote.getHikeTransitGate());
        assertEquals(Boolean.TRUE, takao.getHikeTransitGate());

        assertEquals(1, service.fillParking(List.of(remote, takao)));
        assertEquals(1, google.calls, "대중교통 입구가 있는 곳은 찾지 않는다");
        assertEquals("정상 주차장", remote.getTrailheadName());
        assertEquals(1, saved.size());

        // 한 번 찾은 곳은 다시 찾지 않는다. 없으면 "-" 로 남겨 다시 묻지 않는다
        service.fillParking(List.of(remote));
        assertEquals(1, google.calls);
        Place empty = summit(3L, "EMPTY", LAT + 0.3, LNG);
        new HikeAccessService(routes, new FakeGoogle(new Object[0]), repository(saved)).fillParking(List.of(empty));
        assertEquals(HikeAccessService.NO_PARKING, empty.getTrailheadName());
        assertNotNull(RailRouteService.summit(empty, LAT, LNG), "주차장이 없어도 등산하는 곳 정보는 있다(가까운 정류장 기준)");
    }

    @Test
    void summitInfoForLegs() {
        Place withParking = summit(1L, "A", LAT + 0.2, LNG);
        withParking.setTrailheadLat(LAT + 0.198);
        withParking.setTrailheadLng(LNG);
        withParking.setTrailheadName("정상 주차장");
        assertNotNull(RailRouteService.summit(withParking, LAT, LNG));

        Place cafe = new Place();
        cafe.setName("카페");
        cafe.setCategory("식음");
        assertNull(RailRouteService.summit(cafe, LAT, LNG));

        // 후지산: 출발지에서 가까운 5합목을 고른다 (가와구치코 쪽이면 요시다 루트, 시즈오카 쪽이면 후지노미야)
        Place fuji = new Place();
        fuji.setName("富士山");
        fuji.setCategory("관광지");
        fuji.setSubType("산");
        assertTrue(Hiking.isFuji(fuji));
        assertFalse(Hiking.isHike(fuji), "후지산은 등산하는 곳이 아니라 5합목 관광으로 다룬다");
        assertEquals("후지산 요시다 루트 5합목(후지 스바루 라인)", Hiking.nearestFifthStation(35.50, 138.76)[0]);
        assertEquals("후지산 후지노미야 5합목", Hiking.nearestFifthStation(35.22, 138.62)[0]);
        Place view = new Place();
        view.setName("후지산 세계유산센터");
        view.setCategory("관광지");
        view.setLatitude(35.22);
        view.setLongitude(138.61);
        assertFalse(Hiking.isFuji(view));
    }

    @Test
    void summitWithoutTransitIsSlightlyLowerOnTransitTripsButNotByCar() {
        PlanService planService = new PlanService();
        int withGate = countSummit(planService, Boolean.TRUE, "도보 및 대중교통");
        int withoutGate = countSummit(planService, Boolean.FALSE, "도보 및 대중교통");
        int byCar = countSummit(planService, Boolean.FALSE, "차량 렌트");
        assertEquals(1, withGate, "역 가까운 산은 그대로 추천");
        assertEquals(1, byCar, "렌터카면 깎지 않는다");
        assertEquals(1, withoutGate, "조금만 낮추므로 평점이 아주 높은 곳은 그래도 들어간다(택시로 안내)");

        Place summit = summit(1L, "S", 34.69, 135.52);
        summit.setHikeTransitGate(Boolean.FALSE);
        assertEquals(-PlanService.HIKE_NO_TRANSIT_PENALTY, PlanService.hikeAdjustment(summit, false, false));
        assertEquals(0.0, PlanService.hikeAdjustment(summit, false, true), "렌터카");
        assertEquals(-PlanService.HIKE_NO_TRANSIT_PENALTY - PlanService.HIKE_PENALTY, PlanService.hikeAdjustment(summit, true, false), "부모님 + 대중교통");
        summit.setHikeTransitGate(Boolean.TRUE);
        assertEquals(0.0, PlanService.hikeAdjustment(summit, false, false), "역 가까운 산(다카오산형)");
    }

    private static int countSummit(PlanService planService, Boolean gate, String transport) {
        PlanService.TripInput in = PlanServiceTest.osakaTrip(2, "오전", "오후", "자연", "사진");
        in.request.setTransportation(transport);
        Place summit = TestPlaces.place("SUMMIT", "テスト山頂展望", "오사카", 34.6900, 135.5200, "관광지", "자연,사진", TestPlaces.H_24);
        summit.setSubType("산 정상");
        summit.setRating(4.9);
        summit.setUserRatingCount(30000);
        summit.setHikeTransitGate(gate);
        in.candidates.add(summit);
        PlanService.TripPlan plan = planService.planTrip(in);
        return (int) plan.getDays().stream().flatMap(d -> d.getItems().stream())
                .filter(i -> i.getType() == PlanService.SimulatedItinerary.Type.VISIT && i.getPlace() != null && "SUMMIT".equals(i.getPlace().getPlaceId()))
                .count();
    }

    // ---------------------------------------------------------------- 준비

    private static Place summit(Long id, String placeId, double lat, double lng) {
        Place p = new Place();
        p.setId(id);
        p.setPlaceId(placeId);
        p.setName(placeId + "岳");
        p.setCategory("관광지");
        p.setSubType("산");
        p.setLatitude(lat);
        p.setLongitude(lng);
        return p;
    }

    private static RailRouteService routes() {
        BusStopIndex.Builder b = new BusStopIndex.Builder();
        b.add("駅前", "テストバス", LAT, LNG, List.of("本線"), List.of(1));
        b.add("終点", "テストバス", LAT + 0.03, LNG, List.of("本線"), List.of(1));
        BusStopService buses = new BusStopService();
        buses.use(b.build("test"));
        TransitNameService names = new TransitNameService(empty(TransitNameRepository.class), empty(TransitStationRepository.class),
                empty(TransitStopRepository.class), empty(TransitPatternRepository.class), null);
        return new RailRouteService(empty(TransitStationRepository.class), empty(TransitLinkRepository.class), empty(TransitStopRepository.class),
                empty(TransitPatternRepository.class), empty(TransitFeedRepository.class), names, FareMemory.emptyService(), buses);
    }

    private static PlaceRepository repository(List<Object[]> saved) {
        return (PlaceRepository) Proxy.newProxyInstance(HikeAccessTest.class.getClassLoader(), new Class<?>[]{PlaceRepository.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("updateTrailhead")) {
                        saved.add(args);
                        return 1;
                    }
                    return null;
                });
    }

    @SuppressWarnings("unchecked")
    private static <T> T empty(Class<T> type) {
        return (T) Proxy.newProxyInstance(HikeAccessTest.class.getClassLoader(), new Class<?>[]{type}, (proxy, method, args) -> {
            switch (method.getName()) {
                case "findAll": return new ArrayList<>();
                case "findByPlaceId": return Optional.empty();
                default: return null;
            }
        });
    }
}
