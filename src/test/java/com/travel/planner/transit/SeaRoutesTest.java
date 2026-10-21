package com.travel.planner.transit;

import com.travel.planner.dto.PlanRequest;
import com.travel.planner.dto.TransitLegDto;
import com.travel.planner.util.TravelTimeEstimator;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 섬으로 가는 구간: 땅 구분, 배·비행기 노선 고르기, 섬 이동 수단 선택, 구간 안내, 일정 이동 시간 */
class SeaRoutesTest {

    // 자주 쓰는 지점
    static final double[] HIROSHIMA_STA = {34.3960, 132.4596};
    static final double[] ITSUKUSHIMA = {34.2960, 132.3198};
    static final double[] TOKYO_STA = {35.6812, 139.7671};
    static final double[] NAHA_KOKUSAI = {26.2125, 127.6792};
    static final double[] CHURAUMI = {26.6942, 127.8780};
    static final double[] IE = {26.71, 127.80};
    static final double[] TAKETOMI = {24.3333, 124.0880};
    static final double[] ISHIGAKI = {24.3448, 124.1572};
    static final double[] KAGOSHIMA_STA = {31.5966, 130.5571};
    static final double[] YAKUSHIMA = {30.3420, 130.5300};
    static final double[] SAPPORO = {43.0687, 141.3508};

    private static SeaRoutes.Crossing cross(double[] a, double[] b, String transport) {
        return SeaRoutes.crossing(a[0], a[1], b[0], b[1], transport);
    }

    private static List<String> modes(SeaRoutes.Crossing c) {
        return c.routes.stream().map(SeaRoutes.Route::modeName).collect(Collectors.toList());
    }

    @Test
    void landsAreRecognised() {
        assertEquals("MIYAJIMA", SeaRoutes.landOf(ITSUKUSHIMA[0], ITSUKUSHIMA[1]), "바닷가 신사도 섬으로 (해안선 300m 여유)");
        assertEquals(SeaRoutes.MAINLAND, SeaRoutes.landOf(HIROSHIMA_STA[0], HIROSHIMA_STA[1]));
        assertEquals(SeaRoutes.MAINLAND, SeaRoutes.landOf(34.3117, 132.3033), "미야지마구치(건너편 본토)");
        assertEquals(SeaRoutes.OKINAWA, SeaRoutes.landOf(CHURAUMI[0], CHURAUMI[1]));
        assertEquals("IE", SeaRoutes.landOf(IE[0], IE[1]), "오키나와 본섬 범위 안의 섬");
        assertEquals("TAKETOMI", SeaRoutes.landOf(TAKETOMI[0], TAKETOMI[1]));
        assertEquals("ISHIGAKI", SeaRoutes.landOf(ISHIGAKI[0], ISHIGAKI[1]));
        assertEquals("SAKURAJIMA", SeaRoutes.landOf(31.585, 130.657));
        assertEquals(SeaRoutes.HOKKAIDO, SeaRoutes.landOf(SAPPORO[0], SAPPORO[1]));
        assertEquals(SeaRoutes.HOKKAIDO, SeaRoutes.landOf(41.77, 140.73), "하코다테");
        assertEquals(SeaRoutes.MAINLAND, SeaRoutes.landOf(41.52, 140.91), "아오모리 오마");
        assertEquals(SeaRoutes.MAINLAND, SeaRoutes.landOf(34.6551, 133.9195), "오카야마");
        assertEquals(SeaRoutes.MAINLAND, SeaRoutes.landOf(33.59, 130.42), "후쿠오카 (규슈는 본토와 이어져 있다)");
        assertEquals(SeaRoutes.MAINLAND, SeaRoutes.landOf(34.34, 134.05), "다카마쓰 (시코쿠)");
        assertEquals(SeaRoutes.OKINAWA_OTHER, SeaRoutes.landOf(24.06, 123.78), "하테루마 등 표에 없는 섬");
        assertNull(SeaRoutes.landOf(37.56, 126.97), "서울");
        assertNull(SeaRoutes.landOf(35.10, 129.04), "부산");
        assertNull(SeaRoutes.landOf(33.45, 126.55), "제주");
        assertEquals(SeaRoutes.MAINLAND, SeaRoutes.landOf(34.20, 129.29), "쓰시마 (표에 없는 섬은 본토로 본다)");
        assertEquals("SADO", SeaRoutes.landOf(38.03, 138.37));
    }

    @Test
    void everyPortAndAirportIsOnItsOwnLand() {
        for (SeaRoutes.Route r : SeaRoutes.routes()) {
            for (SeaRoutes.Hub h : new SeaRoutes.Hub[]{r.a, r.b}) {
                assertEquals(h.land, SeaRoutes.landOf(h.lat, h.lng), h.name);
            }
        }
    }

