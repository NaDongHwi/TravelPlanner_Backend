package com.travel.planner.service;

import com.travel.planner.dto.PlanRequest;
import com.travel.planner.entity.Place;
import com.travel.planner.service.PlanService.DayPlan;
import com.travel.planner.service.PlanService.SimulatedItinerary;
import com.travel.planner.service.PlanService.SimulatedItinerary.Type;
import com.travel.planner.service.PlanService.TripInput;
import com.travel.planner.service.PlanService.TripPlan;
import com.travel.planner.util.JapanCities;
import com.travel.planner.util.PlaceKind;
import com.travel.planner.util.SeasonNotes;
import com.travel.planner.util.TripProfile;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 단기·중기·장기 구분과 장기 살아보기·투어 */
class LongTripTest {

    private final PlanService planService = new PlanService();

    @Test
    void lengthAndStyle() {
        assertEquals(TripProfile.Length.SHORT, TripProfile.lengthOf(7));
        assertEquals(TripProfile.Length.MEDIUM, TripProfile.lengthOf(8));
        assertEquals(TripProfile.Length.MEDIUM, TripProfile.lengthOf(14));
        assertEquals(TripProfile.Length.LONG, TripProfile.lengthOf(15));
        assertNull(TripProfile.styleOf(14, true), "중기는 방식 구분 없음");
        assertEquals(TripProfile.Style.LIVE, TripProfile.styleOf(30, true), "근교 제외 켬 = 살아보기");
        assertEquals(TripProfile.Style.TOUR, TripProfile.styleOf(30, false));
        assertEquals(TripProfile.DayKind.LIVE_REST, TripProfile.dayKind(TripProfile.Style.LIVE, 5, 30, false));
        assertEquals(TripProfile.DayKind.LIVE_DAY, TripProfile.dayKind(TripProfile.Style.LIVE, 5, 30, true), "고정 일정이 있는 날은 여유일로 만들지 않는다");
        assertEquals(TripProfile.DayKind.LIVE_DAY, TripProfile.dayKind(TripProfile.Style.LIVE, 30, 30, false), "마지막 날은 여유일이 아니다");
        assertEquals(TripProfile.DayKind.TOUR_LIGHT, TripProfile.dayKind(TripProfile.Style.TOUR, 14, 30, false));
        assertEquals(TripProfile.DayKind.NORMAL, TripProfile.dayKind(TripProfile.Style.TOUR, 13, 30, false));
    }

    @Test
    void liveInOneCityIsLooseWithRestDays() {
        TripInput in = tokyoTrip(30, true);
        long started = System.currentTimeMillis();
        TripPlan plan = planService.planTrip(in);
        long ms = System.currentTimeMillis() - started;
        assertEquals(30, plan.getDays().size());
        for (DayPlan day : plan.getDays()) {
            long sights = sights(day);
            if (day.getDayNumber() % 5 == 0 && day.getDayNumber() != 30) {
                assertEquals(0, sights, "여유일 " + day.getDayNumber() + "일차");
                assertTrue(day.getPaceNote().startsWith("여유일"));
            } else if (sights > 1) {
                assertTrue(sightMinutes(day) <= PlanService.LIVE_DAY_SIGHT_MIN, day.getDayNumber() + "일차 관광 " + sightMinutes(day) + "분");
            }
            // 살아보기는 20:30 넘어 방문하지 않는다 (숙소 도착 줄 제외)
            day.getItems().stream().filter(i -> i.getType() == Type.VISIT)
                    .forEach(i -> assertTrue(i.getStartMin() <= PlanService.LIVE_DAY_END_MIN, day.getDayNumber() + "일차 " + i.getTime()));
        }
        long cafes = plan.getDays().stream().flatMap(d -> d.getItems().stream())
                .filter(i -> i.getPlace() != null && PlaceKind.of(i.getPlace()) == PlaceKind.CAFE).count();
        assertTrue(cafes >= 25, "거의 매일 카페 한 곳: " + cafes);
        DayPlan rest = plan.getDays().get(4);
        assertTrue(rest.getItems().stream().anyMatch(i -> i.getType() == Type.FREE), "여유일에는 자유 시간(동네 산책)이 들어간다");
        assertTrue(rest.getItems().get(rest.getItems().size() - 1).getStartMin() <= PlanService.LIVE_REST_END_MIN + 60, "여유일은 일찍 마친다");
        assertTrue(ms < 20_000, "30일 계산 " + ms + "ms");
        assertFalse(plan.getWarnings().stream().anyMatch(w -> w.contains("장소가 부족해 자유 시간")), "일부러 비운 시간은 장소 부족 경고를 내지 않는다");
    }

