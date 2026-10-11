package com.travel.planner.service;

import com.travel.planner.dto.AiRouteResponse;
import com.travel.planner.dto.PlanRequest;
import com.travel.planner.entity.Accommodation;
import com.travel.planner.entity.Place;
import com.travel.planner.entity.Plan;
import com.travel.planner.repository.PlaceRepository;
import com.travel.planner.repository.PlanRepository;
import com.travel.planner.repository.TrafficRepository;
import com.travel.planner.repository.TransitFeedRepository;
import com.travel.planner.repository.TransitLinkRepository;
import com.travel.planner.repository.TransitNameRepository;
import com.travel.planner.repository.TransitPatternRepository;
import com.travel.planner.repository.TransitStationRepository;
import com.travel.planner.repository.TransitStopRepository;
import com.travel.planner.service.PlanService.DayPlan;
import com.travel.planner.service.PlanService.SimulatedItinerary;
import com.travel.planner.service.PlanService.SimulatedItinerary.Type;
import com.travel.planner.service.PlanService.TripInput;
import com.travel.planner.service.PlanService.TripPlan;
import com.travel.planner.util.OpeningHours;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 날짜를 정하지 않고 "몇 박 며칠"(tripDays)만 정한 일정.
 * 날짜가 있어야만 되는 것(날씨·요일별 휴무·계절별 일몰)만 빠지고 나머지는 날짜를 정한 일정과 똑같이 동작해야 한다.
 */
class UndatedPlanTest {

    private final PlanService planService = new PlanService();
    private final PlanServiceTest scenarios = new PlanServiceTest();

    // ---------------------------------------------------------------- 기간을 정하는 두 가지 방식

    @Test
    void periodIsEitherDatesOrDayCount() {
        // 날짜를 정한 일정: 일수는 날짜에서 계산하고, 같이 온 tripDays 는 쓰지 않는다
        PlanRequest dated = request(LocalDate.of(2026, 10, 14), LocalDate.of(2026, 10, 17), 9);
        PlanGenerationService.normalizePeriod(dated);
        assertFalse(PlanService.isUndated(dated));
        assertNull(dated.getTripDays());
        assertEquals(4, planService.totalDays(dated));

        // 시작일 + 일수
        PlanRequest startAndDays = request(LocalDate.of(2026, 10, 14), null, 3);
        PlanGenerationService.normalizePeriod(startAndDays);
        assertEquals(LocalDate.of(2026, 10, 16), startAndDays.getEndDate());
        assertFalse(PlanService.isUndated(startAndDays));

        // 일수만: 날짜 미정
        PlanRequest undated = request(null, null, 4);
        PlanGenerationService.normalizePeriod(undated);
        assertTrue(PlanService.isUndated(undated));
        assertEquals(4, planService.totalDays(undated));
        assertEquals(Plan.UNDATED_BASE, PlanService.baseDate(undated));
        assertEquals(LocalDate.of(2026, 10, 14), PlanService.baseDate(dated));

        // 당일치기(1일)도 된다
        PlanRequest dayTrip = request(null, null, 1);
        PlanGenerationService.normalizePeriod(dayTrip);
        assertEquals(1, planService.totalDays(dayTrip));

        assertTrue(assertThrows(IllegalArgumentException.class, () -> PlanGenerationService.normalizePeriod(request(null, null, null)))
                .getMessage().contains("tripDays"));
        assertThrows(IllegalArgumentException.class, () -> PlanGenerationService.normalizePeriod(request(null, LocalDate.of(2026, 10, 17), null)));
        assertThrows(IllegalArgumentException.class, () -> PlanGenerationService.normalizePeriod(request(null, LocalDate.of(2026, 10, 17), 4)));
        assertThrows(IllegalArgumentException.class, () -> PlanGenerationService.normalizePeriod(request(LocalDate.of(2026, 10, 14), null, null)));
        assertThrows(IllegalArgumentException.class, () -> PlanGenerationService.normalizePeriod(request(null, null, 0)));
    }

