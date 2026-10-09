package com.travel.planner.service;

import com.travel.planner.dto.AiRouteResponse;
import com.travel.planner.entity.Place;
import com.travel.planner.service.PlanService.DayPlan;
import com.travel.planner.service.PlanService.SimulatedItinerary;
import com.travel.planner.service.PlanService.TripInput;
import com.travel.planner.service.PlanService.TripPlan;
import com.travel.planner.util.PlaceDescriber;
import com.travel.planner.util.PlaceKind;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 장소 소개(세부 유형·한 줄 소개)와 일자별 개요·여행 요약 테스트. 스프링 컨텍스트 불필요. */
class PlanSummaryTest {

    private final PlanService planService = new PlanService();

    // ---------------------------------------------------------------- 장소 소개

    @Test
    void placeTypeIsReadFromNameWhenNothingIsStored() {
        assertEquals("라멘", PlaceDescriber.subTypeOf(place("고후쿠초 라멘", "식음")));
        assertEquals("라멘 전문점", PlaceDescriber.summaryOf(place("고후쿠초 라멘", "식음")));
        assertEquals("장어", PlaceDescriber.subTypeOf(place("우나기 간타로", "식음")));
        assertEquals("카페", PlaceDescriber.subTypeOf(place("블루보틀 커피", "식음")));
        assertEquals("식당", PlaceDescriber.subTypeOf(place("마루야마", "식음")));
        assertEquals("현지 식당", PlaceDescriber.summaryOf(place("마루야마", "식음")));

        assertEquals("공원", PlaceDescriber.subTypeOf(place("슨푸 성 공원", "관광지")), "성 공원은 성이 아니라 공원이다");
        assertEquals("성", PlaceDescriber.subTypeOf(place("오사카 성", "관광지")));
        assertEquals("성", PlaceDescriber.subTypeOf(place("하마마쓰성", "관광지")));
        assertEquals("신사", PlaceDescriber.subTypeOf(place("구노잔 도쇼구", "관광지")));
        assertEquals("미술관", PlaceDescriber.subTypeOf(place("시즈오카 시립 미술관", "관광지")));
        assertEquals("온천", PlaceDescriber.subTypeOf(place("아오이 온천 쿠사나기노유", "관광지")));
        assertEquals("쇼핑몰", PlaceDescriber.subTypeOf(place("덴포잔 쇼핑몰", "쇼핑")));
        assertEquals("관광 명소", PlaceDescriber.subTypeOf(place("어딘가", "관광지")));
    }

    @Test
    void lodgingAndTransportHaveATypeButNoSummary() {
        assertEquals("숙소", PlaceDescriber.subTypeOf(place("Hotel Associa Shizuoka", "숙소")));
        assertNull(PlaceDescriber.summaryOf(place("Hotel Associa Shizuoka", "숙소")));
        assertEquals("공항", PlaceDescriber.subTypeOf(place("간사이 국제공항", "교통")));
        assertNull(PlaceDescriber.summaryOf(place("간사이 국제공항", "교통")));
    }

    @Test
    void storedDescriptionWinsOverTheFallback() {
        Place garden = place("슨푸성 모미지야마 정원", "관광지");
        garden.setSubType("일본식 정원");
        garden.setSummary("연못을 따라 산책로가 이어지는 일본식 정원");
        assertEquals("일본식 정원", PlaceDescriber.subTypeOf(garden));
        assertEquals("연못을 따라 산책로가 이어지는 일본식 정원", PlaceDescriber.summaryOf(garden));
        assertTrue(PlaceDescriber.hasStoredSummary(garden));
        assertFalse(PlaceDescriber.hasStoredSummary(place("도키와 공원", "관광지")));
    }

    // ---------------------------------------------------------------- AI 응답 해석

