package com.travel.planner.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.travel.planner.dto.AiRouteResponse;
import com.travel.planner.dto.PassSuggestionDto;
import com.travel.planner.dto.TransitLegDto;
import com.travel.planner.entity.TransportPass;
import com.travel.planner.transit.PassAdvisor;
import com.travel.planner.transit.RailCategory;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 일정 응답에 패스 추천을 붙이는 부분: 구간별 요금 정보 모으기, 일차·날짜·시각 채우기, 응답 형식. */
class PassAdviceTest {

    private static final String METRO = "東京地下鉄";
    private static final String TOEI = "東京都";

    @Test
    void tokyoDayWithManySubwayRidesGetsTheSubwayTicket() {
        FareDataService fareData = FareMemory.bundledService();
        AiRouteResponse response = tokyoTrip();

        PassAdvice.apply(response, fareData.passes(), fareData::passRow, line -> line.equals("3号線銀座線") ? "긴자선" : line);

        assertEquals(1, response.getPasses().size());
        PassSuggestionDto pass = response.getPasses().get(0);
        // 1일차에 메트로 3번 + 도에이 2번 = 1,160엔. 공통 1일권(1,100엔)·24시간권(1,000엔) 중 24시간권이 가장 이득이고,
        // 24시간 안에 드는 2일차 아침의 한 번(180엔)까지 덮는다
        assertEquals("tokyo-subway-ticket-24h", pass.getKey());
        assertEquals("Tokyo Subway Ticket 24시간권", pass.getName());
        assertEquals("도쿄", pass.getCity());
        assertEquals(1000, pass.getPriceYen());
        assertEquals(500, pass.getChildPriceYen());
        assertEquals("24시간", pass.getValidity());
        assertEquals(List.of(1, 2), pass.getDays());
        assertEquals("2026-10-13", pass.getStartDate());
        assertEquals("2026-10-14", pass.getEndDate());
        assertEquals("09:40", pass.getStartTime(), "첫 승차의 출발 시각 = 도착 10:00 − 이동 20분");
        assertEquals(1340, pass.getCoveredFareYen());
        assertEquals(340, pass.getSavingYen());
        assertEquals(6, pass.getRideCount());
        assertFalse(pass.isEstimated());
        assertTrue(pass.getLines().contains("긴자선"), "노선 이름은 화면 표기로: " + pass.getLines());
        assertTrue(pass.getConditions().contains("방일 외국인"), pass.getConditions());
        assertEquals("2026-10-11", pass.getVerifiedAt());
        assertTrue(pass.getUrl().startsWith("https://"));
        assertEquals("1일차 09:40부터 24시간 동안 쓰면 1,340엔어치(6회)를 1,000엔에 타서 약 340엔을 아낍니다.", pass.getSummary());

        assertEquals(1520 - 340, response.getTransitFareWithPassesYen());
        assertTrue(response.getReason().contains("Tokyo Subway Ticket 24시간권") && response.getReason().contains("340엔"), response.getReason());
    }

    @Test
    void noSuggestionLeavesAnEmptyListAndKeepsTheSummary() {
        FareDataService fareData = FareMemory.bundledService();
        AiRouteResponse response = tokyoTrip();
        // 승차를 두 번만 남긴다: 어떤 패스도 이득이 아니다
        response.setTimeline(new ArrayList<>(response.getTimeline().subList(0, 2)));
        String before = response.getReason();

        PassAdvice.apply(response, fareData.passes(), fareData::passRow, null);

        assertNotNull(response.getPasses());
        assertTrue(response.getPasses().isEmpty());
        assertNull(response.getTransitFareWithPassesYen());
        assertEquals(before, response.getReason());
    }

    @Test
    void withoutRegisteredPassesNothingHappens() {
        AiRouteResponse response = tokyoTrip();
        PassAdvice.apply(response, new ArrayList<>(), key -> null, null);
        assertTrue(response.getPasses().isEmpty());
        PassAdvice.apply(response, null, null, null);
        assertTrue(response.getPasses().isEmpty());
        PassAdvice.apply(null, new ArrayList<>(), null, null);   // 예외 없이 끝나야 한다

        // 서비스로 불러도 같다 (요금 자료가 비어 있는 DB)
        PassAdvisorService service = new PassAdvisorService(FareMemory.emptyService(), null);
        AiRouteResponse again = tokyoTrip();
        service.apply(again);
        assertTrue(again.getPasses().isEmpty());
    }