    @Test
    void requestValidationWithoutDates() {
        PlanGenerationService service = new PlanGenerationService(planService, new PlanValidationService(null),
                null, null, null, null, null, null, null, null, null, null);

        // 29박 30일까지
        PlanRequest longest = request(null, null, 30);
        longest.setCities(new ArrayList<>(List.of("오사카")));
        service.validate(longest);
        PlanRequest tooLong = request(null, null, 31);
        tooLong.setCities(new ArrayList<>(List.of("오사카")));
        assertTrue(assertThrows(IllegalArgumentException.class, () -> service.validate(tooLong)).getMessage().contains("29박 30일"));

        // 숙소: 일차로 받아 자리 표시 날짜로 바꾼다 (1~3일차 숙박 = 1·2일차 밤)
        PlanRequest withLodging = request(null, null, 5);
        withLodging.setCities(new ArrayList<>(List.of("오사카")));
        PlanRequest.AccommodationInput first = lodging("난바 호텔", null, null, 1, 3);
        PlanRequest.AccommodationInput second = lodging("우메다 호텔", null, null, 3, 5);
        withLodging.setAccommodations(new ArrayList<>(List.of(first, second)));
        service.validate(withLodging);
        assertEquals(Plan.UNDATED_BASE, first.getCheckIn());
        assertEquals(Plan.UNDATED_BASE.plusDays(2), first.getCheckOut());
        assertEquals(Plan.UNDATED_BASE.plusDays(2), second.getCheckIn());
        assertEquals(Plan.UNDATED_BASE.plusDays(4), second.getCheckOut());

        // 숙소 기간을 비우면 여행 내내
        PlanRequest whole = request(null, null, 3);
        whole.setCities(new ArrayList<>(List.of("오사카")));
        PlanRequest.AccommodationInput all = lodging("난바 호텔", null, null, null, null);
        whole.setAccommodations(new ArrayList<>(List.of(all)));
        service.validate(whole);
        assertEquals(Plan.UNDATED_BASE, all.getCheckIn());
        assertEquals(Plan.UNDATED_BASE.plusDays(2), all.getCheckOut());

        // 날짜가 없는데 숙소·고정 일정에 날짜를 보내면 일차로 보내 달라고 알려 준다
        PlanRequest datedLodging = request(null, null, 3);
        datedLodging.setCities(new ArrayList<>(List.of("오사카")));
        datedLodging.setAccommodations(new ArrayList<>(List.of(lodging("난바 호텔", LocalDate.of(2026, 10, 14), LocalDate.of(2026, 10, 16), null, null))));
        assertTrue(assertThrows(IllegalArgumentException.class, () -> service.validate(datedLodging)).getMessage().contains("checkInDay"));

        PlanRequest overlapping = request(null, null, 4);
        overlapping.setCities(new ArrayList<>(List.of("오사카")));
        overlapping.setAccommodations(new ArrayList<>(List.of(lodging("A 호텔", null, null, 1, 3), lodging("B 호텔", null, null, 2, 4))));
        assertTrue(assertThrows(IllegalArgumentException.class, () -> service.validate(overlapping)).getMessage().contains("겹칩니다"));

        PlanRequest outOfRange = request(null, null, 3);
        outOfRange.setCities(new ArrayList<>(List.of("오사카")));
        outOfRange.setAccommodations(new ArrayList<>(List.of(lodging("A 호텔", null, null, 1, 5))));
        assertTrue(assertThrows(IllegalArgumentException.class, () -> service.validate(outOfRange)).getMessage().contains("일차"));

        PlanRequest datedFixed = request(null, null, 3);
        datedFixed.setCities(new ArrayList<>(List.of("오사카")));
        PlanRequest.FixedScheduleInput fixed = new PlanRequest.FixedScheduleInput();
        fixed.setName("공연");
        fixed.setStartTime(LocalTime.of(19, 0));
        fixed.setEndTime(LocalTime.of(21, 0));
        fixed.setDate(LocalDate.of(2026, 10, 15));
        datedFixed.setFixedSchedules(new ArrayList<>(List.of(fixed)));
        assertTrue(assertThrows(IllegalArgumentException.class, () -> service.validate(datedFixed)).getMessage().contains("dayNumber"));
        fixed.setDate(null);
        fixed.setDayNumber(2);
        service.validate(datedFixed);                 // 일차로 지정하면 된다

        // 날짜를 정한 일정은 예전과 똑같이 검증한다 (날짜로 받은 숙소)
        PlanRequest datedTrip = request(LocalDate.of(2026, 10, 14), LocalDate.of(2026, 10, 17), null);
        datedTrip.setCities(new ArrayList<>(List.of("오사카")));
        PlanRequest.AccommodationInput datedStay = lodging("난바 호텔", LocalDate.of(2026, 10, 14), LocalDate.of(2026, 10, 17), null, null);
        datedTrip.setAccommodations(new ArrayList<>(List.of(datedStay)));
        service.validate(datedTrip);
        assertEquals(LocalDate.of(2026, 10, 14), datedStay.getCheckIn());
    }