    @Test
    void enrichmentAnswerWithFiveFieldsCarriesTypeAndSummary() {
        Place p = place("어딘가 온천", "관광지");
        AdminAsyncService.ParsedAttributes parsed =
                AdminAsyncService.parseAttributes("온천,힐링|실외|120| 당일 온천 | \"노천탕에서 바다를 보며 쉬는 당일 온천\" ", p);
        assertEquals("온천,힐링", parsed.theme);
        assertEquals(120, parsed.duration);
        assertEquals("당일 온천", parsed.subType);
        assertEquals("노천탕에서 바다를 보며 쉬는 당일 온천", parsed.summary);

        // 뒤 칸이 없어도(예전 형식) 앞 칸은 그대로 쓴다
        AdminAsyncService.ParsedAttributes old = AdminAsyncService.parseAttributes("온천,힐링|실외|120", p);
        assertEquals("온천,힐링", old.theme);
        assertNull(old.subType);
        assertNull(old.summary);

        // 세부 유형 칸에 문장을 넣은 응답: 유형은 버리고 나머지는 살린다
        AdminAsyncService.ParsedAttributes longType =
                AdminAsyncService.parseAttributes("온천|실내|90|이곳은 오래된 온천 마을에 있는 아주 유명한 온천 시설|당일 온천", p);
        assertEquals("온천", longType.theme);
        assertNull(longType.subType);
        assertEquals("당일 온천", longType.summary);

        // 소개 안에 구분자가 섞여 있어도 잘리지 않는다
        assertEquals("전시 관람 체험 공방", AdminAsyncService.parseAttributes("문화|실내|90|박물관|전시 관람|체험 공방", p).summary);
    }

    @Test
    void descriptionOnlyAnswerIsParsed() {
        String[] parsed = AdminAsyncService.parseDescription(" 라멘 | 진한 돈코츠 국물의 라멘을 내는 식당 ");
        assertEquals("라멘", parsed[0]);
        assertEquals("진한 돈코츠 국물의 라멘을 내는 식당", parsed[1]);

        String[] sentenceOnly = AdminAsyncService.parseDescription("산책하기 좋은 강변 공원");
        assertNull(sentenceOnly[0]);
        assertEquals("산책하기 좋은 강변 공원", sentenceOnly[1]);

        assertNull(AdminAsyncService.parseDescription(null));
        assertNull(AdminAsyncService.parseDescription(""));
        assertNull(AdminAsyncService.parseDescription("라멘|"));
        assertNull(AdminAsyncService.parseDescription("라멘|없음"));

        String tooLong = "가".repeat(300);
        assertTrue(AdminAsyncService.parseDescription("공원|" + tooLong)[1].length() <= PlaceDescriber.MAX_SUMMARY_LENGTH + 1,
                "DB 칸(300자)을 넘지 않도록 잘라 저장한다");
    }

    // ---------------------------------------------------------------- 조사

    @Test
    void particlesFollowTheFinalConsonant() {
        assertEquals("을", PlanSummarizer.josa("공원", "을", "를"));
        assertEquals("를", PlanSummarizer.josa("신사", "을", "를"));
        assertEquals("로", PlanSummarizer.josa("교토", "으로", "로"));
        assertEquals("으로", PlanSummarizer.josa("도쿄돔", "으로", "로"));
        assertEquals("로", PlanSummarizer.josa("하코다테 호텔", "으로", "로"));   // ㄹ 받침은 '로'
        assertEquals("를", PlanSummarizer.josa("Park", "을", "를"));
    }

    // ---------------------------------------------------------------- 일자별 개요

    @Test
    void everyDayGetsAnOverviewThatMatchesItsTimeline() {
        TripInput in = PlanServiceTest.osakaTrip(4, "오전", "오후", "문화", "자연");
        TripPlan plan = planService.planTrip(in);
        List<AiRouteResponse.DaySummary> days = PlanSummarizer.summarizeDays(plan.getDays());

        assertEquals(4, days.size());
        assertTrue(days.get(0).getTitle().contains("도착"), days.get(0).getTitle());
        assertTrue(days.get(3).getTitle().contains("출국일"), days.get(3).getTitle());

        for (int i = 0; i < days.size(); i++) {
            AiRouteResponse.DaySummary summary = days.get(i);
            DayPlan day = plan.getDays().get(i);
            List<SimulatedItinerary> visits = day.getItems().stream()
                    .filter(it -> it.getType() == SimulatedItinerary.Type.VISIT).collect(Collectors.toList());

            assertEquals(day.getDayNumber(), summary.getDay());
            assertEquals(day.getDate().toString(), summary.getDate());
            assertEquals("오사카", summary.getCity());
            assertEquals(visits.size(), summary.getVisitCount());
            assertEquals(day.getItems().stream().mapToInt(SimulatedItinerary::getTravelMinutes).sum(), summary.getTravelMinutes());
            assertEquals(day.getItems().get(0).getTime(), summary.getStartTime());
            assertNotNull(summary.getSummary());
            assertTrue(summary.getSummary().length() > 20, summary.getSummary());
            assertFalse(summary.getSummary().contains("null"), summary.getSummary());

            // 주요 방문지는 그 날 실제로 가는 관광지(식당·카페 제외)여야 한다
            List<String> sightNames = visits.stream().filter(v -> !PlaceKind.of(v.getPlace()).isFood())
                    .map(v -> v.getPlace().getName()).collect(Collectors.toList());
            assertTrue(summary.getHighlights().size() <= 3);
            for (String name : summary.getHighlights()) {
                assertTrue(sightNames.contains(name), name + " 은(는) " + summary.getDay() + "일차 방문지가 아니다");
                assertTrue(summary.getSummary().contains(name), "개요 본문에 주요 방문지 이름이 있어야 한다");
            }
        }
    }

