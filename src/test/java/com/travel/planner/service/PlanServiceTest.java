package com.travel.planner.service;

import com.travel.planner.dto.PlanRequest;
import com.travel.planner.entity.Place;
import com.travel.planner.service.PlanService.DayPlan;
import com.travel.planner.service.PlanService.SimulatedItinerary;
import com.travel.planner.service.PlanService.SimulatedItinerary.Type;
import com.travel.planner.service.PlanService.TripInput;
import com.travel.planner.service.PlanService.TripPlan;
import com.travel.planner.util.AirportDirectory;
import com.travel.planner.util.OpeningHours;
import com.travel.planner.util.TimeUtil;
import com.travel.planner.util.TravelTimeEstimator;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 일정 엔진 시나리오 테스트.
 * 스프링 컨텍스트 없이 PlanService 만으로 돌아간다 (DB·외부 API 불필요).
 * 모든 시나리오는 checkInvariants 로 "출국 마감 / 영업시간 / 고정 일정 / 시간 순서" 불변식을 함께 검사한다.
 */
class PlanServiceTest {

    private final PlanService planService = new PlanService();

    // 2026-07-02(목) ~ 07-05(일)
    private static final LocalDate START = LocalDate.of(2026, 7, 2);

    // ------------------------------------------------------------------ 시나리오

    @Test
    void afternoonDeparture_arrivesAtAirportBeforeDeadline() {
        TripInput in = osakaTrip(4, "오전", "오후", "맛집", "사진", "카페");
        TripPlan plan = planService.planTrip(in);
        checkInvariants(plan, in);

        // 오후(14:00) 출국 → 12:00 까지 공항 도착
        SimulatedItinerary last = lastItem(plan, 4);
        assertEquals(Type.END, last.getType());
        assertTrue(last.getStartMin() <= 12 * 60, "공항 도착 " + last.getTime());
        // 1일차는 착륙(10:00) + 90분 이후에 출발
        assertTrue(plan.getDays().get(0).getItems().get(0).getStartMin() >= 11 * 60 + 30);
    }

    @Test
    void morningDeparture_goesStraightToAirport() {
        TripInput in = osakaTrip(3, "오후", "오전", "관광", "맛집");
        TripPlan plan = planService.planTrip(in);
        checkInvariants(plan, in);

        DayPlan lastDay = plan.getDays().get(2);
        assertEquals(0, visits(lastDay).size(), "오전 출국일에는 방문이 없어야 한다");
        SimulatedItinerary airport = lastItem(plan, 3);
        assertTrue(airport.getStartMin() <= 8 * 60, "10:00 출국 → 08:00 까지 공항 도착, 실제 " + airport.getTime());
        // 숙소 출발 시각이 공항 도착보다 앞서야 한다 (이전에는 09:00 숙소 출발이 그대로 출력됐다)
        assertTrue(lastDay.getItems().get(0).getStartMin() < airport.getStartMin());
    }

    @Test
    void explicitFlightTimesAreUsed() {
        TripInput in = osakaTrip(3, "13:20", "18:30", "문화", "쇼핑");
        TripPlan plan = planService.planTrip(in);
        checkInvariants(plan, in);

        assertTrue(plan.getDays().get(0).getItems().get(0).getStartMin() >= 13 * 60 + 20 + 90);
        SimulatedItinerary airport = lastItem(plan, 3);
        assertTrue(airport.getStartMin() <= 16 * 60 + 30, "18:30 출국 → 16:30 까지 공항, 실제 " + airport.getTime());
        // 18:30 출국이면 마지막 날에도 오전~점심 일정이 들어가야 한다
        assertTrue(visits(plan.getDays().get(2)).size() >= 2, "마지막 날 방문 수 " + visits(plan.getDays().get(2)).size());
    }

    @Test
    void dayTripRespectsBothArrivalAndDeparture() {
        TripInput in = osakaTrip(1, "오전", "저녁", "관광");
        in.lodgingByNight.clear();
        TripPlan plan = planService.planTrip(in);
        checkInvariants(plan, in);

        List<SimulatedItinerary> items = plan.getDays().get(0).getItems();
        assertTrue(items.get(0).getStartMin() >= 11 * 60 + 30);
        assertTrue(items.get(items.size() - 1).getStartMin() <= 17 * 60, "19:00 출국 → 17:00 까지 공항");
    }

