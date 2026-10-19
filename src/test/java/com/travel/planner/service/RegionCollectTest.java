package com.travel.planner.service;

import com.travel.planner.entity.Place;
import com.travel.planner.entity.Region;
import com.travel.planner.util.CollectRegions;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 지역별 장소 수집: 도시 목록, 건너뛰기, 한도, 이어서 하기, 중복 쪽 건너뛰기 */
class RegionCollectTest {

    @Test
    void regionNamesAreParsed() {
        assertEquals(List.of(Region.KANSAI), CollectRegions.parse("간사이"));
        assertEquals(List.of(Region.KANSAI), CollectRegions.parse("kansai"));
        assertEquals(List.of(Region.KANSAI, Region.KANTO), CollectRegions.parse("KANSAI, 간토 지방"));
        assertEquals(CollectRegions.PRIORITY, CollectRegions.parse("ALL"));
        assertEquals(9, CollectRegions.parse("전체").size());
        assertThrows(IllegalArgumentException.class, () -> CollectRegions.parse("서울"));
        assertThrows(IllegalArgumentException.class, () -> CollectRegions.parse(" "));
    }

    @Test
    void cityListUsesDbNamesAndHasNoDuplicates() {
        Set<String> names = new HashSet<>();
        for (CollectRegions.City c : CollectRegions.all()) {
            assertTrue(names.add(c.name), "도시 이름이 두 번 나온다: " + c.name);
            assertFalse(c.query.isBlank());
        }
        // 이미 DB 에 있는 도시는 DB 표기 그대로
        for (String dbName : List.of("도쿄", "오사카", "유후시", "비에이조", "에노시마", "슈젠지", "고텐바", "오키나와")) {
            assertTrue(names.contains(dbName), dbName);
        }
        assertTrue(names.size() >= 150, "도시 수: " + names.size());
        for (Region r : CollectRegions.PRIORITY) assertFalse(CollectRegions.of(r).isEmpty(), r.name());
    }

    @Test
    void richOrAlreadyCollectedCitiesAreSkipped() {
        Map<String, Long> counts = new HashMap<>();
        counts.put("오사카", 230L);   // 큰 도시 80곳 이상 → 건너뜀
        counts.put("고베", 77L);      // 큰 도시인데 80곳 미만 → 수집
        counts.put("히메지", 22L);    // 작은 도시 25곳 미만 → 수집
        counts.put("와카야마", 30L);  // 작은 도시 25곳 이상 → 건너뜀
        RegionCollectService.Plan plan = RegionCollectService.plan(List.of(Region.KANSAI), counts, List.of("우지"), false);

        List<String> targets = plan.targets.stream().map(t -> t.city.name).toList();
        assertFalse(targets.contains("오사카"));
        assertTrue(targets.contains("고베"));
        assertTrue(targets.contains("히메지"));
        assertFalse(targets.contains("와카야마"));
        assertFalse(targets.contains("우지"), "전에 끝낸 도시");
        assertTrue(plan.skipped.get("우지").contains("끝낸"));
        assertTrue(plan.skipped.get("오사카").contains("230"));

        RegionCollectService.Plan forced = RegionCollectService.plan(List.of(Region.KANSAI), counts, List.of("우지"), true);
        assertEquals(CollectRegions.of(Region.KANSAI).size(), forced.targets.size(), "force 면 전부 돈다");
    }

    @Test
    void previewEstimatesCallsAndCost() {
        RegionCollectService.Plan plan = RegionCollectService.plan(List.of(Region.KANSAI), Map.of(), List.of(), false);
        Map<String, Object> small = RegionCollectService.describePlan(plan, List.of(Region.KANSAI), 150);
        int est = (int) small.get("예상 호출 합계");
        // 큰 도시 4곳 × 100 + 작은 도시 × 12
        assertEquals(4 * 100 + (CollectRegions.of(Region.KANSAI).size() - 4) * 12, est);
        assertTrue(small.get("이번 실행 예상").toString().contains("오사카"), "한도 150번이면 첫 도시(오사카)까지: " + small.get("이번 실행 예상"));

        Map<String, Object> big = RegionCollectService.describePlan(plan, List.of(Region.KANSAI), 5000);
        assertTrue(big.get("이번 실행 예상").toString().contains("모두"));
        assertEquals("약 35.0달러 (약 48,000원)", RegionCollectService.cost(1000));
    }

    // ---------------------------------------------------------------- 실행

    /** 검색 한 번에 장소 3곳(쪽 1개)을 돌려주는 가짜 구글 */
    static class FakeGoogle extends GoogleMapsService {
        int searches;
        final List<String> asked = new ArrayList<>();

        FakeGoogle() { super(null); }

        @Override public String getFormalizedJapanCity(String cityInput) {
            if (cityInput.contains("없는")) throw new RuntimeException("없음");
            return "일본 " + cityInput;
        }