    @Test
    void tripOverviewNamesCitiesLengthAndRoute() {
        TripInput in = PlanServiceTest.sparseSecondCityTrip();
        TripPlan plan = planService.planTrip(in);

        String title = PlanSummarizer.tripTitle(in.request, plan.getDays());
        assertEquals("오사카·교토 3박 4일 맛집·서브컬쳐 여행", title);

        String overview = PlanSummarizer.tripOverview(in.request, plan.getDays());
        assertTrue(overview.startsWith("친구와 함께하는 오사카·교토 3박 4일 여행입니다."), overview);
        assertTrue(overview.contains("오사카(1일차) → 교토(2~3일차) → 오사카(4일차)"), overview);
        assertTrue(overview.contains("난바 테스트 호텔(1일차 숙박), 교토 테스트 호텔(2~3일차 숙박)"), overview);
        assertTrue(overview.contains("간사이 국제공항"), overview);
        assertFalse(overview.contains("null"), overview);

        // 도시가 바뀌는 날은 제목과 본문에 이동이 드러난다
        AiRouteResponse.DaySummary moving = PlanSummarizer.summarizeDays(plan.getDays()).get(1);
        assertTrue(moving.getTitle().startsWith("오사카 → 교토"), moving.getTitle());
        assertTrue(moving.getSummary().startsWith("오사카에서 교토로 넘어가"), moving.getSummary());
    }

    @Test
    void mealsAreNamedByKindInTheDayOverview() {
        TripInput in = PlanServiceTest.osakaTrip(3, "오전", "오후", "맛집", "사진");
        in.request.setMealCount(3);
        TripPlan plan = planService.planTrip(in);
        DayPlan fullDay = plan.getDays().get(1);
        AiRouteResponse.DaySummary summary = PlanSummarizer.summarizeDays(plan.getDays()).get(1);

        long foodVisits = fullDay.getItems().stream()
                .filter(it -> it.getType() == SimulatedItinerary.Type.VISIT && PlaceKind.of(it.getPlace()).isFood()).count();
        long freeMeals = fullDay.getItems().stream().filter(it -> it.getType() == SimulatedItinerary.Type.MEAL).count();
        assertEquals((int) (foodVisits + freeMeals), summary.getMealCount());
        assertTrue(summary.getSummary().contains("식사는 "), summary.getSummary());
        assertTrue(summary.getSummary().contains("점심 "), summary.getSummary());
        assertTrue(summary.getSummary().contains("저녁 "), summary.getSummary());
    }

    @Test
    void visitDescriptionSaysWhatThePlaceIs() {
        TripPlan plan = planService.planTrip(PlanServiceTest.shizuokaTrip());
        List<String> seen = new ArrayList<>();
        for (DayPlan day : plan.getDays()) {
            for (SimulatedItinerary item : day.getItems()) {
                if (item.getType() != SimulatedItinerary.Type.VISIT) continue;
                String description = TimelineAssembler.describe(item);
                assertNotNull(description);
                assertFalse(description.isBlank());
                assertFalse(description.endsWith(" 일정"), item.getDisplayName() + ": 테마만 적힌 설명(" + description + ")이 남아 있다");
                assertNotNull(PlaceDescriber.subTypeOf(item.getPlace()));
                seen.add(description);
            }
        }
        assertFalse(seen.isEmpty());

        // AI 가 채운 소개가 있으면 그것이 그대로 나간다
        SimulatedItinerary first = plan.getDays().get(0).getItems().stream()
                .filter(it -> it.getType() == SimulatedItinerary.Type.VISIT).findFirst().orElseThrow();
        first.getPlace().setSummary("테스트용 소개 문장");
        assertEquals("테스트용 소개 문장", TimelineAssembler.describe(first));
    }

    private static Place place(String name, String category) {
        Place p = new Place();
        p.setName(name);
        p.setCategory(category);
        return p;
    }
}