    @Test
    void weekdayPassIsNotSuggestedOnAHoliday() {
        FareDataService fareData = FareMemory.bundledService();
        // 2026-10-12 는 월요일이지만 공휴일(스포츠의 날): 오사카 엔조이 에코 카드는 평일권(820엔)이 아니라 휴일권(620엔)
        AiRouteResponse response = osakaDay("2026-10-12");
        PassAdvice.apply(response, fareData.passes(), fareData::passRow, null);
        assertEquals("osaka-enjoy-eco-holiday", response.getPasses().get(0).getKey());
        assertEquals(960 - 620, response.getPasses().get(0).getSavingYen());

        response = osakaDay("2026-10-13");
        PassAdvice.apply(response, fareData.passes(), fareData::passRow, null);
        assertEquals("osaka-enjoy-eco-weekday", response.getPasses().get(0).getKey());
        assertEquals(960 - 820, response.getPasses().get(0).getSavingYen());

        // 날짜를 모르면 요일 조건이 있는 패스는 권하지 않는다
        response = osakaDay(null);
        PassAdvice.apply(response, fareData.passes(), fareData::passRow, null);
        assertTrue(response.getPasses().isEmpty());
    }

    @Test
    void estimatedFaresAreMentionedInTheSummary() {
        FareDataService fareData = FareMemory.bundledService();
        AiRouteResponse response = tokyoTrip();
        for (AiRouteResponse.TimelineItem item : response.getTimeline()) {
            if (item.getTransit() != null) item.getTransit().getFareRides().forEach(r -> r.estimated = true);
        }
        PassAdvice.apply(response, fareData.passes(), fareData::passRow, null);
        PassSuggestionDto pass = response.getPasses().get(0);
        assertTrue(pass.isEstimated());
        assertTrue(pass.getSummary().contains("추정"), pass.getSummary());
    }

    @Test
    void approximateCoverageIsFlagged() {
        PassAdvisor.Pass pass = PassAdvisorTestSupport.dayPass("area-pass", 500, METRO);
        TransportPass row = new TransportPass();
        row.setPassKey("area-pass");
        row.setApproximate(true);
        row.setNote("경계를 대략 잡았습니다.");
        AiRouteResponse response = tokyoTrip();
        PassAdvice.apply(response, List.of(pass), key -> row, null);
        PassSuggestionDto dto = response.getPasses().get(0);
        assertTrue(dto.isApproximateCoverage());
        assertEquals("경계를 대략 잡았습니다.", dto.getNote());
        assertTrue(dto.getSummary().startsWith("1일차에 쓰면 ") && dto.getSummary().contains("구입 전에 확인"), dto.getSummary());
        assertEquals("1일", dto.getValidity());
        assertNull(dto.getStartTime(), "일수권에는 시작 시각이 없다");
    }

    @Test
    void validityLabels() {
        PassAdvisor.Pass pass = PassAdvisorTestSupport.dayPass("p", 500, METRO);
        pass.days = 3;
        assertEquals("연속 3일", PassAdvice.validity(pass));
        pass.consecutive = false;
        assertEquals("3일", PassAdvice.validity(pass));
        pass.days = 0;
        pass.hours = 72;
        assertEquals("72시간", PassAdvice.validity(pass));
    }

    /** 패스 계산용 정보는 응답 JSON 에 나가지 않아야 한다 (운영사 원문·좌표가 든 내부 자료다) */
    @Test
    void internalFareRidesAreNotSerialized() throws Exception {
        TransitLegDto leg = leg(20, 180, METRO, RailCategory.SUBWAY, "3号線銀座線");
        String json = new ObjectMapper().writeValueAsString(leg);
        assertFalse(json.contains("fareRides"), json);
        assertFalse(json.contains(METRO), json);
        assertTrue(json.contains("\"fareYen\":180"), json);
    }

    // ---------------------------------------------------------------- 가상 일정