        @Override public List<double[]> getCityGrid(String cityInput) {
            List<double[]> g = new ArrayList<>();
            for (int i = 0; i < 5; i++) g.add(new double[]{34.0 + i * 0.01, 135.0, 3000});
            return g;
        }

        @Override public CollectBatch searchForCollection(String city, String formalizedCity, double lat, double lng, double radius,
                                                          String searchItem, int maxPages, boolean isEmergency,
                                                          java.util.function.Predicate<String> isKnown) {
            searches++;
            asked.add(city + "|" + lat + "|" + searchItem);
            CollectBatch b = new CollectBatch();
            b.calls = 1;
            for (int k = 0; k < 3; k++) {
                Place p = new Place();
                p.setPlaceId(city + "_" + searchItem + "_" + k);   // 같은 도시·검색어면 격자가 달라도 같은 장소 (중복)
                p.setName(city + " 장소 " + k);
                b.places.add(p);
            }
            return b;
        }
    }

    static class MemoryStore implements RegionCollectService.Store {
        final Map<String, Place> places = new HashMap<>();
        final List<String> logged = new ArrayList<>();

        @Override public boolean known(String placeId) { return places.containsKey(placeId); }
        @Override public Place find(String placeId) { return places.get(placeId); }
        @Override public void save(Place place) { places.put(place.getPlaceId(), place); }
        @Override public void logCity(CollectRegions.City city, int calls, int inserted) { logged.add(city.name); }
    }

    private static RegionCollectService.Plan planOf(String... names) {
        List<String> wanted = List.of(names);
        RegionCollectService.Plan all = RegionCollectService.plan(CollectRegions.PRIORITY, Map.of(), List.of(), true);
        RegionCollectService.Plan plan = new RegionCollectService.Plan();
        for (String n : wanted) {
            plan.targets.add(all.targets.stream().filter(t -> t.city.name.equals(n)).findFirst().orElseThrow());
        }
        return plan;
    }

    @Test
    void townsUseOneGridPointAndEightSearches() {
        FakeGoogle google = new FakeGoogle();
        RegionCollectService service = new RegionCollectService(null, null, google);
        MemoryStore store = new MemoryStore();
        RegionCollectService.Plan plan = planOf("히메지", "고베");
        RegionCollectService.Status s = RegionCollectService.newStatus(List.of(Region.KANSAI), 10_000, plan);
        service.setRunningForTest(true);
        service.collect(plan, 10_000, store, s);

        long himeji = google.asked.stream().filter(a -> a.startsWith("히메지|")).count();
        long kobe = google.asked.stream().filter(a -> a.startsWith("고베|")).count();
        assertEquals(8, himeji, "작은 도시: 검색어 8개 × 도심 1곳");
        assertEquals(5 * 16, kobe, "큰 도시: 검색어 16개 × 격자 5곳");
        assertEquals(List.of("히메지", "고베"), store.logged);
        assertEquals("완료", s.state);
        assertEquals(8 * 3 + 16 * 3, s.inserted, "격자가 달라도 같은 장소는 한 번만 넣는다");
        assertEquals("히메지", store.places.get("히메지_[TYPE]park_0").getCity());
    }

    @Test
    void stopsAtBudgetWithoutLoggingTheCityAndResumesThere() {
        FakeGoogle google = new FakeGoogle();
        RegionCollectService service = new RegionCollectService(null, null, google);
        MemoryStore store = new MemoryStore();
        RegionCollectService.Plan plan = planOf("히메지", "와카야마");

        // 한도 10번: 히메지(작은 도시, 최대 2쪽씩 → 8번)는 끝나고, 와카야마는 첫 검색 전에 멈춘다 (8 + 2 > 10 은 아니므로 한 번 하고 멈춤)
        RegionCollectService.Status s = RegionCollectService.newStatus(List.of(Region.KANSAI), 10, plan);
        service.setRunningForTest(true);
        service.collect(plan, 10, store, s);
        assertEquals("한도 도달", s.state);
        assertEquals(List.of("히메지"), store.logged, "멈춘 도시는 기록하지 않는다");
        assertTrue(s.calls <= 10, "한도를 넘지 않는다: " + s.calls);
        assertTrue(s.message.contains("와카야마"));

        // 다음 실행: 끝낸 도시는 계획에서 빠지고 와카야마부터
        RegionCollectService.Plan next = RegionCollectService.plan(List.of(Region.KANSAI), Map.of(), store.logged, false);
        assertFalse(next.targets.stream().anyMatch(t -> t.city.name.equals("히메지")));
        assertTrue(next.targets.stream().anyMatch(t -> t.city.name.equals("와카야마")));
    }