    @Test
    void sameTripAsTourIsDenserAndHasLightDays() {
        TripPlan live = planService.planTrip(tokyoTrip(30, true));
        TripPlan tour = planService.planTrip(tokyoTrip(30, false));
        long liveSights = live.getDays().stream().mapToLong(LongTripTest::sights).sum();
        long tourSights = tour.getDays().stream().mapToLong(LongTripTest::sights).sum();
        assertTrue(tourSights > liveSights, "투어 " + tourSights + " > 살아보기 " + liveSights);
        DayPlan light = tour.getDays().get(13);
        assertTrue(light.getPaceNote().startsWith("가벼운 날"));
        assertTrue(sights(light) <= 1 || sightMinutes(light) <= PlanService.TOUR_LIGHT_SIGHT_MIN, "가벼운 날 관광 " + sightMinutes(light) + "분");
    }

    @Test
    void shortTripsAreUnchanged() {
        TripInput in = tokyoTrip(5, true);
        TripPlan plan = planService.planTrip(in);
        assertTrue(plan.getDays().stream().allMatch(d -> d.getPaceNote() == null));
    }

    @Test
    void tourAddsNearbyCitiesInRouteOrder() {
        // 도쿄 입국 · 오사카 출국 30일: 도시당 약 4일 → 8곳
        List<String> cities = JapanCities.expandForTour(List.of("도쿄", "오사카"), 30, "도쿄", "오사카");
        assertEquals(8, cities.size(), cities.toString());
        assertEquals("도쿄", cities.get(0));
        assertEquals("오사카", cities.get(cities.size() - 1));
        assertFalse(cities.contains("삿포로"), "바다 건너 도시는 더하지 않는다");
        assertFalse(cities.contains("오키나와"));
        // 홋카이도를 골랐으면 홋카이도 안에서만
        List<String> hokkaido = JapanCities.expandForTour(List.of("삿포로"), 20, "삿포로", "삿포로");
        assertTrue(hokkaido.stream().allMatch(c -> JapanCities.find(c).island == 'H'), hokkaido.toString());
        // 짧으면 그대로, 표에 없는 도시만 골랐으면 그대로
        assertEquals(List.of("도쿄"), JapanCities.expandForTour(List.of("도쿄"), 4, "도쿄", "도쿄"));
        assertEquals(List.of("어딘가"), JapanCities.expandForTour(List.of("어딘가"), 30, null, null));
    }

    @Test
    void seasonNotesBeyondForecast() {
        assertTrue(SeasonNotes.of(LocalDate.of(2027, 6, 20), 35.68, 139.77).contains(SeasonNotes.RAINY), "도쿄 6월 장마");
        assertFalse(SeasonNotes.of(LocalDate.of(2027, 6, 20), 43.06, 141.35).contains(SeasonNotes.RAINY), "삿포로는 장마 없음");
        assertTrue(SeasonNotes.of(LocalDate.of(2027, 5, 20), 26.21, 127.68).contains(SeasonNotes.RAINY), "오키나와 5월 장마");
        assertTrue(SeasonNotes.of(LocalDate.of(2027, 9, 1), 33.59, 130.42).contains(SeasonNotes.TYPHOON));
        assertTrue(SeasonNotes.of(LocalDate.of(2027, 1, 20), 43.06, 141.35).contains(SeasonNotes.SNOW));
        assertTrue(SeasonNotes.of(LocalDate.of(2027, 1, 20), 36.58, 136.65).contains(SeasonNotes.SNOW), "가나자와");
        assertFalse(SeasonNotes.of(LocalDate.of(2027, 1, 20), 35.68, 139.77).contains(SeasonNotes.SNOW), "도쿄");
        assertTrue(SeasonNotes.of(LocalDate.of(2027, 11, 3), 35.68, 139.77).isEmpty());
    }