    @Test
    void sameLandIsNotACrossing() {
        assertNull(cross(TOKYO_STA, HIROSHIMA_STA, "대중교통"));
        assertNull(cross(new double[]{40.8286, 140.7343}, new double[]{41.7735, 140.7266}, "대중교통"), "아오모리 → 하코다테는 철도(세이칸 터널)");
        assertNull(cross(TOKYO_STA, SAPPORO, SeaRoutes.tag("대중교통", "배")), "배를 고르면 본토 ↔ 홋카이도는 철도");
        assertEquals(List.of("비행기"), modes(cross(TOKYO_STA, SAPPORO, "대중교통")), "고르지 않았으면 훨씬 빠른 비행기 (철도 8시간 안팎)");
        assertNull(cross(NAHA_KOKUSAI, CHURAUMI, "대중교통"));
    }

    @Test
    void shortHopsUseTheFerry() {
        SeaRoutes.Crossing miyajima = cross(HIROSHIMA_STA, ITSUKUSHIMA, "도보 및 대중교통");
        assertEquals(List.of("배"), modes(miyajima));
        assertEquals("미야지마구치 선착장", miyajima.hops.get(0)[0].name);
        assertTrue(miyajima.minutes >= 40 && miyajima.minutes <= 120, "히로시마역 → 이쓰쿠시마 신사 " + miyajima.minutes + "분");

        SeaRoutes.Crossing ie = cross(CHURAUMI, IE, "대중교통");
        assertEquals("모토부항", ie.hops.get(0)[0].name);

        SeaRoutes.Crossing back = cross(TAKETOMI, ISHIGAKI, "대중교통");
        assertEquals(List.of("배"), modes(back), "반대 방향도 같은 노선");
        assertEquals("다케토미항", back.hops.get(0)[0].name);
    }

    @Test
    void farIslandsChainFlightAndFerry() {
        SeaRoutes.Crossing c = cross(NAHA_KOKUSAI, TAKETOMI, "대중교통");
        assertEquals(List.of("비행기", "배"), modes(c), "나하 → 이시가키(비행기) → 다케토미(배)");
        assertEquals("이시가키 공항", c.hops.get(0)[1].name);
        assertEquals("이시가키항 이도 터미널", c.hops.get(1)[0].name);

        SeaRoutes.Crossing okinawa = cross(TOKYO_STA, NAHA_KOKUSAI, "대중교통");
        assertEquals(List.of("비행기"), modes(okinawa));
        assertEquals("하네다 공항", okinawa.hops.get(0)[0].name, "도쿄역에서 가까운 공항");
        assertTrue(okinawa.minutes > 200 && okinawa.minutes < 400, "도쿄 → 나하 " + okinawa.minutes + "분");
    }

    @Test
    void chosenIslandTransportIsUsedWhenPossible() {
        // 가고시마 → 야쿠시마: 고르지 않으면 고속선, 비행기를 고르면 비행기
        assertEquals(List.of("배"), modes(cross(KAGOSHIMA_STA, YAKUSHIMA, "대중교통")));
        SeaRoutes.Crossing byAir = cross(KAGOSHIMA_STA, YAKUSHIMA, SeaRoutes.tag("대중교통", "비행기"));
        assertEquals(List.of("비행기"), modes(byAir));
        assertNull(byAir.prefNote);

        // 배를 골랐지만 나하 → 이시가키는 배편이 없다
        SeaRoutes.Crossing noShip = cross(NAHA_KOKUSAI, ISHIGAKI, SeaRoutes.tag("대중교통", "배"));
        assertEquals(List.of("비행기"), modes(noShip));
        assertTrue(noShip.prefNote.contains("배편이 없어"), noShip.prefNote);

        // 비행기를 골랐지만 미야지마에는 공항이 없다
        SeaRoutes.Crossing noAirport = cross(HIROSHIMA_STA, ITSUKUSHIMA, SeaRoutes.tag("대중교통", "비행기"));
        assertEquals(List.of("배"), modes(noAirport));
        assertTrue(noAirport.prefNote.contains("비행기 편이 없어"), noAirport.prefNote);

        // 본토 ↔ 홋카이도는 비행기를 골랐을 때만 비행기
        SeaRoutes.Crossing sapporo = cross(TOKYO_STA, SAPPORO, SeaRoutes.tag("대중교통", "비행기"));
        assertEquals(List.of("비행기"), modes(sapporo));
        assertEquals("신치토세 공항", sapporo.hops.get(0)[1].name);
    }

    @Test
    void islandsWithoutRoutesGetAWarningNotATaxi() {
        SeaRoutes.Crossing c = SeaRoutes.crossing(ISHIGAKI[0], ISHIGAKI[1], 24.06, 123.78, "대중교통");
        assertTrue(c.unknown);
        assertTrue(c.minutes > 60);
        TransitLegDto leg = TransitLegPlanner.plan(null, null, null, ISHIGAKI[0], ISHIGAKI[1], "이시가키", 24.06, 123.78, "하테루마", "대중교통", 60, null, null);
        assertEquals("배 등", leg.getMode());
        assertNull(leg.getFareYen(), "모르는 요금은 넣지 않는다");
        assertTrue(leg.getNote().contains("운항사"));
    }