    @Test
    void stopRequestEndsTheRun() {
        FakeGoogle google = new FakeGoogle() {
            @Override public CollectBatch searchForCollection(String city, String formalizedCity, double lat, double lng, double radius,
                                                              String searchItem, int maxPages, boolean isEmergency,
                                                              java.util.function.Predicate<String> isKnown) {
                CollectBatch b = super.searchForCollection(city, formalizedCity, lat, lng, radius, searchItem, maxPages, isEmergency, isKnown);
                if (searches == 3) stopper.run();
                return b;
            }
        };
        RegionCollectService service = new RegionCollectService(null, null, google);
        stopper = service::stop;
        MemoryStore store = new MemoryStore();
        RegionCollectService.Plan plan = planOf("히메지", "와카야마");
        RegionCollectService.Status s = RegionCollectService.newStatus(List.of(Region.KANSAI), 1000, plan);
        service.setRunningForTest(true);
        service.collect(plan, 1000, store, s);
        assertEquals("중지됨", s.state);
        assertEquals(3, google.searches);
        assertTrue(store.logged.isEmpty());
    }

    private static Runnable stopper;

    @Test
    void unknownCityIsSkippedNotLogged() {
        FakeGoogle google = new FakeGoogle() {
            @Override public String getFormalizedJapanCity(String cityInput) {
                if (cityInput.startsWith("히메지")) throw new RuntimeException("없음");
                return super.getFormalizedJapanCity(cityInput);
            }
        };
        RegionCollectService service = new RegionCollectService(null, null, google);
        MemoryStore store = new MemoryStore();
        RegionCollectService.Plan plan = planOf("히메지", "와카야마");
        RegionCollectService.Status s = RegionCollectService.newStatus(List.of(Region.KANSAI), 1000, plan);
        service.setRunningForTest(true);
        service.collect(plan, 1000, store, s);
        assertEquals(List.of("와카야마"), store.logged);
        assertTrue(s.finished.get(0).contains("건너뜀"));
        assertEquals("완료", s.state);
    }

    // ---------------------------------------------------------------- 중복이 많으면 다음 쪽을 받지 않는다

    private static String page(String token, String... ids) {
        StringBuilder sb = new StringBuilder("{\"places\":[");
        for (int i = 0; i < ids.length; i++) {
            if (i > 0) sb.append(',');
            sb.append("{\"id\":\"").append(ids[i]).append("\",\"displayName\":{\"text\":\"장소").append(ids[i]).append("\"},")
                    .append("\"formattedAddress\":\"일본 효고현 히메지시 1-").append(i).append("\",\"rating\":4.3,\"userRatingCount\":500,")
                    .append("\"location\":{\"latitude\":34.8,\"longitude\":134.7},\"primaryType\":\"tourist_attraction\"}");
        }
        sb.append("]");
        if (token != null) sb.append(",\"nextPageToken\":\"").append(token).append('"');
        return sb.append('}').toString();
    }

    @Test
    void pagingStopsWhenThePageIsMostlyKnown() {
        List<String> pages = List.of(
                page("t2", "a1", "a2", "a3", "a4", "a5"),
                page("t3", "k1", "k2", "k3", "k4", "n1"),   // 5곳 중 4곳이 이미 있음 → 여기서 멈춤
                page(null, "z1"));
        int[] served = {0};
        GoogleMapsService google = new GoogleMapsService(null) {
            @Override protected String postTextSearch(Map<String, Object> body, HttpHeaders headers) {
                return pages.get(served[0]++);
            }
        };
        Set<String> known = Set.of("k1", "k2", "k3", "k4");
        GoogleMapsService.CollectBatch b = google.searchForCollection("히메지", "일본 효고현 히메지시", 34.8, 134.7, 3000,
                "[TYPE]tourist_attraction", 3, false, known::contains);
        assertEquals(2, b.calls, "세 번째 쪽은 받지 않는다");
        assertTrue(b.stoppedEarly);
        assertEquals(10, b.places.size());

        served[0] = 0;
        GoogleMapsService.CollectBatch all = google.searchForCollection("히메지", "일본 효고현 히메지시", 34.8, 134.7, 3000,
                "[TYPE]tourist_attraction", 3, false, null);
        assertEquals(3, all.calls, "확인 함수가 없으면(기존 /collect-places) 예전처럼 3쪽까지");
        assertFalse(all.stoppedEarly);
    }

    @Test
    void mostlyKnownRule() {
        Place a = new Place(); a.setPlaceId("a");
        Place b = new Place(); b.setPlaceId("b");
        Place c = new Place(); c.setPlaceId("c");
        Place d = new Place(); d.setPlaceId("d");
        Place e = new Place(); e.setPlaceId("e");
        assertTrue(GoogleMapsService.mostlyKnown(List.of(), id -> false), "기준을 넘은 장소가 없는 쪽이면 다음 쪽도 볼 필요가 없다");
        assertTrue(GoogleMapsService.mostlyKnown(List.of(a, b, c, d, e), Set.of("a", "b", "c", "d")::contains));
        assertFalse(GoogleMapsService.mostlyKnown(List.of(a, b, c, d, e), Set.of("a", "b", "c")::contains));
    }
}