    @Test
    void fullDaysDoNotEndEarly() {
        TripInput in = osakaTrip(4, "오전", "오후", "관광", "맛집", "쇼핑");
        TripPlan plan = planService.planTrip(in);
        checkInvariants(plan, in);

        for (int day = 2; day <= 3; day++) {
            DayPlan d = plan.getDays().get(day - 1);
            int lastEnd = d.getItems().stream().filter(i -> i.getEndMin() != null).mapToInt(SimulatedItinerary::getEndMin).max().orElse(0);
            assertTrue(lastEnd >= 19 * 60, "Day " + day + " 마지막 활동 종료 " + TimeUtil.format(lastEnd));
            assertTrue(visits(d).size() >= 5, "Day " + day + " 방문 수 " + visits(d).size());
        }
    }

    @Test
    void everyRequestedThemeIsRepresented() {
        TripInput in = osakaTrip(4, "오전", "오후", "온천", "서브컬쳐", "야경");
        TripPlan plan = planService.planTrip(in);
        checkInvariants(plan, in);

        for (String theme : Arrays.asList("온천", "서브컬쳐", "야경")) {
            assertTrue(plan.getThemeCounts().getOrDefault(theme, 0) >= 1, theme + " 테마 방문 0건: " + plan.getThemeCounts());
        }
        // 야경을 요청하면 야경 명소는 해가 진 뒤(7월: 18:30 이후)에 배치된다
        for (DayPlan d : plan.getDays()) {
            for (SimulatedItinerary v : visits(d)) {
                if (v.getPlace().getTheme() != null && v.getPlace().getTheme().contains("야경")) {
                    assertTrue(v.getStartMin() >= 18 * 60 + 30, v.getPlace().getName() + " " + v.getTime());
                }
            }
        }
    }

    @Test
    void worksWhenDbThemesAreStillEmpty() {
        TripInput in = osakaTrip(3, "오전", "오후", "맛집", "문화", "쇼핑");
        for (Place p : in.candidates) {   // 인리치먼트가 한 번도 안 돈 DB
            p.setTheme(null);
            p.setPlaceType(null);
            p.setRecommendedDuration(null);
        }
        TripPlan plan = planService.planTrip(in);
        checkInvariants(plan, in);
        for (String theme : Arrays.asList("맛집", "문화", "쇼핑")) {
            assertTrue(plan.getThemeCounts().getOrDefault(theme, 0) >= 1, theme + " 추정 테마 방문 0건");
        }
    }

    @Test
    void fixedScheduleIsPlacedAndNeverOverlapped() {
        TripInput in = osakaTrip(3, "오전", "오후", "관광", "맛집");
        PlanRequest.FixedScheduleInput fixed = new PlanRequest.FixedScheduleInput();
        fixed.setName("공연 관람");
        fixed.setDayNumber(2);
        fixed.setStartTime(LocalTime.of(14, 0));
        fixed.setEndTime(LocalTime.of(16, 0));
        in.request.setFixedSchedules(new ArrayList<>(List.of(fixed)));

        TripPlan plan = planService.planTrip(in);
        checkInvariants(plan, in);

        List<SimulatedItinerary> day2 = plan.getDays().get(1).getItems();
        assertEquals(1, day2.stream().filter(i -> i.getType() == Type.FIXED).count());
        assertEquals(0, plan.getDays().get(0).getItems().stream().filter(i -> i.getType() == Type.FIXED).count());
        for (SimulatedItinerary v : day2) {
            if (v.getType() != Type.VISIT) continue;
            boolean overlaps = v.getStartMin() < 16 * 60 && v.getEndMin() > 14 * 60;
            assertFalse(overlaps, v.getPlace().getName() + " 이(가) 고정 일정과 겹침 " + v.getTime() + "~" + v.getEndTime());
        }
    }