    @Test
    void tagRoundTrip() {
        assertEquals("도보 및 대중교통", SeaRoutes.tag("도보 및 대중교통", null));
        assertEquals("도보 및 대중교통", SeaRoutes.tag("도보 및 대중교통", "상관없음"));
        String tagged = SeaRoutes.tag("도보 및 대중교통", "배");
        assertEquals(SeaRoutes.Pref.SHIP, SeaRoutes.prefOf(tagged));
        assertEquals("도보 및 대중교통", SeaRoutes.untag(tagged));
        assertEquals(SeaRoutes.Pref.FLIGHT, SeaRoutes.prefOf(SeaRoutes.tag("렌트카", "비행기")));
        assertEquals(SeaRoutes.Pref.SHIP, SeaRoutes.Pref.of("페리"));
        assertEquals(SeaRoutes.Pref.FLIGHT, SeaRoutes.Pref.of("항공"));
        assertEquals(SeaRoutes.tag("대중교통", "비행기"), SeaRoutes.tag(SeaRoutes.tag("대중교통", "배"), "비행기"), "다시 붙여도 하나만");
        // 태그가 붙어도 기존 판단(도보·대중교통·렌트)은 그대로
        assertEquals(TravelTimeEstimator.minutesForDistance(10, "도보 및 대중교통"), TravelTimeEstimator.minutesForDistance(10, tagged));

        PlanRequest req = new PlanRequest();
        req.setTransportation("도보 및 대중교통");
        req.setIslandTransport("비행기");
        assertEquals(SeaRoutes.Pref.FLIGHT, SeaRoutes.prefOf(req.legTransport()));
    }

    @Test
    void ferryLegHasGroundShipGroundSteps() {
        TransitLegDto leg = TransitLegPlanner.plan(null, null, null, HIROSHIMA_STA[0], HIROSHIMA_STA[1], "히로시마역",
                ITSUKUSHIMA[0], ITSUKUSHIMA[1], "이쓰쿠시마 신사", "도보 및 대중교통", 60, null, null);
        assertEquals("배", leg.getMode());
        assertEquals("sea", leg.getSource());
        TransitLegDto.Step ride = leg.getSteps().stream().filter(s -> "승차".equals(s.getType()) && "배".equals(s.getCategory())).findFirst().orElseThrow();
        assertEquals("미야지마구치 선착장", ride.getFrom());
        assertEquals("미야지마 선착장", ride.getTo());
        assertEquals(10, ride.getMinutes());
        assertEquals(200, ride.getFareYen());
        assertTrue(leg.getFareYen() >= 200, "땅 위 구간 + 배 요금");
        assertTrue(leg.getSteps().indexOf(ride) > 0, "배 앞에 선착장까지 가는 단계가 있다");
        assertEquals(leg.getMinutes(), leg.getSteps().stream().mapToInt(TransitLegDto.Step::getMinutes).sum(), "단계 시간의 합 = 전체");
        assertTrue(leg.getNote().contains("방문세"));
        assertFalse(leg.getSummary().contains("택시"));
    }

    @Test
    void flightLegLeavesAirfareOut() {
        TransitLegDto leg = TransitLegPlanner.plan(null, null, null, TOKYO_STA[0], TOKYO_STA[1], "도쿄역",
                NAHA_KOKUSAI[0], NAHA_KOKUSAI[1], "국제거리", "도보 및 대중교통", 300, null, null);
        assertEquals("비행기", leg.getMode());
        TransitLegDto.Step flight = leg.getSteps().stream().filter(s -> "비행기".equals(s.getCategory()) && "승차".equals(s.getType())).findFirst().orElseThrow();
        assertNull(flight.getFareYen());
        assertTrue(leg.getNote().contains("항공권"));
        assertTrue(leg.getFareYen() < 3000, "합계에는 공항까지·공항에서의 요금만: " + leg.getFareYen());
    }

    @Test
    void engineTravelTimeUsesTheCrossing() {
        int straight = TravelTimeEstimator.minutesForDistance(14, "도보 및 대중교통");
        int sea = TravelTimeEstimator.minutes(HIROSHIMA_STA[0], HIROSHIMA_STA[1], ITSUKUSHIMA[0], ITSUKUSHIMA[1], "도보 및 대중교통");
        assertTrue(sea > straight, "배를 타면 직선거리 추정보다 오래 걸린다: " + sea + " vs " + straight);
        int naha = TravelTimeEstimator.minutes(TOKYO_STA[0], TOKYO_STA[1], NAHA_KOKUSAI[0], NAHA_KOKUSAI[1], "대중교통");
        assertTrue(naha < 400, "도쿄 → 나하를 철도 속도로 계산하지 않는다(약 11시간): " + naha);
        assertNotNull(SeaRoutes.crossingMinutes(TOKYO_STA[0], TOKYO_STA[1], NAHA_KOKUSAI[0], NAHA_KOKUSAI[1], "대중교통"));
        assertEquals(naha, (int) SeaRoutes.crossingMinutes(TOKYO_STA[0], TOKYO_STA[1], NAHA_KOKUSAI[0], NAHA_KOKUSAI[1], "대중교통"), "두 번째는 저장해 둔 값");
    }
}