    // ---------------------------------------------------------------- 영업시간: 요일을 모를 때

    @Test
    void typicalOpeningHoursIgnoreTheWeeklyClosingDay() {
        Place museum = TestPlaces.place("M", "월요일에 쉬는 박물관", "오사카", 34.67, 135.50, "관광지", "문화",
                TestPlaces.weekClosedOn("오전 9:00 ~ 오후 5:00", "월요일"));
        assertTrue(OpeningHours.intervalsOn(museum, LocalDate.of(2026, 10, 12)).isEmpty(), "날짜를 알면 월요일 휴무를 지킨다");
        assertRanges("540-1020", OpeningHours.intervalsOn(museum, (LocalDate) null));
        assertRanges("540-1020", OpeningHours.typicalIntervals(museum));

        // 요일마다 다르면 가장 여러 요일에 해당하는 시간
        Place shop = TestPlaces.place("S", "주말에 늦게 여는 가게", "오사카", 34.67, 135.50, "쇼핑", "쇼핑",
                "월요일: 오전 10:00 ~ 오후 8:00 | 화요일: 오전 10:00 ~ 오후 8:00 | 수요일: 오전 10:00 ~ 오후 8:00 | 목요일: 오전 10:00 ~ 오후 8:00 "
                        + "| 금요일: 오전 10:00 ~ 오후 8:00 | 토요일: 오전 11:00 ~ 오후 9:00 | 일요일: 오전 11:00 ~ 오후 9:00");
        assertRanges("600-1200", OpeningHours.typicalIntervals(shop));

        // 주말에만 여는 곳: 여는 날의 시간을 쓴다 (휴무일은 세지 않는다)
        Place weekendOnly = TestPlaces.place("W", "주말 시장", "오사카", 34.67, 135.50, "쇼핑", "쇼핑",
                "월요일: 휴무일 | 화요일: 휴무일 | 수요일: 휴무일 | 목요일: 휴무일 | 금요일: 휴무일 | 토요일: 오전 9:00 ~ 오후 3:00 | 일요일: 오전 9:00 ~ 오후 3:00");
        assertRanges("540-900", OpeningHours.typicalIntervals(weekendOnly));

        // 구조화 영업시간(periods)도 같은 규칙: 일요일(0)만 쉬는 가게
        Place structured = TestPlaces.place("P", "일요일에 쉬는 식당", "오사카", 34.67, 135.50, "식음", "맛집", null);
        StringBuilder periods = new StringBuilder("[");
        for (int day = 1; day <= 6; day++) {
            if (day > 1) periods.append(',');
            periods.append("{\"open\":{\"day\":").append(day).append(",\"hour\":11,\"minute\":0},\"close\":{\"day\":").append(day).append(",\"hour\":21,\"minute\":30}}");
        }
        structured.setOpeningPeriods(periods.append("]").toString());
        assertTrue(OpeningHours.intervalsOn(structured, LocalDate.of(2026, 10, 18)).isEmpty());   // 일요일
        assertRanges("660-1290", OpeningHours.typicalIntervals(structured));

        // 정보가 없으면 없다고(null), 일주일 내내 쉬면 휴무(빈 목록)
        assertNull(OpeningHours.typicalIntervals(TestPlaces.place("N", "정보 없는 곳", "오사카", 34.67, 135.50, "관광지", "관광", null)));
        assertTrue(OpeningHours.typicalIntervals(TestPlaces.place("C", "문 닫은 곳", "오사카", 34.67, 135.50, "관광지", "관광",
                TestPlaces.week("휴무일"))).isEmpty());

        // 화면에 보여 줄 영업시간: 날짜를 알면 그 요일 한 줄, 모르면 요일별 전체
        assertEquals("월요일: 휴무일", OpeningHours.describe(museum, LocalDate.of(2026, 10, 12)));
        assertEquals(museum.getOpeningHours().trim(), OpeningHours.describe(museum, null));
        assertEquals(OpeningHours.LEGACY_DEFAULT, OpeningHours.describe(structured, null));
    }