    @Test
    void multiCityVisitsEveryCity() {
        TripInput in = osakaTrip(4, "오전", "오후", "문화", "자연");
        in.request.setCities(new ArrayList<>(List.of("오사카", "교토")));
        in.candidates.addAll(TestPlaces.kyoto());
        TripPlan plan = planService.planTrip(in);
        checkInvariants(plan, in);

        Set<String> visitedCities = new HashSet<>();
        for (DayPlan d : plan.getDays()) {
            for (SimulatedItinerary v : visits(d)) visitedCities.add(v.getPlace().getCity());
        }
        assertTrue(visitedCities.contains("오사카") && visitedCities.contains("교토"), "방문 도시 " + visitedCities);
        // 입·출국일(공항이 오사카 쪽)은 오사카, 가운데 날은 교토
        assertEquals("오사카", plan.getDays().get(0).getCity());
        assertEquals("오사카", plan.getDays().get(3).getCity());
        assertEquals("교토", plan.getDays().get(1).getCity());
    }

    @Test
    void worksWithoutHotelOrAirport() {
        TripInput in = osakaTrip(2, null, null, "관광");
        in.arrivalAirport = null;
        in.departureAirport = null;
        in.lodgingByNight.clear();
        TripPlan plan = planService.planTrip(in);
        checkInvariants(plan, in);
        for (DayPlan d : plan.getDays()) {
            assertTrue(visits(d).size() >= 5, "Day " + d.getDayNumber() + " 방문 수 " + visits(d).size());
            assertEquals(Type.VISIT, d.getItems().get(0).getType());
        }
    }

    @Test
    void scarcePoolProducesWarningsNotExceptions() {
        TripInput in = osakaTrip(3, "오전", "오후", "온천");
        in.candidates = new ArrayList<>(in.candidates.subList(0, 5));
        TripPlan plan = planService.planTrip(in);
        checkInvariants(plan, in);
        assertFalse(plan.getWarnings().isEmpty());
    }

    @Test
    void themeParkTakesMostOfTheDay() {
        TripInput in = osakaTrip(3, "오전", "오후", "액티비티");
        TripPlan plan = planService.planTrip(in);
        checkInvariants(plan, in);
        SimulatedItinerary usj = plan.getDays().stream().flatMap(d -> d.getItems().stream())
                .filter(i -> i.getPlace() != null && i.getPlace().getName().contains("유니버설")).findFirst().orElse(null);
        assertNotNull(usj, "액티비티 테마인데 USJ 가 빠짐");
        assertTrue(usj.getEndMin() - usj.getStartMin() >= 300, "USJ 체류 " + (usj.getEndMin() - usj.getStartMin()) + "분");
    }

    @Test
    void sameChainIsNotRepeatedAndNoAnchorsAsVisits() {
        TripInput in = osakaTrip(4, "오전", "오후", "쇼핑", "맛집");
        TripPlan plan = planService.planTrip(in);
        checkInvariants(plan, in);
        long donki = plan.getDays().stream().flatMap(d -> visits(d).stream())
                .filter(v -> v.getPlace().getName().startsWith("돈키호테")).count();
        assertTrue(donki <= 1, "돈키호테 지점 " + donki + "곳 방문");
    }

    @Test
    void fixedOrderSimulationReportsTheBrokenPlace() {
        TripInput in = osakaTrip(3, "오전", "오후", "관광");
        Place hotel = in.lodgingByNight.get(1);
        PlanService.DayContext ctx = planService.buildDayContext(in.request, 2, 3, hotel, hotel);

        Place castle = TestPlaces.find(in.candidates, "오사카 성");          // 09:00~17:00
        Place museum = TestPlaces.find(in.candidates, "난바 역사 박물관");   // 09:00~17:00, 월요일 휴무
        Place garden = TestPlaces.find(in.candidates, "덴노지 정원");        // 09:00~17:00

        PlanService.SimulationResult ok = planService.simulateFixedOrder(ctx, List.of(castle, museum), in.request);
        assertTrue(ok.isSuccess(), ok.getReason());
        assertEquals(4, ok.getValidRoute().size());   // 숙소 + 2곳 + 숙소

        // 17시에 닫는 곳 다섯 군데를 연달아 넣으면 뒤쪽이 영업시간을 넘긴다 → 반드시 실패로 보고되어야 한다
        List<Place> tooMany = new ArrayList<>(List.of(castle, museum, garden,
                TestPlaces.find(in.candidates, "우메다 미술관"), TestPlaces.find(in.candidates, "베이 수족관")));
        ctx = planService.buildDayContext(in.request, 2, 3, hotel, hotel);
        PlanService.SimulationResult broken = planService.simulateFixedOrder(ctx, tooMany, in.request);
        assertFalse(broken.isSuccess());
        assertNotNull(broken.getProblemPlace());
        assertNotNull(broken.getReason());
    }