    @Test
    void datedTripsUpToAYearUndatedUpToThirtyDays() {
        PlanGenerationService service = new PlanGenerationService(planService, new PlanValidationService(null),
                null, null, null, null, null, null, null, null, null, null, null, null);
        PlanRequest dated = tokyoTrip(365, true).request;
        service.validate(dated);
        PlanRequest tooLong = tokyoTrip(366, true).request;
        IllegalArgumentException e = org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class, () -> service.validate(tooLong));
        assertTrue(e.getMessage().contains("365"));
        PlanRequest undated = tokyoTrip(31, true).request;
        undated.setStartDate(null);
        undated.setEndDate(null);
        undated.setTripDays(31);
        e = org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class, () -> service.validate(undated));
        assertTrue(e.getMessage().contains("날짜를 정하지 않은 일정은 최대 30일"), e.getMessage());
    }

    @Test
    void liveRevisitsFavouriteRestaurantsAfterAWeekButNotSights() {
        TripPlan plan = planService.planTrip(tokyoTrip(120, true));
        java.util.Map<String, List<Integer>> days = new java.util.HashMap<>();
        for (DayPlan d : plan.getDays()) {
            for (SimulatedItinerary i : d.getItems()) {
                if (i.getType() != Type.VISIT || i.getPlace() == null) continue;
                days.computeIfAbsent(i.getPlace().getPlaceId(), k -> new ArrayList<>()).add(d.getDayNumber());
            }
        }
        boolean anyRevisit = false;
        for (java.util.Map.Entry<String, List<Integer>> e : days.entrySet()) {
            List<Integer> list = e.getValue();
            if (list.size() < 2) continue;
            Place p = tokyoTrip(1, true).candidates.stream().filter(c -> c.getPlaceId().equals(e.getKey())).findFirst().orElseThrow();
            PlaceKind kind = PlaceKind.of(p);
            assertTrue(kind == PlaceKind.RESTAURANT || kind == PlaceKind.CAFE, "관광지는 다시 넣지 않는다: " + p.getName());
            for (int k = 1; k < list.size(); k++) assertTrue(list.get(k) - list.get(k - 1) >= 7, p.getName() + " " + list);
            anyRevisit = true;
        }
        assertTrue(anyRevisit, "넉 달 살아보기면 단골집이 생긴다");
        // 투어는 다시 넣지 않는다
        TripPlan tour = planService.planTrip(tokyoTrip(120, false));
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (DayPlan d : tour.getDays()) for (SimulatedItinerary i : d.getItems()) {
            if (i.getType() == Type.VISIT && i.getPlace() != null) assertTrue(seen.add(i.getPlace().getPlaceId()));
        }
    }

    @Test
    void manyFreeTimeWarningsAreCondensed() {
        List<String> warnings = new ArrayList<>();
        warnings.add("다른 안내");
        for (int d = 1; d <= 12; d++) warnings.add("Day " + d + ": 13:00~16:00 에 넣을 수 있는 장소가 부족해 자유 시간으로 두었습니다.");
        warnings.add("Day 13: 넣을 수 있는 장소가 부족해 17:00 이후는 자유 시간으로 두었습니다.");
        PlanService.condenseFreeTimeWarnings(warnings);
        assertEquals(2, warnings.size(), warnings.toString());
        assertTrue(warnings.get(1).startsWith("넣을 수 있는 장소가 부족해 자유 시간으로 둔 날이 13일 있습니다(1일차, 2일차"), warnings.get(1));
        List<String> few = new ArrayList<>(List.of("Day 1: 13:00~16:00 에 넣을 수 있는 장소가 부족해 자유 시간으로 두었습니다."));
        PlanService.condenseFreeTimeWarnings(few);
        assertEquals(1, few.size(), "5줄 이하면 그대로");
    }

    @Test
    void seasonNotesAreAddedOnlyBeyondTheForecastRange() {
        PlanRequest req = tokyoTrip(40, true).request;
        LocalDate today = LocalDate.now(java.time.ZoneId.of("Asia/Tokyo"));
        LocalDate start = LocalDate.of(today.getYear() + 1, 6, 1);
        req.setStartDate(start);
        req.setEndDate(start.plusDays(39));
        com.travel.planner.dto.AiRouteResponse response = new com.travel.planner.dto.AiRouteResponse();
        List<com.travel.planner.dto.AiRouteResponse.DaySummary> days = new ArrayList<>();
        for (int d = 1; d <= 40; d++) {
            com.travel.planner.dto.AiRouteResponse.DaySummary s = new com.travel.planner.dto.AiRouteResponse.DaySummary();
            s.setDay(d);
            s.setCity("도쿄");
            days.add(s);
        }
        response.setDays(days);
        List<String> warnings = new ArrayList<>();
        java.util.Map<String, double[]> centers = java.util.Map.of("도쿄", new double[]{35.68, 139.77});
        PlanGenerationService.addSeasonNotes(req, 40, null, centers, response, warnings);
        assertTrue(warnings.get(0).contains("날씨 예보 범위"), warnings.toString());
        assertTrue(warnings.stream().anyMatch(w -> w.startsWith("일정 중 장마철")));
        assertNull(days.get(0).getSeasonNote(), "6/1 은 장마 전");
        assertEquals("장마철 (평년 기준, 예보 아님)", days.get(19).getSeasonNote(), "6/20");
    }

    // ---------------------------------------------------------------- 준비

    private static int sightMinutes(DayPlan day) {
        return day.getItems().stream().filter(i -> i.getType() == Type.VISIT && i.getPlace() != null)
                .filter(i -> { PlaceKind k = PlaceKind.of(i.getPlace()); return k == PlaceKind.ATTRACTION || k == PlaceKind.SHOPPING || k == PlaceKind.THEME_PARK; })
                .mapToInt(i -> i.getEndMin() - i.getStartMin()).sum();
    }

    @Test
    void sightBudgetIsTimeNotCount() {
        // 예산 4시간: 20분짜리는 여러 곳, 2시간짜리는 두 곳, 첫 곳은 예산보다 길어도(테마파크) 넣고 그 뒤로는 막는다
        assertTrue(PlanService.fitsSightBudget(240, 3, 60, 20), "짧은 곳 네 번째도 들어간다");
        assertTrue(PlanService.fitsSightBudget(240, 1, 120, 120));
        assertFalse(PlanService.fitsSightBudget(240, 2, 240, 20), "예산을 다 쓰면 끝");
        assertTrue(PlanService.fitsSightBudget(240, 0, 0, 480), "첫 관광지는 테마파크라도 넣는다");
        assertFalse(PlanService.fitsSightBudget(240, 1, 480, 20), "테마파크 뒤로는 관광지를 넣지 않는다");
        assertFalse(PlanService.fitsSightBudget(0, 0, 0, 20), "여유일은 관광지 없음");
    }

    private static long sights(DayPlan day) {
        return day.getItems().stream().filter(i -> i.getType() == Type.VISIT && i.getPlace() != null)
                .filter(i -> { PlaceKind k = PlaceKind.of(i.getPlace()); return k == PlaceKind.ATTRACTION || k == PlaceKind.SHOPPING || k == PlaceKind.THEME_PARK; })
                .count();
    }

    /** 도쿄에 관광지·식당·카페·상점 256곳(3:3:2:1)을 격자로 뿌린 가상 도시 */
    static TripInput tokyoTrip(int days, boolean stayInCity) {
        PlanRequest req = new PlanRequest();
        req.setStartDate(LocalDate.of(2026, 11, 2));
        req.setEndDate(LocalDate.of(2026, 11, 2).plusDays(days - 1L));
        req.setCities(new ArrayList<>(List.of("도쿄")));
        req.setThemes(new ArrayList<>(List.of("문화", "맛집", "사진")));
        req.setCompanion("친구");
        req.setTransportation("도보 및 대중교통");
        req.setInCity("도쿄");
        req.setOutCity("도쿄");
        req.setInTime("오전");
        req.setOutTime("오후");
        req.setExcludeSuburbs(stayInCity);
        List<Place> places = new ArrayList<>();
        int n = 0;
        for (int i = 0; i < 16; i++) {
            for (int j = 0; j < 16; j++) {
                double lat = 35.60 + i * 0.012;
                double lng = 139.62 + j * 0.014;
                int k = (i * 16 + j) % 9;
                String id = "TK" + (n++);
                Place p;
                if (k <= 2) p = TestPlaces.place(id, "명소 " + id, "도쿄", lat, lng, "관광지", "문화,사진", TestPlaces.H_9_17);
                else if (k <= 5) p = TestPlaces.place(id, "식당 " + id, "도쿄", lat, lng, "식음", "맛집", TestPlaces.H_RESTAURANT);
                else if (k <= 7) p = TestPlaces.place(id, "카페 " + id, "도쿄", lat, lng, "식음", "카페", TestPlaces.H_CAFE);
                else p = TestPlaces.place(id, "상점 " + id, "도쿄", lat, lng, "쇼핑", "쇼핑", TestPlaces.H_SHOP);
                p.setRating(4.1);
                p.setUserRatingCount(800);
                places.add(p);
            }
        }
        TripInput in = new TripInput();
        in.request = req;
        in.candidates = places;
        Place hotel = TestPlaces.place("HOTEL_TOKYO", "도쿄 테스트 호텔", "도쿄", 35.69, 139.72, "숙소", null, null);
        for (int night = 1; night < days; night++) in.lodgingByNight.put(night, hotel);
        return in;
    }
}