    // ---------------------------------------------------------------- 엔진

    @Test
    void undatedTripIsPlannedLikeADatedOne() {
        TripInput in = undated(PlanServiceTest.osakaTrip(4, "오전", "오후", "맛집", "사진", "카페"));
        TripPlan plan = planService.planTrip(in);
        scenarios.checkInvariants(plan, in);

        assertEquals(4, plan.getDays().size());
        for (DayPlan day : plan.getDays()) assertNull(day.getDate(), "날짜를 정하지 않았으면 일자에 날짜가 없다");
        assertTrue(visits(plan) >= 12, "방문 수 " + visits(plan) + "\n" + describe(plan));
        // 마지막 날 오후(14:00) 출국 → 12:00 까지 공항
        List<SimulatedItinerary> last = plan.getDays().get(3).getItems();
        assertEquals(Type.END, last.get(last.size() - 1).getType());
        assertTrue(last.get(last.size() - 1).getStartMin() <= 12 * 60);
    }

    @Test
    void sameResultAsADatedTripWhenNothingDependsOnTheDate() {
        // 10월 14일(수)~17일(토): 일몰 기준이 날짜 미정일 때와 같고(17:30), 테스트 장소 가운데 이 요일에 쉬는 곳이 없다.
        // 날씨 예보도 없으므로 날짜를 정한 일정과 정하지 않은 일정이 한 줄도 다르지 않아야 한다.
        for (String[] themes : new String[][]{{"맛집", "사진", "카페"}, {"관광", "문화", "쇼핑"}, {"힐링", "자연", "야경"}}) {
            TripInput dated = PlanServiceTest.osakaTrip(4, "오전", "오후", themes);
            dated.request.setStartDate(LocalDate.of(2026, 10, 14));
            dated.request.setEndDate(LocalDate.of(2026, 10, 17));
            TripInput undated = undated(PlanServiceTest.osakaTrip(4, "오전", "오후", themes));

            assertEquals(lines(planService.planTrip(dated)), lines(planService.planTrip(undated)), String.join(",", themes));
        }
    }

    @Test
    void weeklyClosingDayAndSunsetFollowTheDateOnlyWhenThereIsOne() {
        // 월요일에 쉬는 박물관: 월요일 하루 일정에는 못 들어가지만, 날짜를 정하지 않은 일정에는 들어갈 수 있다
        TripInput monday = PlanServiceTest.osakaTrip(1, "미정", "미정", "문화");
        monday.lodgingByNight.clear();
        monday.request.setStartDate(LocalDate.of(2026, 10, 12));
        monday.request.setEndDate(LocalDate.of(2026, 10, 12));
        assertFalse(names(planService.planTrip(monday)).contains("난바 역사 박물관"), "월요일 휴무");

        TripInput anyDay = undated(PlanServiceTest.osakaTrip(1, "미정", "미정", "문화"));
        anyDay.lodgingByNight.clear();
        TripPlan plan = planService.planTrip(anyDay);
        scenarios.checkInvariants(plan, anyDay);
        assertTrue(names(plan).contains("난바 역사 박물관"), "요일을 모르면 정기 휴무를 적용하지 않는다\n" + describe(plan));
    }

