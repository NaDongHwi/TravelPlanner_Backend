package com.travel.planner.service;

import com.travel.planner.dto.AiRouteResponse;
import com.travel.planner.dto.TransitLegDto;
import com.travel.planner.dto.GtfsSourceDto;
import com.travel.planner.entity.Place;
import com.travel.planner.entity.TransitFeed;
import com.travel.planner.entity.TransitName;
import com.travel.planner.entity.TransitPattern;
import com.travel.planner.entity.TransitStop;
import com.travel.planner.repository.TransitFeedRepository;
import com.travel.planner.repository.TransitLinkRepository;
import com.travel.planner.repository.TransitNameRepository;
import com.travel.planner.repository.TransitPatternRepository;
import com.travel.planner.repository.TransitStationRepository;
import com.travel.planner.repository.TransitStopRepository;
import com.travel.planner.transit.GtfsReader;
import com.travel.planner.transit.GtfsTestFeed;
import com.travel.planner.service.PlanService.SimulatedItinerary;
import com.travel.planner.service.PlanService.SimulatedItinerary.Type;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 일정에 교통 정보를 붙이는 부분과 GTFS 피드 저장·가져오기 테스트 (교통망 없이도 동작해야 하는 경로 포함). */
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
        assertTrue(noNetwork.getTransitNote().contains("아직 가져오지 않아"), noNetwork.getTransitNote());

        // 버스 구간의 요금이 운영사 요금표에서 나온 것이면 그렇게 적는다
        AiRouteResponse withBus = new AiRouteResponse();
        withBus.setReason("요약.");
        TransitLegDto bus = leg("버스", 250, "gtfs");
        bus.setFareSource("official");
        withBus.setTimeline(List.of(item(1, bus), item(1, leg("JR", 200, "rail-network"))));
        TransitTotals.apply(withBus, true);
        assertEquals(450, withBus.getTransitFareYen());
        assertTrue(withBus.getTransitNote().contains("버스 등 1개 구간의 요금은 운영사가 공개한 요금표"), withBus.getTransitNote());
        assertTrue(withBus.getTransitNote().contains("철도 구간은"), withBus.getTransitNote());
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

    // ---------------------------------------------------------------- GTFS 피드 저장·가져오기

    @Test
    void feedIsStoredReplacedDeletedAndUsedForLegs() throws Exception {
        Memory db = new Memory();
        GtfsStore store = new GtfsStore(db.feeds(), db.stops(), db.patterns());
        RailRouteService service = db.routeService();
        assertFalse(service.isNetworkLoaded());

        TransitFeed saved = store.replaceFeed("mogi", "모의시 버스", "https://example.jp/gtfs.zip", "CC BY 4.0 / もぎ市",
                GtfsReader.read(GtfsTestFeed.zip(), LocalDate.of(2026, 10, 11)));
        service.invalidate();

        assertEquals(TransitFeed.OK, saved.getStatus());
        assertEquals(7, saved.getStopCount());
        assertEquals(5, saved.getPatternCount());
        assertEquals(100, saved.getFareCoveragePercent());
        assertEquals("もぎ交通", saved.getAgencies());
        assertEquals(LocalDate.of(2027, 9, 30), saved.getValidTo());
        assertEquals(7, db.stopRows.size());
        assertEquals(5, db.patternRows.size());
        assertTrue(service.isNetworkLoaded(), "철도망 없이 버스 피드만 있어도 경로를 계산한다");
        assertFalse(service.isRailLoaded());
        assertEquals(7, service.stopCount());
        assertEquals(5, service.patternCount());

        // DB 에서 다시 읽어 만든 그래프로 구간을 계산한다: 요금표의 요금이 그대로 나온다
        double lat = GtfsTestFeed.LAT;
        double lng = GtfsTestFeed.LNG;
        TransitLegDto leg = service.leg(lat + 0.0005, lng + 0.0002, "호텔", lat + 0.0004, lng + 0.0398, "온천", "도보 및 대중교통", 40,
                LocalDate.of(2026, 10, 13), 9 * 60 + 30);
        assertEquals("버스", leg.getMode());
        assertEquals(250, leg.getFareYen());
        assertEquals("official", leg.getFareSource());
        assertEquals(6, leg.getSteps().get(2).getTripsPerDay());

        // 하루 일정에 붙일 때는 그 날의 요일을 쓴다: 공휴일(2026-10-12)에는 휴일 시간표의 3회
        Place hotel = place("호텔", lat + 0.0005, lng + 0.0002);
        Place onsen = place("온천", lat + 0.0004, lng + 0.0398);
        List<SimulatedItinerary> items = new ArrayList<>();
        items.add(new SimulatedItinerary(Type.START, hotel, null, 540, null, 0, hotel.getLatitude(), hotel.getLongitude()));
        items.add(new SimulatedItinerary(Type.VISIT, onsen, null, 600, 720, 40, onsen.getLatitude(), onsen.getLongitude()));
        List<TransitLegDto> holiday = service.legsFor(items, "도보 및 대중교통", LocalDate.of(2026, 10, 12));
        assertEquals(3, holiday.get(1).getSteps().get(2).getTripsPerDay());
        List<TransitLegDto> unknownDate = service.legsFor(items, "도보 및 대중교통");
        assertEquals(6, unknownDate.get(1).getSteps().get(2).getTripsPerDay(), "날짜를 모르면 평일 기준");

        // 같은 키로 다시 가져오면 그 피드만 통째로 바뀐다 (행이 쌓이지 않는다)
        store.replaceFeed("mogi", "모의시 버스", "https://example.jp/gtfs.zip", null, GtfsReader.read(GtfsTestFeed.zip(), LocalDate.of(2026, 10, 11)));
        assertEquals(7, db.stopRows.size());
        assertEquals(5, db.patternRows.size());
        assertEquals(1, db.feedRows.size());
        assertEquals("CC BY 4.0 / もぎ市", db.feedRows.get(0).getLicense(), "출처 표시는 새로 주지 않으면 그대로 둔다");

        // 유효 기간이 지난 파일은 상태가 EXPIRED 이고, 안내에 그 사실이 붙는다
        store.replaceFeed("mogi", "모의시 버스", "https://example.jp/gtfs.zip", null, GtfsReader.read(GtfsTestFeed.zip(), LocalDate.of(2028, 1, 15)));
        service.invalidate();
        assertEquals(TransitFeed.EXPIRED, db.feedRows.get(0).getStatus());
        TransitLegDto outdated = service.leg(lat + 0.0005, lng + 0.0002, "호텔", lat + 0.0004, lng + 0.0398, "온천", "도보 및 대중교통", 40);
        assertTrue(outdated.getNote().contains("유효 기간이 지났습니다"), outdated.getNote());

        // 실패 기록: 이전 자료는 그대로 두고 메시지만 남긴다
        store.markFailed("mogi", null, null, null, "HTTP 404");
        assertEquals(TransitFeed.EXPIRED, db.feedRows.get(0).getStatus());
        assertTrue(db.feedRows.get(0).getMessage().contains("이전에 가져온 자료를 계속 씁니다"), db.feedRows.get(0).getMessage());
        assertEquals(7, db.stopRows.size());
        store.markFailed("never", "한 번도 못 받은 피드", "https://example.jp/x.zip", null, "HTTP 403");
        assertEquals(TransitFeed.FAILED, db.feedRows.get(1).getStatus());

        // 삭제
        assertTrue(store.deleteFeed("mogi"));
        assertFalse(store.deleteFeed("mogi"));
        service.invalidate();
        assertEquals(0, db.stopRows.size());
        assertEquals(0, db.patternRows.size());
        assertEquals(0, service.stopCount());
    }

    @Test
    void importServiceReadsALocalFileAndReportsFailuresWithoutLosingData() throws Exception {
        Memory db = new Memory();
        GtfsStore store = new GtfsStore(db.feeds(), db.stops(), db.patterns());
        RailRouteService routes = db.routeService();
        GtfsImportService importer = new GtfsImportService(store, routes, db.feeds());
        Path zip = GtfsTestFeed.zip();

        GtfsSourceDto source = new GtfsSourceDto();
        source.setKey(" Mogi Bus!! ");
        source.setName("모의시 버스");
        source.setPath(zip.toString());
        source.setLicense("CC BY 4.0 / もぎ市");
        String summary = importer.importOne(source);
        assertTrue(summary.startsWith("mogi-bus (모의시 버스): 정류장 7개, 노선 패턴 5개, 요금 확인 100%"), summary);
        assertEquals("mogi-bus", db.feedRows.get(0).getFeedKey(), "키는 영문 소문자·숫자·- 로 다듬는다");
        assertEquals(7, db.stopRows.size());

        // 없는 파일: 예외로 알리고, 실패 사실을 피드에 남기되 이미 가져온 자료는 지우지 않는다
        source.setPath("no-such-file.zip");
        IOException error = assertThrows(IOException.class, () -> importer.importOne(source));
        assertTrue(error.getMessage().contains("파일이 없습니다"), error.getMessage());
        assertEquals(7, db.stopRows.size());
        assertTrue(db.feedRows.get(0).getMessage().contains("가져오기 실패"), db.feedRows.get(0).getMessage());

        // 주소도 파일도 없으면 거절
        GtfsSourceDto nothing = new GtfsSourceDto();
        nothing.setKey("x");
        assertThrows(IOException.class, () -> importer.importOne(nothing));
        assertThrows(IOException.class, () -> GtfsImportService.normalizeKey("  "));
        assertThrows(IOException.class, () -> GtfsImportService.normalizeKey("한글"));

        // ODPT 주소는 키가 있어야 한다. 키가 이미 들어 있거나 ODPT 주소가 아니면 그대로 둔다
        IOException needsKey = assertThrows(IOException.class,
                () -> importer.withOdptKey("https://api.odpt.org/api/v4/files/odpt/KyotoBus/AllLines.zip?date=current&acl:consumerKey="));
        assertTrue(needsKey.getMessage().contains("transit.odpt.consumer-key"), needsKey.getMessage());
        assertEquals("https://api.odpt.org/x.zip?acl:consumerKey=abc", importer.withOdptKey("https://api.odpt.org/x.zip?acl:consumerKey=abc"));
        assertEquals("https://example.jp/a.zip", importer.withOdptKey("https://example.jp/a.zip"));

        // 주소에 공백·일본어가 있어도 내려받을 수 있는 주소로 바꾼다 (이미 %XX 인 부분은 그대로)
        assertEquals("https://example.jp/GTFS%20(a).zip", GtfsImportService.toUri("https://example.jp/GTFS (a).zip").toString());
        assertEquals("https://example.jp/%E5%9D%82.zip", GtfsImportService.toUri("https://example.jp/坂.zip").toString());
        assertEquals("https://example.jp/%E5%9D%82.zip?date=current", GtfsImportService.toUri("https://example.jp/%E5%9D%82.zip?date=current").toString());
    }

    @Test
    void builtInFeedListIsWellFormed() {
        Memory db = new Memory();
        GtfsImportService importer = new GtfsImportService(new GtfsStore(db.feeds(), db.stops(), db.patterns()), db.routeService(), db.feeds());
        List<GtfsSourceDto> catalog = importer.catalog();

        assertTrue(catalog.size() >= 30, "기본 목록 " + catalog.size() + "개");
        Set<String> keys = new HashSet<>();
        for (GtfsSourceDto entry : catalog) {
            assertNotNull(entry.getKey());
            assertTrue(keys.add(entry.getKey()), "키가 겹친다: " + entry.getKey());
            assertTrue(entry.getKey().matches("[a-z0-9_-]{1,60}"), "키 형식: " + entry.getKey());
            assertTrue(entry.getUrl().startsWith("https://"), entry.getKey() + " 주소: " + entry.getUrl());
            assertTrue(entry.getName() != null && !entry.getName().isBlank(), entry.getKey() + " 이름");
            assertTrue(entry.getLicense() != null && !entry.getLicense().isBlank(), entry.getKey() + " 출처 표시");
            assertFalse(entry.getCities().isEmpty(), entry.getKey() + " 도시");
            assertEquals(Boolean.FALSE, entry.getImported());
        }
        // 고르기: 키로, 도시로
        assertEquals(List.of("toei-bus"), importer.select("toei-bus", null).stream().map(GtfsSourceDto::getKey).collect(Collectors.toList()));
        List<GtfsSourceDto> kyoto = importer.select(null, "교토");
        assertTrue(kyoto.size() >= 2 && kyoto.stream().allMatch(e -> e.getCities().contains("교토")), "교토 피드 " + kyoto.size() + "개");
        assertTrue(importer.select(null, "없는도시").isEmpty());
        assertEquals(catalog.size(), importer.select(null, null).size());
    }

    @Test
    void namesWithoutKoreanAreQueuedForTranslation() throws Exception {
        Memory db = new Memory();
        new GtfsStore(db.feeds(), db.stops(), db.patterns()).replaceFeed("mogi", "모의시 버스", null, null,
                GtfsReader.read(GtfsTestFeed.zip(), LocalDate.of(2026, 10, 11)));
        TransitNameService names = db.nameService();

        Map<String, List<String>> pending = names.pendingNames();
        assertEquals(List.of("山頂", "北町", "東町").stream().sorted().collect(Collectors.toList()),
                pending.get(TransitName.STATION).stream().sorted().collect(Collectors.toList()), "읽기 자료가 없는 정류장만 번역 대상");
        assertEquals(List.of("山頂線", "市内循環"), pending.get(TransitName.LINE).stream().sorted().collect(Collectors.toList()),
                "계통 번호 '10' 처럼 일본어가 없는 이름은 번역하지 않는다");
        assertEquals(List.of("もぎ交通"), pending.get(TransitName.OPERATOR));

        assertTrue(TransitNameService.hasJapanese("市役所前"));
        assertTrue(TransitNameService.hasJapanese("バス"));
        assertFalse(TransitNameService.hasJapanese("42"));
        assertFalse(TransitNameService.hasJapanese("A-1 Express"));
        assertFalse(TransitNameService.hasJapanese(null));
    }

    // ---------------------------------------------------------------- 준비

    /** 철도망·번역 표가 비어 있는 상태의 서비스 */
    private static RailRouteService serviceWithoutNetwork() {
        return new Memory().routeService();
    }

    /** 메모리에 행을 쌓아 두는 가짜 저장소 묶음 */
    private static final class Memory {
        final List<TransitFeed> feedRows = new ArrayList<>();
        final List<TransitStop> stopRows = new ArrayList<>();
        final List<TransitPattern> patternRows = new ArrayList<>();
        long nextId = 100;

        TransitFeedRepository feeds() {
            return proxy(TransitFeedRepository.class, (method, args) -> {
                switch (method) {
                    case "findAll": return new ArrayList<>(feedRows);
                    case "findByFeedKey": return feedRows.stream().filter(f -> f.getFeedKey().equals(args[0])).collect(Collectors.toList());
                    case "save": {
                        TransitFeed feed = (TransitFeed) args[0];
                        if (feed.getId() == null) {
                            feed.setId(nextId++);
                            feedRows.add(feed);
                        }
                        return feed;
                    }
                    case "deleteAll": feedRows.removeAll(toList(args[0])); return null;
                    default: return null;
                }
            });
        }

        TransitStopRepository stops() {
            return proxy(TransitStopRepository.class, (method, args) -> {
                switch (method) {
                    case "findAll": return new ArrayList<>(stopRows);
                    case "saveAll": {
                        List<Object> saved = toList(args[0]);
                        for (Object o : saved) {
                            ((TransitStop) o).setId(nextId++);
                            stopRows.add((TransitStop) o);
                        }
                        return saved;
                    }
                    case "deleteFeed": {
                        int before = stopRows.size();
                        stopRows.removeIf(s -> s.getFeedKey().equals(args[0]));
                        return before - stopRows.size();
                    }
                    case "findNamesWithoutKorean":
                        return stopRows.stream().filter(s -> s.getNameKo() == null).map(TransitStop::getName).distinct().collect(Collectors.toList());
                    default: return null;
                }
            });
        }

        TransitPatternRepository patterns() {
            return proxy(TransitPatternRepository.class, (method, args) -> {
                switch (method) {
                    case "findAll": return new ArrayList<>(patternRows);
                    case "saveAll": {
                        List<Object> saved = toList(args[0]);
                        for (Object o : saved) {
                            ((TransitPattern) o).setId(nextId++);
                            patternRows.add((TransitPattern) o);
                        }
                        return saved;
                    }
                    case "deleteFeed": {
                        int before = patternRows.size();
                        patternRows.removeIf(p -> p.getFeedKey().equals(args[0]));
                        return before - patternRows.size();
                    }
                    case "findRouteNames": return patternRows.stream().map(TransitPattern::getRouteName).distinct().collect(Collectors.toList());
                    case "findAgencies": return patternRows.stream().map(TransitPattern::getAgency).distinct().collect(Collectors.toList());
                    default: return null;
                }
            });
        }

        TransitNameService nameService() {
            return new TransitNameService(empty(TransitNameRepository.class), empty(TransitStationRepository.class), stops(), patterns(), null);
        }

        RailRouteService routeService() {
            return new RailRouteService(empty(TransitStationRepository.class), empty(TransitLinkRepository.class), stops(), patterns(), feeds(),
                    nameService(), FareMemory.emptyService(), new BusStopService());
        }
    }

    private interface Call {
        Object apply(String method, Object[] args);
    }

    @SuppressWarnings("unchecked")
    private static <T> T proxy(Class<T> type, Call call) {
        return (T) Proxy.newProxyInstance(TransitServiceTest.class.getClassLoader(), new Class<?>[]{type},
                (proxy, method, args) -> call.apply(method.getName(), args));
    }

    private static List<Object> toList(Object iterable) {
        List<Object> list = new ArrayList<>();
        for (Object o : (Iterable<?>) iterable) list.add(o);
        return list;
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