    /**
     * 1일차(2026-10-13 화): 지하철을 다섯 번 탄다. 2일차 아침에 한 번 더 타고 끝.
     * 요금은 모두 운영사 요금표 기준(추정 아님)이라고 둔다.
     */
    private static AiRouteResponse tokyoTrip() {
        AiRouteResponse response = new AiRouteResponse();
        response.setReason("도쿄 2일 여행입니다.");
        List<AiRouteResponse.DaySummary> days = new ArrayList<>();
        days.add(day(1, "2026-10-13"));
        days.add(day(2, "2026-10-14"));
        response.setDays(days);

        List<AiRouteResponse.TimelineItem> timeline = new ArrayList<>();
        timeline.add(item(1, "09:00", null));
        timeline.add(item(1, "10:00", leg(20, 180, METRO, RailCategory.SUBWAY, "3号線銀座線")));
        timeline.add(item(1, "12:00", leg(25, 260, METRO, RailCategory.SUBWAY, "4号線丸ノ内線")));
        timeline.add(item(1, "14:00", leg(15, 220, TOEI, RailCategory.SUBWAY, "12号線大江戸線")));
        timeline.add(item(1, "16:30", leg(20, 220, TOEI, RailCategory.SUBWAY, "1号線浅草線")));
        timeline.add(item(1, "19:00", leg(30, 280, METRO, RailCategory.SUBWAY, "2号線日比谷線")));
        timeline.add(item(2, "09:00", null));
        timeline.add(item(2, "09:30", leg(15, 180, METRO, RailCategory.SUBWAY, "3号線銀座線")));
        timeline.add(item(2, "13:00", leg(10, 0, null, null, null)));       // 도보
        timeline.add(item(2, "15:00", leg(20, 180, METRO, RailCategory.SUBWAY, "3号線銀座線")));   // 24시간(09:40 기준)이 지난 뒤
        response.setTimeline(timeline);
        response.setTransitFareYen(1520);
        return response;
    }

    private static AiRouteResponse osakaDay(String date) {
        AiRouteResponse response = new AiRouteResponse();
        List<AiRouteResponse.DaySummary> days = new ArrayList<>();
        days.add(day(1, date));
        response.setDays(days);
        List<AiRouteResponse.TimelineItem> timeline = new ArrayList<>();
        timeline.add(item(1, "09:00", null));
        for (int i = 0; i < 4; i++) timeline.add(item(1, (10 + 2 * i) + ":00", leg(15, 240, "大阪市高速電気軌道", RailCategory.SUBWAY, "1号線(御堂筋線)")));
        response.setTimeline(timeline);
        response.setTransitFareYen(960);
        return response;
    }

    private static AiRouteResponse.DaySummary day(int number, String date) {
        AiRouteResponse.DaySummary day = new AiRouteResponse.DaySummary();
        day.setDay(number);
        day.setDate(date);
        return day;
    }

    private static AiRouteResponse.TimelineItem item(int day, String time, TransitLegDto leg) {
        AiRouteResponse.TimelineItem item = new AiRouteResponse.TimelineItem();
        item.setDay(day);
        item.setTime(time);
        item.setTransit(leg);
        return item;
    }

    /** 요금 묶음이 하나인 구간. operator 가 null 이면 도보 */
    private static TransitLegDto leg(int minutes, int fare, String operator, RailCategory category, String line) {
        TransitLegDto leg = new TransitLegDto();
        leg.setMinutes(minutes);
        leg.setFareYen(fare);
        if (operator != null) {
            PassAdvisor.Ride ride = new PassAdvisor.Ride();
            ride.fareYen = fare;
            PassAdvisor.Leg part = new PassAdvisor.Leg();
            part.operator = operator;
            part.category = category;
            part.line = line;
            ride.legs.add(part);
            leg.getFareRides().add(ride);
        }
        return leg;
    }

    /** 다른 패키지의 테스트 도우미를 쓰지 않도록 여기서 만든다 */
    private static final class PassAdvisorTestSupport {
        static PassAdvisor.Pass dayPass(String key, int price, String operator) {
            PassAdvisor.Pass pass = new PassAdvisor.Pass();
            pass.key = key;
            pass.name = key;
            pass.priceYen = price;
            pass.days = 1;
            PassAdvisor.Cover cover = new PassAdvisor.Cover();
            cover.operators.add(operator);
            pass.covers.add(cover);
            return pass;
        }
    }
}