    @Test
    void randomUndatedTripsKeepEveryInvariant() {
        Random random = new Random(20261011);
        String[] pool = {"맛집", "쇼핑", "관광", "힐링", "사진", "서브컬쳐", "문화", "자연", "야경", "온천", "액티비티", "카페"};
        String[] flights = {"오전", "오후", "저녁", "미정", "08:10", "11:40", "15:25", "20:30"};
        int planned = 0;
        for (int i = 0; i < 300; i++) {
            int days = i == 0 ? 30 : 1 + random.nextInt(i % 25 == 0 ? 14 : 6);
            List<String> themes = new ArrayList<>(Arrays.asList(pool));
            java.util.Collections.shuffle(themes, random);
            themes = themes.subList(0, 1 + random.nextInt(3));
            TripInput in = undated(PlanServiceTest.osakaTrip(days, flights[random.nextInt(flights.length)], flights[random.nextInt(flights.length)],
                    themes.toArray(new String[0])));
            if (random.nextInt(4) == 0) in.lodgingByNight.clear();
            if (random.nextInt(3) == 0) in.request.setMealCount(1 + random.nextInt(3));
            if (random.nextInt(5) == 0) {
                in.request.setPreferredStartTime(LocalTime.of(8 + random.nextInt(3), 0));
                in.request.setPreferredEndTime(LocalTime.of(19 + random.nextInt(4), 0));
            }
            if (random.nextInt(6) == 0 && days >= 2) {
                PlanRequest.FixedScheduleInput fixed = new PlanRequest.FixedScheduleInput();
                fixed.setName("예약한 공연");
                fixed.setStartTime(LocalTime.of(14, 0));
                fixed.setEndTime(LocalTime.of(15, 30));
                fixed.setDayNumber(2);
                in.request.setFixedSchedules(new ArrayList<>(List.of(fixed)));
            }
            TripPlan plan = planService.planTrip(in);
            scenarios.checkInvariants(plan, in);
            assertEquals(days, plan.getDays().size());
            for (DayPlan day : plan.getDays()) assertNull(day.getDate());
            planned++;
        }
        assertEquals(300, planned);
    }

    // ---------------------------------------------------------------- 응답·저장·복원·문서

    @Test
    void responseHasDayNumbersButNoDates() {
        TripInput in = undated(PlanServiceTest.osakaTrip(3, "오전", "오후", "관광", "맛집"));
        TripPlan plan = planService.planTrip(in);

        List<AiRouteResponse.DaySummary> days = PlanSummarizer.summarizeDays(plan.getDays());
        assertEquals(3, days.size());
        for (int i = 0; i < days.size(); i++) {
            assertEquals(i + 1, days.get(i).getDay());
            assertNull(days.get(i).getDate());
            assertNull(days.get(i).getDayOfWeek());
            assertNull(days.get(i).getBadWeather(), "예보를 보지 않는다");
            assertNotNull(days.get(i).getSummary());
        }
        assertTrue(PlanSummarizer.tripTitle(in.request, plan.getDays()).contains("2박 3일"));
        assertTrue(PlanSummarizer.tripOverview(in.request, plan.getDays()).contains("2박 3일"));

        // 일정 줄: 날짜가 없어도 조립되고, 영업시간은 요일별 전체가 나간다
        GoogleMapsService noLookup = new GoogleMapsService(null) {
            @Override
            public boolean refreshPlace(Place place, String lang) { return false; }
        };
        List<AiRouteResponse.TimelineItem> timeline = new TimelineAssembler(noLookup, null, routes()).toTimeline(plan.getDays(), "ko", "대중교통");
        assertFalse(timeline.isEmpty());
        AiRouteResponse.TimelineItem visit = timeline.stream().filter(t -> t.getPlaceId() != null && t.getOpeningHours().contains("월요일")).findFirst().orElse(null);
        assertNotNull(visit, "요일별 영업시간 전체가 나가는 줄이 있어야 한다");
        assertTrue(visit.getOpeningHours().contains("일요일"), visit.getOpeningHours());

        // 교통비 합계와 패스 추천도 날짜 없이 끝까지 돈다
        AiRouteResponse response = new AiRouteResponse();
        response.setReason("오사카 여행입니다.");
        response.setDays(days);
        response.setTimeline(timeline);
        TransitTotals.apply(response, false);
        new PassAdvisorService(FareMemory.bundledService(), names()).apply(response);
        assertNotNull(response.getPasses());
        for (com.travel.planner.dto.PassSuggestionDto pass : response.getPasses()) {
            assertNull(pass.getStartDate());
            assertNull(pass.getEndDate());
        }

        // 날씨: 날짜가 없으면 예보를 조회하지 않는다 (날씨 서비스를 건드리지 않고 빈 결과)
        PlanGenerationService generation = new PlanGenerationService(planService, new PlanValidationService(null),
                null, null, null, null, null, null, null, null, null, null);
        java.util.Map<String, double[]> centers = new java.util.HashMap<>();
        centers.put("오사카", new double[]{34.67, 135.50});
        assertTrue(generation.forecast(in.request, 3, Arrays.asList("오사카", "오사카", "오사카"), centers, "오사카").isEmpty());

        // 패스 추천: 날짜를 모르면 요일 조건이 있는 패스는 쓸 수 없다
        assertTrue(new com.travel.planner.transit.PassAdvisor.Pass().usableOn(null));
        com.travel.planner.transit.PassAdvisor.Pass weekendOnly = new com.travel.planner.transit.PassAdvisor.Pass();
        weekendOnly.dayRule = com.travel.planner.transit.PassAdvisor.DayRule.HOLIDAY;
        assertFalse(weekendOnly.usableOn(null));
        com.travel.planner.transit.PassAdvisor.Pass weekdayOnly = new com.travel.planner.transit.PassAdvisor.Pass();
        weekdayOnly.dayRule = com.travel.planner.transit.PassAdvisor.DayRule.WEEKDAY;
        assertFalse(weekdayOnly.usableOn(null));
    }

