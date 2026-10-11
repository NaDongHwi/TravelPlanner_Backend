package com.travel.planner.transit;

import com.travel.planner.dto.TransitLegDto;
import com.travel.planner.entity.TransitPattern;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * GTFS 가져오기 기능 테스트: zip 읽기 → 노선 패턴·운행 횟수·요금표 → 철도망과 합친 경로 탐색 → 구간 안내.
 * 실제 파일 대신, 같은 형식으로 만든 작은 가상 버스망을 쓴다 (위도 35.0, 경도 0.01도 ≈ 0.91km).
 *
 *   10번 "駅前～温泉線" : 駅前 ─ 市役所前 ─ 城跡公園 ─ 温泉入口   (구간 요금 150~250엔, 평일 6회 · 토·휴일 3회, 왕복)
 *   "市内循環"          : 駅前 → 北町 → 東町 → 市役所前 → 駅前        (균일 200엔, 10분 간격: frequencies.txt)
 *   "山頂線"            : 温泉入口 ─ 山頂                              (500엔, 여름(2027-07-01 ~ 08-31)에만 하루 2회)
 */
class GtfsNetworkTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 10, 11);
    private static final LocalDate WEEKDAY = LocalDate.of(2026, 10, 13);      // 화요일
    private static final LocalDate HOLIDAY_MONDAY = LocalDate.of(2026, 10, 12); // 스포츠의 날
    private static final LocalDate SUMMER_DAY = LocalDate.of(2027, 7, 20);

    private static final double LAT = GtfsTestFeed.LAT;
    private static final double LNG = GtfsTestFeed.LNG;

    // ---------------------------------------------------------------- 가나 → 한글, 공휴일

    @Test
    void kanaReadingIsWrittenInHangulByTheOfficialRules() {
        assertEquals("도쿄", KanaHangul.convert("とうきょう"), "장모음은 적지 않는다");
        assertEquals("오사카", KanaHangul.convert("おおさか"));
        assertEquals("기요미즈미치", KanaHangul.convert("きよみずみち"), "어두는 예사소리, 어중은 거센소리");
        assertEquals("신주쿠", KanaHangul.convert("しんじゅく"), "ん 은 ㄴ 받침");
        assertEquals("닛폰바시", KanaHangul.convert("にっぽんばし"), "っ 은 ㅅ 받침");
        assertEquals("규슈", KanaHangul.convert("きゅうしゅう"));
        assertEquals("지바", KanaHangul.convert("ちば"));
        assertEquals("쓰키지", KanaHangul.convert("つきじ"));
        assertEquals("에이덴", KanaHangul.convert("えいでん"), "えい 는 줄이지 않는다");
        assertEquals("시야쿠쇼마에", KanaHangul.convert("しやくしょまえ"));
        assertEquals("바스센타", KanaHangul.convert("バスセンター"), "가타카나와 장음 부호");
        assertEquals("에키마에 2", KanaHangul.convert("えきまえ　２"), "숫자·공백은 그대로(반각으로)");
        assertNull(KanaHangul.convert("京都駅前"), "한자가 섞여 있으면 읽기를 알 수 없다");
        assertNull(KanaHangul.convert(""));
        assertNull(KanaHangul.convert(null));
    }

    @Test
    void japaneseHolidaysDecideWhichTimetableApplies() {
        assertTrue(JapanHolidays.isHoliday(LocalDate.of(2026, 10, 12)), "스포츠의 날 (10월 둘째 월요일)");
        assertTrue(JapanHolidays.isHoliday(LocalDate.of(2026, 9, 22)), "경로의 날과 추분 사이에 낀 국민의 휴일");
        assertTrue(JapanHolidays.isHoliday(LocalDate.of(2026, 5, 6)), "5/3 이 일요일이라 5/6 이 대체 휴일");
        assertTrue(JapanHolidays.isHoliday(LocalDate.of(2027, 3, 22)), "춘분(3/21 일요일)의 대체 휴일");
        assertFalse(JapanHolidays.isHoliday(LocalDate.of(2026, 10, 13)));
        assertFalse(JapanHolidays.isHoliday(LocalDate.of(2026, 5, 7)));

        assertEquals(JapanHolidays.WEEKDAY, JapanHolidays.dayType(WEEKDAY));
        assertEquals(JapanHolidays.SATURDAY, JapanHolidays.dayType(LocalDate.of(2026, 10, 17)));
        assertEquals(JapanHolidays.HOLIDAY, JapanHolidays.dayType(LocalDate.of(2026, 10, 18)), "일요일");
        assertEquals(JapanHolidays.HOLIDAY, JapanHolidays.dayType(HOLIDAY_MONDAY), "월요일이지만 공휴일");
        assertEquals(JapanHolidays.HOLIDAY, JapanHolidays.dayType(LocalDate.of(2026, 12, 30)), "연말연시는 휴일 시간표");
        assertEquals(JapanHolidays.WEEKDAY, JapanHolidays.dayType(null), "날짜를 모르면 평일로 본다");
    }

    // ---------------------------------------------------------------- 파일 읽기

    @Test
    void feedIsReducedToPatternsWithTimesTripCountsAndFares() throws Exception {
        GtfsFeed feed = GtfsReader.read(GtfsTestFeed.zip(), TODAY);

        assertEquals("もぎ市", feed.publisher);
        assertEquals(LocalDate.of(2026, 4, 1), feed.validFrom);
        assertEquals(LocalDate.of(2027, 9, 30), feed.validTo);
        assertFalse(feed.expired);
        assertEquals(List.of("もぎ交通"), feed.agencies);
        assertEquals(7, feed.stops.size(), "실제로 지나는 정류장만 (상위 정류장·좌표 없는 정류장 제외)");
        assertEquals(5, feed.patterns.size(), "10번 왕복 + 순환 + 산정선 왕복");
        assertEquals(100, feed.fareCoveragePercent);
        assertTrue(feed.warnings.stream().anyMatch(w -> w.contains("좌표가 없거나")), "좌표 없는 정류장을 뺐다고 알린다: " + feed.warnings);

        // 10번 가는 방향: 줄 순서가 뒤섞여 있어도 stop_sequence 대로 정렬된다
        GtfsFeed.Pattern outbound = pattern(feed, "10", "駅前", "温泉入口");
        assertEquals(List.of("駅前", "市役所前", "城跡公園", "温泉入口"), stopNames(feed, outbound));
        assertEquals("駅前～温泉線", outbound.routeLongName);
        assertEquals("もぎ交通", outbound.agency);
        assertEquals(RailCategory.BUS, outbound.category);
        assertEquals("温泉入口行き", outbound.headsign);
        assertArrayEquals(new int[]{6, 3, 3}, outbound.trips, "평일 6회, 토요일 3회, 일요일·공휴일 3회");
        assertEquals(0f, outbound.cumMinutes[0]);
        assertEquals(4f, outbound.cumMinutes[1]);
        assertEquals(8f, outbound.cumMinutes[2]);
        assertEquals(15.7f, outbound.cumMinutes[3], 0.01f, "9편 가운데 1편만 21분 걸린다: 평균 15.7분");
        assertEquals(8 * 60, outbound.firstDeparture);
        assertEquals(18 * 60, outbound.lastDeparture);
        assertNull(outbound.serviceFrom, "연중 운행");
        assertNull(outbound.serviceTo);
        assertFalse(outbound.irregular);

        // 구간 요금표
        assertNull(outbound.flatFare);
        assertEquals(150, outbound.fare(0, 1));
        assertEquals(180, outbound.fare(0, 2));
        assertEquals(250, outbound.fare(0, 3));
        assertEquals(220, outbound.fare(1, 3));
        assertEquals(180, outbound.fare(2, 3));
        assertEquals(-1, outbound.fare(3, 1), "거꾸로는 탈 수 없다");

        // 돌아오는 방향: 市役所前 은 하차 불가(drop_off_type=1)
        GtfsFeed.Pattern inbound = pattern(feed, "10", "温泉入口", "駅前");
        assertEquals(260, inbound.fare(0, 3), "요금은 방향별로 읽는다 (温泉入口→駅前 만 260엔)");
        assertEquals(180, inbound.fare(0, 1));
        assertEquals(2, inbound.flags[2], "市役所前 하차 불가");
        assertEquals(0, inbound.flags[1]);

        // 순환선: 이름이 긴 것만 있고, frequencies.txt 로 10분 간격, 균일 요금
        GtfsFeed.Pattern loop = pattern(feed, "市内循環", "駅前", "駅前");
        assertNull(loop.routeLongName);
        assertEquals(5, loop.stops.length);
        assertArrayEquals(new int[]{84, 84, 84}, loop.trips, "07:00 부터 21:00 전까지 10분 간격으로");
        assertEquals(200, loop.flatFare);
        assertEquals(200, loop.fare(1, 3));
        assertEquals(7 * 60, loop.firstDeparture);
        assertEquals(20 * 60 + 50, loop.lastDeparture, "21:00 이 되기 전 마지막 출발");

        // 여름에만 다니는 노선: 운행 기간이 남는다
        GtfsFeed.Pattern summit = pattern(feed, "山頂線", "温泉入口", "山頂");
        assertArrayEquals(new int[]{2, 2, 2}, summit.trips);
        assertEquals(LocalDate.of(2027, 7, 1), summit.serviceFrom);
        assertEquals(LocalDate.of(2027, 8, 31), summit.serviceTo);
        assertEquals(500, summit.flatFare);

        // 정류장 한국어 표기: 원본의 한국어 번역 > 읽기(가나)를 한글로 > 없음
        assertEquals("에키마에", stop(feed, "駅前").nameKo, "record_id 로 지정한 읽기");
        assertEquals("시야쿠쇼마에", stop(feed, "市役所前").nameKo, "field_value 로 지정한 읽기");
        assertEquals("성터 공원", stop(feed, "城跡公園").nameKo, "원본에 한국어 번역이 있으면 그것을 쓴다");
        assertNull(stop(feed, "山頂").nameKo, "읽기 자료가 없으면 비워 둔다 (나중에 AI 번역 대상)");
    }

    @Test
    void expiredFeedIsStillReadButFlaggedAndOldFormatIsAccepted() throws Exception {
        // 유효 기간이 지난 뒤에 읽으면: 마지막 1년의 시간표로 읽고, 날짜별 운행 기간은 남기지 않는다
        GtfsFeed old = GtfsReader.read(GtfsTestFeed.zip(), LocalDate.of(2028, 1, 15));
        assertTrue(old.expired);
        assertEquals(5, old.patterns.size());
        GtfsFeed.Pattern summit = pattern(old, "山頂線", "温泉入口", "山頂");
        assertNull(summit.serviceFrom);
        assertArrayEquals(new int[]{2, 2, 2}, summit.trips);

        // 옛 형식(GTFS-JP 1판)의 translations.txt: trans_id 가 일본어 원문
        Map<String, String> files = GtfsTestFeed.files();
        files.put("translations.txt", "trans_id,lang,translation\n駅前,ja,駅前\n駅前,ja-Hrkt,えきまえ\n山頂,ko-KR,산 정상\n");
        GtfsFeed legacy = GtfsReader.read(GtfsTestFeed.zip(files), TODAY);
        assertEquals("에키마에", stop(legacy, "駅前").nameKo);
        assertEquals("산 정상", stop(legacy, "山頂").nameKo);

        // 요금 파일이 없는 피드: 노선은 읽고 요금만 모른다
        files.remove("fare_attributes.txt");
        files.remove("fare_rules.txt");
        GtfsFeed noFare = GtfsReader.read(GtfsTestFeed.zip(files), TODAY);
        assertEquals(0, noFare.fareCoveragePercent);
        assertEquals(-1, pattern(noFare, "10", "駅前", "温泉入口").fare(0, 3));
        assertTrue(noFare.warnings.stream().anyMatch(w -> w.contains("요금 정보")), noFare.warnings.toString());

        // GTFS 가 아닌 zip
        Map<String, String> broken = new LinkedHashMap<>();
        broken.put("readme.txt", "hello");
        IOException error = assertThrows(IOException.class, () -> GtfsReader.read(GtfsTestFeed.zip(broken), TODAY));
        assertTrue(error.getMessage().contains("GTFS 파일이 아닙니다"), error.getMessage());
    }

    @Test
    void timeAndDateFormatsAreParsedLeniently() {
        assertEquals(8 * 3600 + 5 * 60, GtfsReader.parseTime("8:05:00"));
        assertEquals(25 * 3600 + 10 * 60, GtfsReader.parseTime("25:10:00"), "자정 넘긴 운행");
        assertEquals(-1, GtfsReader.parseTime(""));
        assertEquals(-1, GtfsReader.parseTime("abc"));
        assertEquals(LocalDate.of(2026, 10, 11), GtfsReader.parseDate("20261011"));
        assertEquals(LocalDate.of(2026, 10, 11), GtfsReader.parseDate("2026-10-11"));
        assertNull(GtfsReader.parseDate("2026"));
        assertEquals(RailCategory.TRAM, GtfsReader.categoryOf(0, "x"));
        assertEquals(RailCategory.BUS, GtfsReader.categoryOf(3, "x"));
        assertEquals(RailCategory.FERRY, GtfsReader.categoryOf(4, "x"));
        assertEquals(RailCategory.JR, GtfsReader.categoryOf(2, "東日本旅客鉄道"));
        assertEquals(RailCategory.BUS, GtfsReader.categoryOf(1501, "x"), "합승 택시 등은 버스로");
    }

    @Test
    void patternSurvivesTheRoundTripThroughDatabaseRows() throws Exception {
        GtfsFeed feed = GtfsReader.read(GtfsTestFeed.zip(), TODAY);
        long[] rowIds = new long[feed.stops.size()];
        Map<Long, Integer> indexById = new HashMap<>();
        for (int i = 0; i < rowIds.length; i++) {
            rowIds[i] = 1000 + i * 7L;                 // DB 가 매긴 id 는 0부터도 연속도 아니다
            indexById.put(rowIds[i], i);
        }
        for (GtfsFeed.Pattern original : feed.patterns) {
            TransitPattern row = GtfsCodec.toRow("mogi", original, rowIds);
            GtfsFeed.Pattern restored = GtfsCodec.toPattern(row, indexById, false);
            assertNotNull(restored);
            assertEquals("mogi", restored.feedKey);
            assertEquals(original.routeName, restored.routeName);
            assertEquals(original.category, restored.category);
            assertArrayEquals(original.stops, restored.stops);
            assertArrayEquals(original.cumMinutes, restored.cumMinutes, 0.051f);
            assertArrayEquals(original.trips, restored.trips);
            assertEquals(original.flatFare, restored.flatFare);
            assertArrayEquals(original.fares, restored.fares);
            assertEquals(original.serviceFrom, restored.serviceFrom);
            assertEquals(original.firstDeparture, restored.firstDeparture);
            if (original.flags != null && hasFlag(original.flags)) assertArrayEquals(original.flags, restored.flags);
        }
        // 정류장이 지워진 패턴은 건너뛴다 (예외를 던지지 않는다)
        TransitPattern row = GtfsCodec.toRow("mogi", feed.patterns.get(0), rowIds);
        assertNull(GtfsCodec.toPattern(row, new HashMap<>(), false));
        row.setCumMinutes("not,a,number");
        assertNull(GtfsCodec.toPattern(row, indexById, false));
    }

    // ---------------------------------------------------------------- 경로 탐색

    @Test
    void busRouteUsesTheOfficialFareAndTheTripCountOfThatDay() throws Exception {
        RailGraph graph = busGraph();
        assertEquals(0, graph.stationCount());
        assertEquals(7, graph.stopCount());
        assertEquals(5, graph.patternCount());

        // 역 앞 근처 → 온천 입구 근처 (직선 3.6km), 평일 09:30 출발
        RailGraph.Route route = graph.route(LAT + 0.0005, LNG + 0.0002, LAT + 0.0004, LNG + 0.0398, WEEKDAY, 9 * 60 + 30);
        assertNotNull(route);
        assertEquals(List.of(RailGraph.Step.Kind.WALK, RailGraph.Step.Kind.WAIT, RailGraph.Step.Kind.RIDE, RailGraph.Step.Kind.WALK),
                route.steps.stream().map(s -> s.kind).collect(Collectors.toList()));
        RailGraph.Step ride = route.steps.get(2);
        assertTrue(ride.scheduled);
        assertEquals("10", ride.line);
        assertEquals("駅前", ride.from);
        assertEquals("에키마에", ride.fromKo);
        assertEquals("温泉入口", ride.to);
        assertEquals(3, ride.stops);
        assertEquals(16, ride.minutes, "시각표의 평균 15.7분");
        assertEquals(250, ride.fareYen);
        assertTrue(ride.fareConfirmed);
        assertEquals(6, ride.tripsPerDay, "평일 6회");
        assertEquals(120, ride.headwayMinutes);
        assertEquals(8 * 60, ride.firstDeparture);
        assertEquals(18 * 60, ride.lastDeparture);
        assertFalse(ride.outsideServiceHours);
        assertEquals(15, route.steps.get(1).minutes, "2시간 간격이어도 기다리는 시간은 15분까지만 잡는다 (시간표를 보고 나간다)");
        assertEquals(250, route.fareYen);
        assertEquals(130, route.childFareYen);
        assertEquals(1, route.confirmedFareRides);
        assertEquals(0, route.estimatedFareRides);
        assertEquals(0, route.transfers);
        assertEquals(route.steps.stream().mapToInt(s -> s.minutes).sum(), route.minutes);

        // 같은 구간을 공휴일(월요일)에 가면 휴일 시간표: 하루 3회
        RailGraph.Route holiday = graph.route(LAT + 0.0005, LNG + 0.0002, LAT + 0.0004, LNG + 0.0398, HOLIDAY_MONDAY, 9 * 60 + 30);
        assertEquals(3, holiday.steps.get(2).tripsPerDay);

        // 막차(18:00) 뒤에 출발하면 경로는 알려 주되 운행 시간 밖이라고 표시한다
        RailGraph.Route late = graph.route(LAT + 0.0005, LNG + 0.0002, LAT + 0.0004, LNG + 0.0398, WEEKDAY, 21 * 60);
        assertTrue(late.steps.get(2).outsideServiceHours);

        // 가까운 구간은 10분 간격 순환선: 기다리는 시간이 짧다
        RailGraph.Route shortHop = graph.route(LAT + 0.0002, LNG, LAT + 0.0102, LNG + 0.0102, WEEKDAY, 600);
        RailGraph.Step loopRide = shortHop.steps.get(2);
        assertEquals("市内循環", loopRide.line);
        assertEquals("東町", loopRide.to);
        assertEquals(200, loopRide.fareYen);
        assertEquals(84, loopRide.tripsPerDay);
        assertEquals(10, loopRide.headwayMinutes);
        assertEquals(5, shortHop.steps.get(1).minutes, "10분 간격이면 평균 5분 기다린다");
    }

    @Test
    void seasonalRouteRunsOnlyInItsSeasonAndDropOffRestrictionIsRespected() throws Exception {
        RailGraph graph = busGraph();

        // 산 정상은 여름 노선으로만 갈 수 있다
        assertNull(graph.route(LAT, LNG, LAT, LNG + 0.08, WEEKDAY, 600), "10월에는 산정선이 다니지 않는다");
        RailGraph.Route summer = graph.route(LAT, LNG, LAT, LNG + 0.08, SUMMER_DAY, 8 * 60);
        assertNotNull(summer, "7월에는 간다");
        List<RailGraph.Step> rides = summer.steps.stream().filter(s -> s.kind == RailGraph.Step.Kind.RIDE).collect(Collectors.toList());
        assertEquals(List.of("10", "山頂線"), rides.stream().map(s -> s.line).collect(Collectors.toList()));
        assertEquals(1, summer.transfers);
        assertEquals(750, summer.fareYen, "탈 때마다 따로 낸다: 250 + 500");
        RailGraph.Step transfer = summer.steps.stream().filter(s -> s.kind == RailGraph.Step.Kind.TRANSFER).findFirst().orElseThrow();
        assertEquals("温泉入口", transfer.from);
        assertEquals("温泉入口", transfer.to);
        assertEquals(15, transfer.minutes, "같은 정류장에서 갈아타며 다음 버스를 기다린다");
        assertEquals(2, rides.get(1).tripsPerDay);
        assertNull(rides.get(1).toKo, "읽기 자료가 없는 정류장은 한국어 표기가 없다");

        // 날짜를 모르면 평일 운행 기준으로 보되, 계절 노선도 후보에 둔다 (운행 기간을 따질 수 없으므로)
        assertNotNull(graph.route(LAT, LNG, LAT, LNG + 0.08));

        // 돌아오는 10번은 市役所前 에 내릴 수 없다: 그 정류장에서 내리는 경로는 나오지 않는다
        RailGraph.Route back = graph.route(LAT, LNG + 0.04, LAT, LNG + 0.0101, WEEKDAY, 9 * 60);
        assertNotNull(back);
        for (RailGraph.Step step : back.steps) {
            if (step.kind == RailGraph.Step.Kind.RIDE && "10".equals(step.line)) {
                assertFalse("市役所前".equals(step.to), "하차 불가 정류장에서 내렸다");
            }
        }
    }

    @Test
    void railAndBusAreCombinedWithAWalkingTransfer() throws Exception {
        // JR "本線": 西駅 ─ 中央駅 (9km). 中央駅 은 버스 정류장 駅前 에서 약 100m
        List<RailGraph.Station> stations = new ArrayList<>();
        stations.add(new RailGraph.Station(0, "西駅", "西日本旅客鉄道", "本線", "G1", RailCategory.JR, LAT, LNG - 0.1));
        stations.add(new RailGraph.Station(1, "中央駅", "西日本旅客鉄道", "本線", "G2", RailCategory.JR, LAT + 0.0005, LNG - 0.001));
        List<double[]> links = new ArrayList<>();
        links.add(new double[]{0, 1, 9.0});
        GtfsNetwork network = new GtfsNetwork().add("mogi", GtfsReader.read(GtfsTestFeed.zip(), TODAY));
        RailGraph graph = new RailGraph(stations, links, network.stops, network.patterns);

        RailGraph.Route route = graph.route(LAT, LNG - 0.1, LAT, LNG + 0.04, WEEKDAY, 9 * 60);
        assertNotNull(route);
        List<RailGraph.Step> rides = route.steps.stream().filter(s -> s.kind == RailGraph.Step.Kind.RIDE).collect(Collectors.toList());
        assertEquals(2, rides.size());
        assertEquals("本線", rides.get(0).line);
        assertFalse(rides.get(0).scheduled);
        assertEquals("10", rides.get(1).line);
        assertTrue(rides.get(1).scheduled);
        assertEquals(1, route.transfers);
        RailGraph.Step transfer = route.steps.stream().filter(s -> s.kind == RailGraph.Step.Kind.TRANSFER).findFirst().orElseThrow();
        assertEquals("中央駅", transfer.from);
        assertFalse(transfer.fromIsStop);
        assertEquals("駅前", transfer.to);
        assertTrue(transfer.toIsStop);
        assertTrue(transfer.minutes >= 15, "걷는 시간 + 버스 대기: " + transfer.minutes + "분");
        assertEquals(RailFareModel.jrFare(9.0) + 250, route.fareYen, "JR 은 거리 기준 요금, 버스는 요금표");
        assertEquals(1, route.confirmedFareRides);
        assertEquals(1, route.estimatedFareRides);

        // 구간 안내
        TransitLegDto leg = TransitLegPlanner.plan(graph, TransitLegPlanner.ORIGINAL, LAT, LNG - 0.1, "호텔", LAT, LNG + 0.04, "온천", "도보 및 대중교통", 60,
                WEEKDAY, 9 * 60);
        assertEquals("JR·버스", leg.getMode());
        assertEquals("rail-network+gtfs", leg.getSource());
        assertEquals("mixed", leg.getFareSource());
        assertEquals(1, leg.getTransfers());
        assertTrue(leg.getSummary().contains(" / 환승 / "), leg.getSummary());
        assertTrue(leg.getNote().contains("철도 구간은 전국 철도망 기준 추정"), leg.getNote());
        assertTrue(leg.getNote().contains("버스 요금은 운영사 요금표"), leg.getNote());
    }

    @Test
    void legPlannerDescribesTheBusRideAndWarnsAboutSparseService() throws Exception {
        RailGraph graph = busGraph();
        TransitLegDto leg = TransitLegPlanner.plan(graph, TransitLegPlanner.ORIGINAL, LAT + 0.0005, LNG + 0.0002, "호텔", LAT + 0.0004, LNG + 0.0398,
                "온천", "도보 및 대중교통", 40, WEEKDAY, 9 * 60 + 30);

        assertEquals("버스", leg.getMode());
        assertEquals("gtfs", leg.getSource());
        assertEquals("official", leg.getFareSource());
        assertEquals(250, leg.getFareYen());
        assertEquals(130, leg.getChildFareYen());
        assertTrue(leg.isEstimated(), "소요 시간은 여전히 추정이다 (개별 출발 시각을 쓰지 않는다)");
        assertEquals(List.of("도보", "대기", "승차", "도보"), leg.getSteps().stream().map(TransitLegDto.Step::getType).collect(Collectors.toList()));
        assertEquals(leg.getSteps().stream().mapToInt(TransitLegDto.Step::getMinutes).sum(), leg.getMinutes());

        TransitLegDto.Step first = leg.getSteps().get(0);
        assertEquals("호텔", first.getFrom());
        assertEquals("에키마에", first.getTo(), "정류장은 한국어 표기로, '역'을 붙이지 않는다");
        TransitLegDto.Step ride = leg.getSteps().get(2);
        assertEquals("버스", ride.getCategory());
        assertEquals("10", ride.getLine());
        assertEquals("에키마에", ride.getFrom());
        assertEquals("온센이리구치", ride.getTo());
        assertEquals("駅前", ride.getFromOriginal(), "현지 표지판과 맞춰 볼 수 있게 원문도 준다");
        assertEquals("温泉入口", ride.getToOriginal());
        assertEquals("温泉入口行き", ride.getHeadsign());
        assertEquals(250, ride.getFareYen());
        assertEquals(Boolean.TRUE, ride.getFareConfirmed());
        assertEquals(6, ride.getTripsPerDay());
        assertEquals(120, ride.getHeadwayMinutes());
        assertEquals("08:00", ride.getFirstDeparture());
        assertEquals("18:00", ride.getLastDeparture());
        assertEquals("버스 10 에키마에 → 온센이리구치 (3정거장, 250엔)", leg.getSummary());
        assertTrue(leg.getNote().contains("하루 6회(약 120분 간격)만 다닙니다"), leg.getNote());
        assertTrue(leg.getNote().contains("첫차 08:00 · 막차 18:00"), leg.getNote());
        assertFalse(leg.getNote().contains("철도 구간은"), "철도를 타지 않았으면 철도 설명은 넣지 않는다");

        // 막차 뒤: 경고가 "운행하지 않을 수 있습니다"로 바뀐다
        TransitLegDto late = TransitLegPlanner.plan(graph, TransitLegPlanner.ORIGINAL, LAT + 0.0005, LNG + 0.0002, "호텔", LAT + 0.0004, LNG + 0.0398,
                "온천", "도보 및 대중교통", 40, WEEKDAY, 21 * 60);
        assertTrue(late.getNote().contains("이 시간에 운행하지 않을 수 있습니다"), late.getNote());

        // 자주 다니는 순환선에는 횟수 경고가 없다
        TransitLegDto loop = TransitLegPlanner.plan(graph, TransitLegPlanner.ORIGINAL, LAT + 0.0002, LNG, "호텔", LAT + 0.0102, LNG + 0.0102,
                "미술관", "도보 및 대중교통", 25, WEEKDAY, 600);
        assertEquals("버스", loop.getMode());
        assertFalse(loop.getNote().contains("회("), loop.getNote());
        assertEquals("버스 市内循環 에키마에 → 東町 (2정거장, 200엔)", loop.getSummary(), "번역되지 않은 이름은 원문 그대로 나간다");
    }

    @Test
    void railRoutesInAFeedAreSkippedUnlessRequested() throws Exception {
        // 피드에 지하철 노선(route_type 1)이 함께 들어 있는 경우
        Map<String, String> files = GtfsTestFeed.files();
        files.put("routes.txt", files.get("routes.txt") + "M1,A1,地下鉄線,,1\n");
        files.put("trips.txt", files.get("trips.txt") + "M1,DAILY,METRO,温泉入口行き\n");
        files.put("stop_times.txt", files.get("stop_times.txt") + "METRO,6:00:00,6:00:00,S1,1,0,0\nMETRO,6:05:00,6:05:00,S4,2,0,0\n");

        GtfsFeed skipped = GtfsReader.read(GtfsTestFeed.zip(files), TODAY);
        assertEquals(5, skipped.patterns.size(), "지하철 노선은 전국 철도망과 겹치므로 기본으로는 읽지 않는다");
        assertFalse(skipped.includesRail);
        assertTrue(skipped.warnings.stream().anyMatch(w -> w.contains("철도·지하철 노선 1개")), skipped.warnings.toString());

        GtfsFeed included = GtfsReader.read(GtfsTestFeed.zip(files), TODAY, true);
        assertEquals(6, included.patterns.size());
        assertTrue(included.includesRail);
        assertEquals(RailCategory.SUBWAY, pattern(included, "地下鉄線", "駅前", "温泉入口").category);

        // 철도 노선만 있는 피드: 이유를 알려 주며 거절한다
        files.put("routes.txt", "route_id,agency_id,route_short_name,route_long_name,route_type\nM1,A1,地下鉄線,,1\n");
        IOException error = assertThrows(IOException.class, () -> GtfsReader.read(GtfsTestFeed.zip(files), TODAY));
        assertTrue(error.getMessage().contains("철도·지하철 노선만 있는 피드"), error.getMessage());
    }

    @Test
    void railNetworkLineCoveredByAFeedIsReplacedByTheFeed() throws Exception {
        // 10번을 노면전차(route_type 0)로 바꾼 피드
        Map<String, String> files = GtfsTestFeed.files();
        files.put("routes.txt", files.get("routes.txt").replace("R1,A1,10,駅前～温泉線,3", "R1,A1,10,駅前～温泉線,0"));
        GtfsNetwork network = new GtfsNetwork().add("mogi", GtfsReader.read(GtfsTestFeed.zip(files), TODAY));

        // 철도망: 같은 길을 가는 노면전차 "市内線"(역 4개가 모두 정류장에서 100m 안), 역 하나만 겹치는 "郊外線", 같은 자리의 JR "本線"
        List<RailGraph.Station> stations = new ArrayList<>();
        double near = 0.0005;     // 약 55m
        double[] tramLng = {0, 0.01, 0.02, 0.04};
        for (int i = 0; i < tramLng.length; i++) {
            stations.add(new RailGraph.Station(stations.size(), "電停" + i, "もぎ電鉄", "市内線", "T" + i, RailCategory.TRAM, LAT + near, LNG + tramLng[i]));
        }
        stations.add(new RailGraph.Station(stations.size(), "郊外0", "もぎ電鉄", "郊外線", "U0", RailCategory.TRAM, LAT + near, LNG));
        stations.add(new RailGraph.Station(stations.size(), "郊外1", "もぎ電鉄", "郊外線", "U1", RailCategory.TRAM, LAT + 0.05, LNG));
        stations.add(new RailGraph.Station(stations.size(), "郊外2", "もぎ電鉄", "郊外線", "U2", RailCategory.TRAM, LAT + 0.10, LNG));
        for (int i = 0; i < tramLng.length; i++) {
            stations.add(new RailGraph.Station(stations.size(), "JR" + i, "西日本旅客鉄道", "本線", "J" + i, RailCategory.JR, LAT - near, LNG + tramLng[i]));
        }
        List<double[]> links = new ArrayList<>();
        links.add(new double[]{0, 1, 0.9});
        links.add(new double[]{1, 2, 0.9});
        links.add(new double[]{2, 3, 1.8});
        links.add(new double[]{4, 5, 5.5});
        links.add(new double[]{5, 6, 5.5});
        links.add(new double[]{7, 8, 0.9});
        links.add(new double[]{8, 9, 0.9});
        links.add(new double[]{9, 10, 1.8});

        RailGraph graph = new RailGraph(stations, links, network.stops, network.patterns);
        assertEquals(1, graph.supersededLineCount(), "노선 전체가 겹치는 市内線 만 빠진다");
        assertEquals(7, graph.stationCount(), "郊外線 3 + JR 4 는 남는다");
        assertEquals(5, graph.rideLinkCount());
        for (int i = 0; i < graph.stationCount(); i++) assertFalse("市内線".equals(graph.station(i).line));

        // 버스 피드(궤도가 아닌 것)는 철도망 노선을 대신하지 않는다
        GtfsNetwork busOnly = new GtfsNetwork().add("mogi", GtfsReader.read(GtfsTestFeed.zip(), TODAY));
        RailGraph withBus = new RailGraph(stations, links, busOnly.stops, busOnly.patterns);
        assertEquals(0, withBus.supersededLineCount());
        assertEquals(11, withBus.stationCount());
    }

    @Test
    void routeWithTooMuchWalkingGivesWayToTheBusThatStopsNearby() throws Exception {
        // JR "本線": 西駅(駅前 정류장 옆) ─ 東駅. 東駅 에서 온천까지는 1.9km 를 걸어야 한다. 버스 10번은 온천 바로 앞에 서지만 2시간 간격이다.
        List<RailGraph.Station> stations = new ArrayList<>();
        stations.add(new RailGraph.Station(0, "西駅", "西日本旅客鉄道", "本線", "G1", RailCategory.JR, LAT + 0.0005, LNG - 0.001));
        stations.add(new RailGraph.Station(1, "東駅", "西日本旅客鉄道", "本線", "G2", RailCategory.JR, LAT, LNG + 0.019));
        List<double[]> links = new ArrayList<>();
        links.add(new double[]{0, 1, 1.9});
        GtfsNetwork network = new GtfsNetwork().add("mogi", GtfsReader.read(GtfsTestFeed.zip(), TODAY));
        RailGraph graph = new RailGraph(stations, links, network.stops, network.patterns);

        // 걷는 거리를 제한하지 않으면: 빨리 오는 열차를 타고 내려서 오래 걷는 쪽이 나온다
        RailGraph.Route unlimited = graph.route(LAT, LNG, LAT, LNG + 0.04, WEEKDAY, 600);
        assertEquals("本線", unlimited.steps.stream().filter(s -> s.kind == RailGraph.Step.Kind.RIDE).findFirst().orElseThrow().line);
        assertTrue(unlimited.walkKm > 1.8, "역에서 " + unlimited.walkKm + "km 를 걷는다");

        // 걷는 거리를 직선거리의 절반까지로 제한하면: 그 경로는 빠지고 버스가 나온다
        RailGraph.Route limited = graph.route(LAT, LNG, LAT, LNG + 0.04, WEEKDAY, 600, 1.8);
        assertEquals("10", limited.steps.stream().filter(s -> s.kind == RailGraph.Step.Kind.RIDE).findFirst().orElseThrow().line);
        assertTrue(limited.walkKm < 0.1);

        // 구간 안내는 제한을 건 탐색을 쓴다
        TransitLegDto leg = TransitLegPlanner.plan(graph, TransitLegPlanner.ORIGINAL, LAT, LNG, "호텔", LAT, LNG + 0.04, "온천", "도보 및 대중교통", 40, WEEKDAY, 600);
        assertEquals("버스", leg.getMode());
        assertEquals(250, leg.getFareYen());
    }

    @Test
    void walkingIsSuggestedWhereBusDataExistsButNothingBeatsWalking() throws Exception {
        RailGraph graph = busGraph();
        // 城跡公園 근처 → 温泉入口 쪽으로 1.5km. 10번을 타면: 정류장까지 2분 + 대기 15분 + 승차 8분 + 걷기 7분 = 32분 > 걸어서 26분
        TransitLegDto slower = TransitLegPlanner.plan(graph, TransitLegPlanner.ORIGINAL, LAT + 0.001, LNG + 0.020, "성터", LAT + 0.001, LNG + 0.036,
                "찻집", "도보 및 대중교통", 15, WEEKDAY, 600);
        assertEquals("도보", slower.getMode());
        assertEquals("walk", slower.getSource());
        assertEquals(0, slower.getFareYen());
        assertEquals(26, slower.getMinutes());
        assertTrue(slower.getNote().contains("걷는 것보다 빨리 가는 대중교통이 없어"), slower.getNote());
        assertTrue(slower.getNote().contains("걷는 시간(26분)이 일정의 이동 시간(15분)보다 깁니다"), slower.getNote());

        // 정류장이 양쪽에 있지만 타고 내리려면 직선거리의 절반 넘게 걸어야 하는 구간: 역시 걸어간다
        TransitLegDto tooMuchWalk = TransitLegPlanner.plan(graph, TransitLegPlanner.ORIGINAL, LAT + 0.003, LNG + 0.020, "성터", LAT + 0.003, LNG + 0.036,
                "찻집", "도보 및 대중교통", 15, WEEKDAY, 600);
        assertEquals("도보", tooMuchWalk.getMode());

        // 도착지 근처에 정류장이 없으면(노선 자료가 없는 곳) 단정하지 않는다: 노선 미확인 추정으로 남긴다
        TransitLegDto notCovered = TransitLegPlanner.plan(graph, TransitLegPlanner.ORIGINAL, LAT + 0.003, LNG + 0.020, "성터", LAT + 0.014, LNG + 0.024,
                "산장", "도보 및 대중교통", 15, WEEKDAY, 600);
        assertEquals("버스 등", notCovered.getMode());
        assertEquals("distance", notCovered.getSource());

        // 걷기에 먼 거리(30분 초과)도 걸으라고 하지 않는다
        TransitLegDto far = TransitLegPlanner.plan(graph, TransitLegPlanner.ORIGINAL, LAT + 0.004, LNG, "북쪽 호텔", LAT + 0.004, LNG + 0.024,
                "성터 북문", "도보 및 대중교통", 20, LocalDate.of(2026, 10, 13), 23 * 60);
        assertFalse("도보".equals(far.getMode()), far.getMode() + " " + far.getSummary());

        // 교통망을 아직 가져오지 않았으면 예전처럼 거리 기준 추정
        TransitLegDto noGraph = TransitLegPlanner.plan(null, TransitLegPlanner.ORIGINAL, LAT + 0.001, LNG + 0.020, "성터", LAT + 0.001, LNG + 0.036,
                "찻집", "도보 및 대중교통", 15, WEEKDAY, 600);
        assertEquals("버스 등", noGraph.getMode());
    }

    // ---------------------------------------------------------------- 가상 피드

    private static RailGraph busGraph() throws Exception {
        GtfsNetwork network = new GtfsNetwork().add("mogi", GtfsReader.read(GtfsTestFeed.zip(), TODAY));
        return new RailGraph(new ArrayList<>(), new ArrayList<>(), network.stops, network.patterns);
    }

    private static boolean hasFlag(byte[] flags) {
        for (byte flag : flags) if (flag != 0) return true;
        return false;
    }

    private static GtfsFeed.Stop stop(GtfsFeed feed, String name) {
        return feed.stops.stream().filter(s -> s.name.equals(name)).findFirst().orElseThrow();
    }

    private static List<String> stopNames(GtfsFeed feed, GtfsFeed.Pattern pattern) {
        return Arrays.stream(pattern.stops).mapToObj(i -> feed.stops.get(i).name).collect(Collectors.toList());
    }

    private static GtfsFeed.Pattern pattern(GtfsFeed feed, String routeName, String first, String last) {
        for (GtfsFeed.Pattern p : feed.patterns) {
            if (p.routeName.equals(routeName) && feed.stops.get(p.stops[0]).name.equals(first)
                    && feed.stops.get(p.stops[p.stops.length - 1]).name.equals(last)) return p;
        }
        throw new AssertionError("패턴이 없습니다: " + routeName + " " + first + "→" + last);
    }
}