    @Test
    void fixedOrderSimulationProtectsDepartureDeadline() {
        TripInput in = osakaTrip(3, "오전", "13:00", "관광");
        Place hotel = in.lodgingByNight.get(2);
        PlanService.DayContext ctx = planService.buildDayContext(in.request, 3, 3, hotel, in.departureAirport);
        // 13:00 출국 → 11:00 공항. 09:00 에 90분짜리 방문을 넣으면 공항 마감을 넘긴다.
        PlanService.SimulationResult r = planService.simulateFixedOrder(ctx,
                List.of(TestPlaces.find(in.candidates, "오사카 성")), in.request);
        assertFalse(r.isSuccess());
        assertTrue(r.getReason().contains("출국"), r.getReason());
    }

    @Test
    void dayCityAssignmentKeepsCitiesContiguous() {
        PlanRequest req = new PlanRequest();
        req.setStartDate(START);
        req.setEndDate(START.plusDays(5));
        req.setCities(List.of("오사카", "교토"));
        List<Place> all = new ArrayList<>(TestPlaces.osaka());
        all.addAll(TestPlaces.kyoto());
        List<String> cities = planService.assignDayCities(req, 6, planService.cityCenters(all, req.getCities()), null, null, new java.util.HashMap<>());
        assertEquals(List.of("오사카", "오사카", "오사카", "교토", "교토", "교토"), cities);
    }

    // ------------------------------------------------------------------ 공통 불변식

    // ------------------------------------------------------------------ 하루 식사 횟수 · 카페 제외

    /** 그날 식사로 잡힌 항목 수: 식당·이자카야 방문 + "자유 식사" 자리 */
    private static int mealsOn(DayPlan day) {
        int n = 0;
        for (SimulatedItinerary i : day.getItems()) {
            if (i.getType() == Type.MEAL) n++;
            if (i.getType() == Type.VISIT) {
                com.travel.planner.util.PlaceKind kind = com.travel.planner.util.PlaceKind.of(i.getPlace());
                if (kind == com.travel.planner.util.PlaceKind.RESTAURANT || kind == com.travel.planner.util.PlaceKind.BAR) n++;
            }
        }
        return n;
    }

    private static int cafesOn(DayPlan day) {
        return (int) visits(day).stream()
                .filter(v -> com.travel.planner.util.PlaceKind.of(v.getPlace()) == com.travel.planner.util.PlaceKind.CAFE).count();
    }

    @Test
    void mealCountOneGivesASingleDinner() {
        TripInput in = osakaTrip(3, "오전", "오후", "문화", "자연");
        in.request.setMealCount(1);
        TripPlan plan = planService.planTrip(in);
        checkInvariants(plan, in);
        for (DayPlan day : plan.getDays()) assertTrue(mealsOn(day) <= 1, "Day " + day.getDayNumber() + " 식사 " + mealsOn(day) + "회\n" + describe(plan));
        // 종일 일정인 2일차는 저녁 한 끼
        DayPlan full = plan.getDays().get(1);
        assertEquals(1, mealsOn(full), describe(plan));
        SimulatedItinerary dinner = full.getItems().stream().filter(i -> i.getType() == Type.MEAL || (i.getType() == Type.VISIT
                && com.travel.planner.util.PlaceKind.of(i.getPlace()) == com.travel.planner.util.PlaceKind.RESTAURANT)).findFirst().orElseThrow();
        assertTrue(dinner.getStartMin() >= 17 * 60, "저녁 시간대가 아님: " + dinner.getTime());
    }