    @Test
    void undatedPlanIsSavedRestoredAndPrintedWithoutDates() {
        List<Plan> savedPlans = new ArrayList<>();
        PlanPersistenceService persistence = new PlanPersistenceService(fake(PlanRepository.class, savedPlans), fake(PlaceRepository.class, null),
                fake(TrafficRepository.class, null), routes());

        TripInput in = undated(PlanServiceTest.osakaTrip(4, "오전", "오후", "관광", "맛집"));
        TripPlan tripPlan = planService.planTrip(in);
        Plan plan = persistence.saveNewPlan(null, in.request, tripPlan.getDays(), new ArrayList<Accommodation>(), "요약");

        assertTrue(plan.undated());
        assertEquals(4, plan.getTripDays());
        assertEquals(4, plan.totalDays());
        assertEquals(Plan.UNDATED_BASE, plan.getStartDate(), "날짜 칸(NOT NULL)에는 자리 표시 날짜가 들어간다");
        assertEquals(Plan.UNDATED_BASE.plusDays(3), plan.getEndDate());
        assertNull(plan.dateOfDay(1));
        assertNull(plan.dateOfDay(4));
        assertFalse(plan.getItineraries().isEmpty());

        // 일정 일부 바꾸기·reroute 가 쓰는 복원: 날짜가 아니라 일수로 돌아온다
        PlanRequest restored = persistence.restoreRequest(plan);
        assertNull(restored.getStartDate());
        assertNull(restored.getEndDate());
        assertEquals(4, restored.getTripDays());
        assertTrue(PlanService.isUndated(restored));
        assertEquals(4, planService.totalDays(restored));
        assertNull(planService.buildDayContext(restored, 2, 4, null, null).getDate());

        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy년 MM월 dd일");
        assertEquals("3박 4일 (날짜 미정)", PdfExportService.periodLabel(plan, formatter));
        plan.setTripDays(1);
        assertEquals("당일치기 (날짜 미정)", PdfExportService.periodLabel(plan, formatter));

        // 날짜를 정한 일정은 그대로
        TripInput datedIn = PlanServiceTest.osakaTrip(3, "오전", "오후", "관광");
        Plan dated = persistence.saveNewPlan(null, datedIn.request, planService.planTrip(datedIn).getDays(), new ArrayList<Accommodation>(), "요약");
        assertFalse(dated.undated());
        assertNull(dated.getTripDays());
        assertEquals(LocalDate.of(2026, 7, 2), dated.getStartDate());
        assertEquals(LocalDate.of(2026, 7, 4), dated.getEndDate());
        assertEquals(3, dated.totalDays());
        assertEquals(LocalDate.of(2026, 7, 3), dated.dateOfDay(2));
        assertEquals("2026년 07월 02일 ~ 2026년 07월 04일", PdfExportService.periodLabel(dated, formatter));
        PlanRequest restoredDated = persistence.restoreRequest(dated);
        assertEquals(LocalDate.of(2026, 7, 2), restoredDated.getStartDate());
        assertNull(restoredDated.getTripDays());
        assertEquals(LocalDate.of(2026, 7, 3), planService.buildDayContext(restoredDated, 2, 3, null, null).getDate());
    }