    @Test
    void defaultMealCountIsLunchAndDinner() {
        TripInput base = osakaTrip(3, "오전", "오후", "문화", "자연");
        TripInput two = osakaTrip(3, "오전", "오후", "문화", "자연");
        two.request.setMealCount(2);
        assertEquals(describe(planService.planTrip(base)), describe(planService.planTrip(two)), "횟수를 보내지 않으면 2회와 같아야 한다");
        assertEquals(2, mealsOn(planService.planTrip(base).getDays().get(1)));
    }

    @Test
    void mealCountThreeAddsBreakfast() {
        TripInput in = osakaTrip(3, "오전", "오후", "문화", "자연");
        in.request.setMealCount(3);
        TripPlan plan = planService.planTrip(in);
        checkInvariants(plan, in);
        DayPlan full = plan.getDays().get(1);
        // 아침은 일찍 여는 카페나 식당, 없으면 "아침 식사" 자리로 들어간다 (09:00 출발 → 10:00 전에 시작)
        SimulatedItinerary first = full.getItems().get(1);
        boolean breakfast = first.getStartMin() < 10 * 60 && (first.getType() == Type.MEAL
                || com.travel.planner.util.PlaceKind.of(first.getPlace()).isFood());
        assertTrue(breakfast, "2일차 첫 일정이 아침 식사가 아님\n" + describe(plan));
        assertTrue(mealsOn(full) + cafesOn(full) >= 3, describe(plan));
    }

    @Test
    void mealCountFiveForFoodLovers() {
        TripInput in = osakaTrip(3, "오전", "오후", "맛집", "사진");
        in.request.setMealCount(5);
        TripPlan plan = planService.planTrip(in);
        checkInvariants(plan, in);
        DayPlan full = plan.getDays().get(1);
        assertTrue(mealsOn(full) >= 4, "2일차 식사 " + mealsOn(full) + "회 (아침은 카페일 수 있음)\n" + describe(plan));

        // 식사끼리는 최소 90분 간격
        for (DayPlan day : plan.getDays()) {
            int lastEnd = -1000;
            for (SimulatedItinerary i : day.getItems()) {
                boolean meal = i.getType() == Type.MEAL || (i.getType() == Type.VISIT && (
                        com.travel.planner.util.PlaceKind.of(i.getPlace()) == com.travel.planner.util.PlaceKind.RESTAURANT
                                || com.travel.planner.util.PlaceKind.of(i.getPlace()) == com.travel.planner.util.PlaceKind.BAR));
                if (!meal) continue;
                assertTrue(i.getStartMin() - lastEnd >= PlanService.MIN_MEAL_GAP_MIN, "Day " + day.getDayNumber() + " " + i.getDisplayName() + " " + i.getTime() + " 앞 식사와 너무 붙음");
                lastEnd = i.getEndMin();
            }
        }
    }

    @Test
    void mealCountAboveLimitIsClampedWithoutFoodTheme() {
        TripInput in = osakaTrip(3, "오전", "오후", "문화", "자연");
        in.request.setMealCount(5);   // 맛집 테마가 없으면 3회까지만
        assertEquals(3, PlanService.maxMealCount(in.request));
        assertEquals(3, PlanService.mealCount(in.request));
        in.request.setThemes(new ArrayList<>(List.of("맛집")));
        assertEquals(5, PlanService.maxMealCount(in.request));
    }

    @Test
    void excludeCafeRemovesCafesUnlessCafeThemeIsChosen() {
        TripInput in = osakaTrip(3, "오전", "오후", "문화", "자연");
        in.request.setExcludeCafe(true);
        TripPlan plan = planService.planTrip(in);
        checkInvariants(plan, in);
        for (DayPlan day : plan.getDays()) assertEquals(0, cafesOn(day), "Day " + day.getDayNumber() + " 에 카페가 들어감");

        // 카페 테마를 골랐으면 체크박스 값과 상관없이 카페가 들어간다 (종일 일정인 날마다 1곳 이상)
        TripInput cafe = osakaTrip(3, "오전", "오후", "카페", "자연");
        cafe.request.setExcludeCafe(true);
        TripPlan cafePlan = planService.planTrip(cafe);
        checkInvariants(cafePlan, cafe);
        assertTrue(cafesOn(cafePlan.getDays().get(1)) >= 1, describe(cafePlan));
        assertTrue(cafePlan.getThemeCounts().get("카페") >= 2, cafePlan.getThemeCounts().toString());
    }

    // ------------------------------------------------------------------ 실제 보고된 시즈오카 일정의 문제들

    @Test
    void shizuoka_hotelsStationsAndFarPlacesAreNotVisited() {
        TripInput in = shizuokaTrip();
        TripPlan plan = planService.planTrip(in);
        checkInvariants(plan, in);

        double[] center = planService.cityCenters(in.candidates, in.request.getCities()).get("시즈오카");
        int remoteVisits = 0;
        for (DayPlan day : plan.getDays()) {
            for (SimulatedItinerary v : visits(day)) {
                String name = v.getPlace().getName();
                assertFalse(name.contains("호텔") || name.contains("Hotel") || name.endsWith("역"), "숙소·역이 방문지로 들어감: " + name);
                assertFalse("Valor Fujimidai Shop".equals(name), "슈퍼마켓이 일정에 들어감");
                double km = com.travel.planner.util.DistanceUtil.calculateDistance(center[0], center[1], v.getLatitude(), v.getLongitude());
                assertTrue(km <= PlanService.MAX_DISTANCE_FROM_CENTER_KM, name + " 은 도심에서 " + Math.round(km) + "km");
                if (km > PlanService.NEAR_SEED_KM) remoteVisits++;
            }
        }
        // 외딴 온천은 "시내에서 온천 테마를 더 채울 수 없을 때" 여행 전체에서 한 번만, 그리고 경고와 함께
        assertTrue(remoteVisits <= 1, "외딴 장소 방문 " + remoteVisits + "회");
        if (remoteVisits == 1) {
            assertTrue(plan.getWarnings().stream().anyMatch(w -> w.contains("떨어진")), "외딴 장소를 넣었으면 알려야 한다: " + plan.getWarnings());
        }
        assertTrue(plan.getThemeCounts().get("온천") >= 1 && plan.getThemeCounts().get("자연") >= 3, plan.getThemeCounts().toString());
    }

    @Test
    void shizuoka_outdoorNatureSpotsAreVisitedInDaylight() {
        TripInput in = shizuokaTrip();
        TripPlan plan = planService.planTrip(in);
        int sunset = 17 * 60 + 30;   // 10월
        int checked = 0;
        for (DayPlan day : plan.getDays()) {
            for (SimulatedItinerary v : visits(day)) {
                Place p = v.getPlace();
                boolean outdoorNature = "관광지".equals(p.getCategory()) && p.getTheme() != null && p.getTheme().contains("자연");
                if (!outdoorNature) continue;
                checked++;
                assertTrue(v.getEndMin() <= sunset, "Day " + day.getDayNumber() + " " + p.getName() + " " + v.getTime() + "~" + v.getEndTime() + " (해 진 뒤)");
            }
        }
        assertTrue(checked >= 4, "자연 명소 방문 수 " + checked);
    }

    @Test
    void shizuoka_smallParksDoNotTakeTwoHours() {
        TripInput in = shizuokaTrip();
        Place park = TestPlaces.find(in.candidates, "도키와 공원");
        Place onsen = TestPlaces.find(in.candidates, "아오이 온천 쿠사나기노유");
        assertTrue(planService.calculateDwellTime(park, in.request) <= 60, "공원 체류 " + planService.calculateDwellTime(park, in.request));
        assertTrue(planService.calculateDwellTime(onsen, in.request) >= 90);
    }

    @Test
    void dinnerIsNotLeftInTheMiddleOfNowhere() {
        // 식당 데이터가 전혀 없는 근교(숙소에서 7~12km)만 도는 날: 저녁은 그 자리가 아니라 숙소 근처에서 먹는다.
        TripInput in = shizuokaTrip();
        in.candidates.removeIf(p -> !List.of("니혼다이라 유메테라스", "구노잔 도쇼구", "미호노 마쓰바라", "니혼다이라 동물원").contains(p.getName()));
        TripPlan plan = planService.planTrip(in);
        checkInvariants(plan, in);

        Place hotel = in.lodgingByNight.get(1);
        int dinners = 0;
        for (DayPlan day : plan.getDays()) {
            for (SimulatedItinerary item : day.getItems()) {
                if (item.getType() != Type.MEAL || !item.getDisplayName().startsWith("저녁")) continue;
                dinners++;
                assertTrue(hotel.getLatitude().equals(item.getLatitude()) && hotel.getLongitude().equals(item.getLongitude()),
                        "Day " + day.getDayNumber() + " " + item.getDisplayName() + " 위치가 숙소가 아님");
                assertTrue(item.getDisplayName().contains("숙소 근처"));
            }
        }
        assertTrue(dinners >= 1, describe(plan));
    }