    // ---------------------------------------------------------------- 준비

    private static TripInput undated(TripInput in) {
        PlanRequest req = in.request;
        int days = (int) java.time.temporal.ChronoUnit.DAYS.between(req.getStartDate(), req.getEndDate()) + 1;
        req.setStartDate(null);
        req.setEndDate(null);
        req.setTripDays(days);
        return in;
    }

    private static PlanRequest request(LocalDate start, LocalDate end, Integer tripDays) {
        PlanRequest req = new PlanRequest();
        req.setStartDate(start);
        req.setEndDate(end);
        req.setTripDays(tripDays);
        return req;
    }

    private static PlanRequest.AccommodationInput lodging(String name, LocalDate checkIn, LocalDate checkOut, Integer checkInDay, Integer checkOutDay) {
        PlanRequest.AccommodationInput acc = new PlanRequest.AccommodationInput();
        acc.setName(name);
        acc.setCheckIn(checkIn);
        acc.setCheckOut(checkOut);
        acc.setCheckInDay(checkInDay);
        acc.setCheckOutDay(checkOutDay);
        return acc;
    }

    private static void assertRanges(String expected, List<int[]> actual) {
        assertNotNull(actual);
        StringBuilder sb = new StringBuilder();
        for (int[] range : actual) sb.append(sb.length() > 0 ? "," : "").append(range[0]).append('-').append(range[1]);
        assertEquals(expected, sb.toString());
    }

    private static int visits(TripPlan plan) {
        int count = 0;
        for (DayPlan day : plan.getDays()) for (SimulatedItinerary i : day.getItems()) if (i.getType() == Type.VISIT) count++;
        return count;
    }

    private static List<String> names(TripPlan plan) {
        List<String> out = new ArrayList<>();
        for (DayPlan day : plan.getDays()) for (SimulatedItinerary i : day.getItems()) if (i.getType() == Type.VISIT) out.add(i.getDisplayName());
        return out;
    }

    /** 일정을 줄 단위 문자열로 (두 일정이 같은지 비교하는 데 쓴다) */
    private static List<String> lines(TripPlan plan) {
        List<String> out = new ArrayList<>();
        for (DayPlan day : plan.getDays()) {
            for (SimulatedItinerary i : day.getItems()) {
                out.add(day.getDayNumber() + " " + i.getTime() + "~" + i.getEndTime() + " " + i.getType() + " " + i.getDisplayName() + " +" + i.getTravelMinutes());
            }
        }
        return out;
    }

    private static String describe(TripPlan plan) {
        return String.join("\n", lines(plan)) + "\n" + plan.getWarnings();
    }

    private static TransitNameService names() {
        return new TransitNameService(empty(TransitNameRepository.class), empty(TransitStationRepository.class),
                empty(TransitStopRepository.class), empty(TransitPatternRepository.class), null);
    }

    private static RailRouteService routes() {
        TransitNameService names = names();
        return new RailRouteService(empty(TransitStationRepository.class), empty(TransitLinkRepository.class), empty(TransitStopRepository.class),
                empty(TransitPatternRepository.class), empty(TransitFeedRepository.class), names, FareMemory.emptyService());
    }

    private static <T> T empty(Class<T> type) {
        return fake(type, null);
    }

    /** 저장하면 받은 것을 그대로 돌려주고(저장한 Plan 은 목록에 담고), 조회하면 비어 있다고 답하는 가짜 저장소 */
    @SuppressWarnings("unchecked")
    private static <T> T fake(Class<T> type, List<Plan> savedPlans) {
        return (T) Proxy.newProxyInstance(UndatedPlanTest.class.getClassLoader(), new Class<?>[]{type}, (proxy, method, args) -> {
            switch (method.getName()) {
                case "findAll": return new ArrayList<>();
                case "findByPlaceId": return Optional.empty();
                case "save":
                    if (savedPlans != null && args[0] instanceof Plan) savedPlans.add((Plan) args[0]);
                    return args[0];
                case "saveAll": return args[0];
                default: return null;
            }
        });
    }
}