    // ------------------------------------------------------------------ 불변식

    void checkInvariants(TripPlan plan, TripInput in) {
        PlanRequest req = in.request;
        int totalDays = planService.totalDays(req);
        assertEquals(totalDays, plan.getDays().size());
        Set<String> seen = new HashSet<>();
        Integer landing = TimeUtil.parseFlightTime(req.getInTime());
        Integer takeoff = TimeUtil.parseFlightTime(req.getOutTime());

        for (DayPlan day : plan.getDays()) {
            List<SimulatedItinerary> items = day.getItems();
            int clock = Integer.MIN_VALUE;
            Double lat = null;
            Double lng = null;

            for (SimulatedItinerary item : items) {
                String label = "Day " + day.getDayNumber() + " " + item.getDisplayName() + " " + item.getTime();

                // 시간 순서 + 이동 시간이 실제로 확보되어 있는가
                assertTrue(item.getStartMin() >= clock, label + " 시간이 거꾸로 감");
                if (lat != null && item.getLatitude() != null && item.getType() != Type.FREE && item.getType() != Type.MEAL) {
                    int need = TravelTimeEstimator.minutes(lat, lng, item.getLatitude(), item.getLongitude(), req.getTransportation());
                    assertTrue(item.getStartMin() - clock >= need || clock == Integer.MIN_VALUE,
                            label + " 이동 시간 부족 (필요 " + need + "분, 확보 " + (item.getStartMin() - clock) + "분)");
                }
                clock = item.getEndMin() != null ? item.getEndMin() : item.getStartMin();
                if (item.getLatitude() != null) {
                    lat = item.getLatitude();
                    lng = item.getLongitude();
                }

                if (item.getType() == Type.VISIT) {
                    Place p = item.getPlace();
                    assertTrue(seen.add(p.getPlaceId()), label + " 중복 방문");
                    assertFalse("숙소".equals(p.getCategory()) || "교통".equals(p.getCategory()), label + " 숙소/교통이 방문지로 들어감");

                    // 영업시간 안에서 시작하고 끝나는가
                    List<int[]> open = OpeningHours.intervalsOn(p, day.getDate());
                    if (open != null) {
                        boolean inside = open.stream().anyMatch(iv -> item.getStartMin() >= iv[0] && item.getEndMin() <= iv[1]);
                        assertTrue(inside, label + "~" + item.getEndTime() + " 영업시간 밖 (" + p.getOpeningHours() + ")");
                    }
                }
            }

            // 1일차: 착륙 + 90분 이전에 움직이지 않는다
            if (day.getDayNumber() == 1 && landing != null && !items.isEmpty()) {
                assertTrue(items.get(0).getStartMin() >= landing + 90, "Day 1 시작 " + items.get(0).getTime());
            }
            // 마지막 날: 출국 2시간 전까지 공항 도착
            if (day.getDayNumber() == totalDays && takeoff != null && in.departureAirport != null) {
                SimulatedItinerary end = items.get(items.size() - 1);
                assertEquals(Type.END, end.getType());
                assertEquals(in.departureAirport.getPlaceId(), end.getPlace().getPlaceId());
                boolean impossible = totalDays == 1 && landing != null && landing + 90 > takeoff - 120;
                assertTrue(impossible || end.getStartMin() <= takeoff - 120,
                        "공항 도착 " + end.getTime() + " > 마감 " + TimeUtil.format(takeoff - 120));
            }
        }
    }

    private static List<SimulatedItinerary> visits(DayPlan day) {
        return day.getItems().stream().filter(i -> i.getType() == Type.VISIT).collect(Collectors.toList());
    }

    private static SimulatedItinerary lastItem(TripPlan plan, int dayNumber) {
        List<SimulatedItinerary> items = plan.getDays().get(dayNumber - 1).getItems();
        return items.get(items.size() - 1);
    }

    /** 디버깅용: 계획을 사람이 읽을 수 있게 출력 */
    static String describe(TripPlan plan) {
        StringBuilder sb = new StringBuilder();
        for (DayPlan d : plan.getDays()) {
            sb.append("Day ").append(d.getDayNumber()).append(" (").append(d.getDate().getDayOfWeek()).append(", ").append(d.getCity()).append(")\n");
            for (SimulatedItinerary i : d.getItems()) {
                sb.append(String.format("  %s%s  %-6s %s%s%n", i.getTime(), i.getEndTime() != null ? "~" + i.getEndTime() : "      ",
                        i.getType(), i.getDisplayName(),
                        i.getPlace() != null && i.getPlace().getTheme() != null ? "  [" + i.getPlace().getTheme() + "]" : ""));
            }
        }
        sb.append("themes=").append(plan.getThemeCounts()).append('\n');
        for (String w : plan.getWarnings()) sb.append("! ").append(w).append('\n');
        return sb.toString();
    }

    // ------------------------------------------------------------------ 입력 생성

    /** 실제로 문제가 보고된 조건: 시즈오카 6일, 혼자, 힐링·온천·자연, 10월, 09:00~21:00 */
    static TripInput shizuokaTrip() {
        PlanRequest req = new PlanRequest();
        req.setStartDate(LocalDate.of(2026, 10, 10));
        req.setEndDate(LocalDate.of(2026, 10, 15));
        req.setCities(new ArrayList<>(List.of("시즈오카")));
        req.setThemes(new ArrayList<>(List.of("힐링", "온천", "자연")));
        req.setExcludedThemes(new ArrayList<>(List.of("쇼핑", "서브컬쳐")));
        req.setCompanion("혼자");
        req.setTransportation("도보 및 대중교통");
        req.setPreferredStartTime(java.time.LocalTime.of(9, 0));
        req.setPreferredEndTime(java.time.LocalTime.of(21, 0));
        req.setInCity("시즈오카");
        req.setOutCity("시즈오카");
        req.setInTime("오전");
        req.setOutTime("저녁");

        TripInput in = new TripInput();
        in.request = req;
        in.candidates = new ArrayList<>(TestPlaces.shizuoka());

        Place airport = TestPlaces.place("AIRPORT_FSZ", "시즈오카 공항", "시즈오카", 34.7961, 138.1894, "교통", null, null);
        in.arrivalAirport = airport;
        in.departureAirport = airport;
        Place hotel = TestPlaces.place("HOTEL_ASSOCIA", "Hotel Associa Shizuoka", "시즈오카", 34.9730, 138.3899, "숙소", null, null);
        for (int night = 1; night < 6; night++) in.lodgingByNight.put(night, hotel);
        return in;
    }

    static TripInput osakaTrip(int days, String inTime, String outTime, String... themes) {
        PlanRequest req = new PlanRequest();
        req.setStartDate(START);
        req.setEndDate(START.plusDays(days - 1L));
        req.setCities(new ArrayList<>(List.of("오사카")));
        req.setThemes(new ArrayList<>(Arrays.asList(themes)));
        req.setCompanion("친구");
        req.setTransportation("도보 및 대중교통");
        req.setInCity("오사카");
        req.setOutCity("오사카");
        req.setInTime(inTime);
        req.setOutTime(outTime);

        TripInput in = new TripInput();
        in.request = req;
        in.candidates = new ArrayList<>(TestPlaces.osaka());

        AirportDirectory.Airport kix = AirportDirectory.resolve("오사카", null).airport;
        Place airport = TestPlaces.place(kix.placeId(), kix.name, "오사카", kix.latitude, kix.longitude, "교통", null, null);
        in.arrivalAirport = airport;
        in.departureAirport = airport;

        Place hotel = TestPlaces.place("HOTEL_NAMBA", "난바 테스트 호텔", "오사카", 34.6660, 135.5020, "숙소", null, null);
        for (int night = 1; night < days; night++) in.lodgingByNight.put(night, hotel);
        return in;
    }
}
