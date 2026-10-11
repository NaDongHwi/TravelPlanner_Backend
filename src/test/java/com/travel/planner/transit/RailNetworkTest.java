package com.travel.planner.transit;

import com.travel.planner.dto.TransitLegDto;
import com.travel.planner.transit.RailNetworkParser.RawNetwork;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 전국 철도망 기능 테스트: 파일 읽기 → 역 순서·거리 계산 → 환승 포함 경로 → 요금·시간 추정.
 * 실제 파일 대신, 같은 형식으로 만든 작은 가상 노선망을 쓴다.
 *
 *   JR "山手線"   : A1 ─ A2 ─ A3 ─ A4 ─ A5   (동서 방향, 역 간 약 1.8km)
 *   지하철 "銀座線": B1 ─ B2 ─ B3             (남북 방향, B2 는 A3 과 같은 역 그룹)
 *   신칸센        : C1 ─ C2                   (C1 은 A5 와 같은 역 그룹, C2 는 약 100km 동쪽)
 *   노면전차       : D1 ─ D2                   (D1 은 B3 에서 약 300m: 이름은 다르지만 걸어서 환승)
 */
class RailNetworkTest {

    private static final double LAT = 35.68;
    private static final double LNG = 139.70;
    private static final double STEP = 0.02;      // 역 간격(경도): 약 1.81km

    // ---------------------------------------------------------------- 파일 읽기와 역 순서

    @Test
    void stationsAreOrderedAlongTheTrackWithTrackDistance() throws Exception {
        RailTopologyBuilder.Result topology = topology();

        assertEquals(12, topology.stations.size(), "노선별 역 12개 (A5 + B3 + C2 + D2)");
        assertEquals(0, topology.unlinkedStations);

        // 山手線: 이웃한 역끼리만 이어지고(A1–A3 처럼 건너뛰지 않는다) 거리는 선로를 따라 잰 값
        List<String> links = linkNames(topology, "山手線");
        assertEquals(List.of("A1-A2", "A2-A3", "A3-A4", "A4-A5"), links);
        for (RailTopologyBuilder.LinkRecord link : topology.links) {
            if (!"山手線".equals(topology.stations.get(link.from).line)) continue;
            assertTrue(Math.abs(link.km - 1.81) < 0.05, "역 간 거리 " + link.km + "km (기대 약 1.81km)");
        }
        assertEquals(List.of("B1-中央", "B3-中央"), linkNames(topology, "銀座線"));
        assertEquals(RailCategory.JR, station(topology, "A1").category);
        assertEquals(RailCategory.SUBWAY, station(topology, "B1").category);
        assertEquals(RailCategory.SHINKANSEN, station(topology, "C2").category);
        assertEquals(RailCategory.TRAM, station(topology, "D1").category);
    }

    @Test
    void stationOffTheTrackVertexIsSnappedAndFarOneIsReportedAsUnlinked() throws Exception {
        RawNetwork network = network();
        // 선로 꼭짓점과 정확히 겹치지 않는(약 40m 떨어진) 역: 가장 가까운 꼭짓점에 붙어 이웃이 생긴다
        network.stations.add(rawStation("11", "2", "山手線", "東日本旅客鉄道", "A2b", "X1", "GX1",
                new double[][]{{LAT + 0.0004, LNG + STEP * 1.5}}));
        // 선로에서 5km 떨어진 역: 붙일 곳이 없다
        network.stations.add(rawStation("11", "2", "山手線", "東日本旅客鉄道", "遠い駅", "X2", "GX2",
                new double[][]{{LAT + 0.05, LNG}}));
        RailTopologyBuilder.Result topology = RailTopologyBuilder.build(network);

        assertEquals(1, topology.unlinkedStations);
        List<String> links = linkNames(topology, "山手線");
        assertTrue(links.contains("A2-A2b") && links.contains("A2b-A3"), "사이에 낀 역이 양쪽 이웃과 이어져야 한다: " + links);
        assertFalse(links.contains("A2-A3"), "사이에 역이 생겼으면 A2–A3 은 더 이상 이웃이 아니다");
    }

    @Test
    void propertyKeysAndGeometryVariantsAreAccepted() throws Exception {
        String json = "{\"type\":\"FeatureCollection\",\"name\":\"x\",\"features\":["
                + "{\"type\":\"Feature\",\"properties\":{\"n02_001\":12,\"N02_002\":\"4\",\"N02_003\":\"線\",\"N02_004\":\"会社\",\"N02_005\":\"駅\",\"N02_005c\":\"001\",\"N02_005g\":\"001\"},"
                + "\"geometry\":{\"type\":\"MultiLineString\",\"coordinates\":[[[139.7,35.6],[139.701,35.6]],[[139.702,35.6],[139.703,35.6]]]}},"
                + "{\"type\":\"Feature\",\"properties\":{\"N02_005\":null},\"geometry\":{\"type\":\"Point\",\"coordinates\":[139.7,35.6]}}"
                + "],\"crs\":{\"ignored\":[1,2,{\"a\":3}]}}";
        RawNetwork network = new RawNetwork();
        RailNetworkParser.readStations(new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8)), network);
        assertEquals(1, network.stations.size(), "역명이 없는 항목은 버린다");
        assertEquals("12", network.stations.get(0).railClass, "숫자로 들어온 코드·소문자 키도 읽는다");
        assertEquals(4, network.stations.get(0).points.size());
        assertEquals(35.6, network.stations.get(0).points.get(0)[0], "좌표는 [경도, 위도] 순서로 들어 있다");

        RawNetwork sections = new RawNetwork();
        RailNetworkParser.readSections(new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8)), sections);
        assertEquals(2, sections.sections.size(), "MultiLineString 은 조각마다 선로 구간 하나");
    }

    // ---------------------------------------------------------------- 경로

    @Test
    void directRideOnOneLine() throws Exception {
        RailGraph graph = RailGraph.of(topology());
        // A1 근처(약 100m) → A4 근처
        RailGraph.Route route = graph.route(LAT + 0.001, LNG, LAT + 0.001, LNG + STEP * 3);

        assertNotNull(route);
        assertEquals(0, route.transfers);
        List<RailGraph.Step> rides = rides(route);
        assertEquals(1, rides.size());
        assertEquals("A1", rides.get(0).from);
        assertEquals("A4", rides.get(0).to);
        assertEquals(3, rides.get(0).stops);
        assertEquals("山手線", rides.get(0).line);
        assertTrue(Math.abs(route.railKm - 5.4) < 0.2, "선로 거리 " + route.railKm);
        assertEquals(RailFareModel.jrFare(route.railKm), route.fareYen);
        assertEquals(190, route.fareYen, "JR 4~6km 구간");
        // 걷기 2분 + 승차 대기 5분 + 3구간(각 약 2.3분) + 걷기 2분
        assertTrue(route.minutes >= 14 && route.minutes <= 20, "소요 " + route.minutes + "분");
        assertEquals(RailGraph.Step.Kind.WALK, route.steps.get(0).kind);
        assertEquals(RailGraph.Step.Kind.WALK, route.steps.get(route.steps.size() - 1).kind);
    }

    @Test
    void transferBetweenOperatorsChargesEachOperatorSeparately() throws Exception {
        RailGraph graph = RailGraph.of(topology());
        // A1 근처 → B3 근처: 山手線 A1→A3(=中央) 에서 銀座線으로 갈아타 B3 까지
        RailGraph.Route route = graph.route(LAT, LNG - 0.001, LAT + STEP, LNG + STEP * 2 + 0.001);

        assertNotNull(route);
        assertEquals(1, route.transfers);
        List<RailGraph.Step> rides = rides(route);
        assertEquals(2, rides.size());
        assertEquals("山手線", rides.get(0).line);
        assertEquals("A3", rides.get(0).to);
        assertEquals("銀座線", rides.get(1).line);
        assertEquals("B3", rides.get(1).to);
        int expected = RailFareModel.jrFare(rides.get(0).km) + RailFareModel.fare(RailCategory.SUBWAY, "東京地下鉄", rides.get(1).km);
        assertEquals(expected, route.fareYen, "회사가 바뀌면 요금을 따로 낸다");
        assertEquals(190 + 180, route.fareYen, "JR 4~6km 190엔 + 도쿄메트로 6km 이하 180엔");
        assertEquals(RailFareModel.childFare(route.fareYen), route.childFareYen);
    }

    @Test
    void shinkansenAddsExpressSurchargeOnJrFare() throws Exception {
        RailGraph graph = RailGraph.of(topology());
        // A4 근처 → C2 근처: 山手線 A4→A5 후 신칸센 C1→C2
        RailGraph.Route route = graph.route(LAT, LNG + STEP * 3, LAT, LNG + STEP * 4 + 1.1);

        assertNotNull(route);
        List<RailGraph.Step> rides = rides(route);
        assertEquals(RailCategory.SHINKANSEN, rides.get(rides.size() - 1).category);
        double shinkansenKm = rides.get(rides.size() - 1).km;
        assertTrue(Math.abs(shinkansenKm - 99.5) < 2.0, "신칸센 구간 " + shinkansenKm + "km");
        // JR 은 재래선 + 신칸센 거리를 이어서 기본 운임을 한 번만 내고, 신칸센 구간에 특급요금이 붙는다
        assertEquals(RailFareModel.jrFare(route.railKm) + RailFareModel.shinkansenSurcharge(shinkansenKm), route.fareYen);
        assertTrue(route.minutes < 70, "100km 를 신칸센으로: " + route.minutes + "분");
    }

    @Test
    void nearbyStationOfAnotherLineIsAWalkingTransfer() throws Exception {
        RailGraph graph = RailGraph.of(topology());
        // B1 근처 → D2 근처: 銀座線 B1→B3, 300m 걸어 D1 에서 노면전차로 D2
        RailGraph.Route route = graph.route(LAT - STEP, LNG + STEP * 2, LAT + STEP + 0.0027, LNG + STEP * 2 + 0.03);

        assertNotNull(route);
        assertEquals(1, route.transfers);
        RailGraph.Step transfer = route.steps.stream().filter(s -> s.kind == RailGraph.Step.Kind.TRANSFER).findFirst().orElseThrow();
        assertEquals("B3", transfer.from);
        assertEquals("D1", transfer.to);
        assertTrue(transfer.minutes >= 8, "300m 걷기 + 대기: " + transfer.minutes + "분");
        assertEquals(RailFareModel.fare(RailCategory.SUBWAY, "東京地下鉄", rides(route).get(0).km) + 200, route.fareYen, "노면전차는 균일 요금");
    }

    @Test
    void noRouteWhenNoStationIsWithinWalkingDistance() throws Exception {
        RailGraph graph = RailGraph.of(topology());
        assertNull(graph.route(LAT + 0.5, LNG, LAT, LNG + STEP), "출발지 근처에 역이 없으면 철도 경로 없음");
        assertNull(graph.route(LAT, LNG, LAT + 0.5, LNG), "도착지 근처에 역이 없으면 철도 경로 없음");
        assertNull(graph.route(LAT, LNG, LAT + 0.0005, LNG), "같은 역 주변끼리는 열차를 타지 않는다");
    }

    // ---------------------------------------------------------------- 구간 안내(도보·철도·미확인·자동차)

    @Test
    void legPlannerChoosesWalkRailOrDistanceEstimate() throws Exception {
        RailGraph graph = RailGraph.of(topology());
        TransitLegPlanner.Names names = new TransitLegPlanner.Names() {
            @Override public String station(String ja) { return "A1".equals(ja) ? "에이원" : ja; }
            @Override public String line(String ja) { return "山手線".equals(ja) ? "야마노테선" : ja; }
            @Override public String operator(String ja) { return "東日本旅客鉄道".equals(ja) ? "JR 동일본" : ja; }
        };
        String transit = "도보 및 대중교통";

        // 600m: 걷는다 (요금 0)
        TransitLegDto walk = TransitLegPlanner.plan(graph, names, LAT, LNG, "가", LAT, LNG + 0.0066, "나", transit, 15);
        assertEquals("도보", walk.getMode());
        assertEquals(0, walk.getFareYen());

        // 약 5.4km, 역이 양쪽에 있다: 철도
        TransitLegDto rail = TransitLegPlanner.plan(graph, names, LAT + 0.001, LNG, "호텔", LAT + 0.001, LNG + STEP * 3, "미술관", transit, 30);
        assertEquals("JR", rail.getMode());
        assertEquals("rail-network", rail.getSource());
        assertEquals(190, rail.getFareYen());
        assertTrue(rail.isEstimated());
        assertEquals("JR 동일본 야마노테선 에이원역 → A4역 (3정거장)", rail.getSummary());
        assertEquals("호텔", rail.getSteps().get(0).getFrom());
        assertEquals("에이원역", rail.getSteps().get(0).getTo());
        assertEquals("미술관", rail.getSteps().get(rail.getSteps().size() - 1).getTo());

        // 같은 구간이지만 철도망을 아직 가져오지 않았을 때: 노선 미확인, 거리 기준 요금
        TransitLegDto unknown = TransitLegPlanner.plan(null, names, LAT + 0.001, LNG, "호텔", LAT + 0.001, LNG + STEP * 3, "미술관", transit, 30);
        assertEquals("버스 등", unknown.getMode());
        assertEquals("distance", unknown.getSource());
        assertEquals(RailFareModel.unknownLocalFare(unknown.getDistanceKm()), unknown.getFareYen());
        assertEquals(30, unknown.getMinutes(), "노선을 모르면 일정의 이동 시간을 그대로 쓴다");

        // 역이 없는 곳(산 위 전망대 등): 철도 경로가 없으므로 거리 기준
        TransitLegDto remote = TransitLegPlanner.plan(graph, names, LAT + 0.3, LNG, "산", LAT + 0.35, LNG, "전망대", transit, 30);
        assertEquals("버스 등", remote.getMode());

        // 렌터카: 요금을 만들지 않는다
        TransitLegDto car = TransitLegPlanner.plan(graph, names, LAT, LNG, "가", LAT, LNG + STEP * 3, "나", "렌트카", 20);
        assertEquals("자동차", car.getMode());
        assertNull(car.getFareYen());

        assertNull(TransitLegPlanner.plan(graph, names, LAT, LNG, "가", LAT, LNG + 0.0001, "나", transit, 5), "같은 자리는 이동 없음");
    }

    @Test
    void fareTablesMatchKnownPublishedFares() {
        // JR 혼슈 간선 보통운임표 (2025년까지의 표): 도쿄–신오사카 552.6km 8,910엔, 도쿄–교토 513.6km 8,360엔
        assertEquals(8910, RailFareModel.jrFare(552.6));
        assertEquals(8360, RailFareModel.jrFare(513.6));
        assertEquals(150, RailFareModel.jrFare(1.2));
        assertEquals(1690, RailFareModel.jrFare(100.0));
        assertEquals(1980, RailFareModel.jrFare(100.1));
        assertTrue(RailFareModel.jrFare(1200) > RailFareModel.jrFare(1000));
        assertEquals(0, RailFareModel.shinkansenSurcharge(0));
        assertEquals(4960, RailFareModel.shinkansenSurcharge(552.6));
        assertEquals(100, RailFareModel.childFare(190), "아동은 반액, 10엔 단위 올림");
        assertEquals(RailCategory.SUBWAY, RailFareModel.categoryOf("21", "4", "大阪市高速電気軌道"), "법적으로 궤도여도 지하철 회사는 지하철");
        assertEquals(RailCategory.TRAM, RailFareModel.categoryOf("21", "3", "東京都"));
        assertEquals(RailCategory.SUBWAY, RailFareModel.categoryOf("12", "3", "東京都"));
        assertEquals(RailCategory.THIRD_SECTOR, RailFareModel.categoryOf("12", "5", "えちごトキめき鉄道"));
        assertEquals(RailCategory.PRIVATE, RailFareModel.categoryOf("12", "4", "阪急電鉄"));
        // 회사별 요금표가 있는 지하철 (2023년 개정 요금): 도쿄메트로 아사쿠사–시부야 14.2km 260엔, 오사카메트로 우메다–난바 4.2km 240엔
        assertEquals(260, RailFareModel.fare(RailCategory.SUBWAY, "東京地下鉄", 14.2));
        assertEquals(240, RailFareModel.fare(RailCategory.SUBWAY, "大阪市高速電気軌道", 4.2));
        assertEquals(RailFareModel.fare(RailCategory.SUBWAY, 4.2), RailFareModel.fare(RailCategory.SUBWAY, "福岡市", 4.2), "표가 없는 회사는 대표 요금표");
    }

    @Test
    void shinkansenIsTakenOnlyWhenItSavesEnoughTime() throws Exception {
        // 재래선(5km 마다 역)과 나란히 달리는 신칸센(양 끝에만 역).
        //   40km: 재래선 약 45분, 신칸센 약 24분 → 21분 빠르지만 요금이 훨씬 비싸므로 재래선 (25분 이상 빨라질 때만 신칸센)
        //  100km: 재래선 약 105분, 신칸센 약 39분 → 신칸센
        for (int km : new int[]{40, 100}) {
            double span = km / 90.4;                                   // 이 위도에서 경도 1도 ≈ 90.4km
            int stops = km / 5;
            List<String> stations = new ArrayList<>();
            List<String> sections = new ArrayList<>();
            for (int i = 0; i <= stops; i++) {
                double lng = LNG + span * i / stops;
                stations.add(stationFeature("11", "2", "在来線", "東日本旅客鉄道", "L" + i, "L" + i, i == 0 ? "G-W" : i == stops ? "G-E" : "GL" + i,
                        new double[][]{{LAT, lng}, {LAT, lng + 0.001}}));
            }
            sections.add(sectionFeature("11", "2", "在来線", "東日本旅客鉄道", horizontal(LAT, LNG, LNG + span + 0.001)));
            stations.add(stationFeature("11", "1", "新幹線", "東日本旅客鉄道", "S0", "S0", "G-W", new double[][]{{LAT + 0.0005, LNG}, {LAT + 0.0005, LNG + 0.001}}));
            stations.add(stationFeature("11", "1", "新幹線", "東日本旅客鉄道", "S1", "S1", "G-E",
                    new double[][]{{LAT + 0.0005, LNG + span}, {LAT + 0.0005, LNG + span + 0.001}}));
            sections.add(sectionFeature("11", "1", "新幹線", "東日本旅客鉄道", horizontal(LAT + 0.0005, LNG, LNG + span + 0.001)));
            RawNetwork network = new RawNetwork();
            RailNetworkParser.readStations(stream(stations), network);
            RailNetworkParser.readSections(stream(sections), network);
            RailGraph graph = RailGraph.of(RailTopologyBuilder.build(network));

            RailGraph.Route route = graph.route(LAT, LNG, LAT, LNG + span);
            assertNotNull(route);
            boolean shinkansen = rides(route).stream().anyMatch(r -> r.category == RailCategory.SHINKANSEN);
            if (km == 40) {
                assertFalse(shinkansen, "40km: 20분쯤 빨라지는 정도로는 신칸센을 타지 않는다 (" + route.minutes + "분)");
                assertEquals(RailFareModel.jrFare(route.railKm), route.fareYen);
                assertEquals(stops, rides(route).get(0).stops);
            } else {
                assertTrue(shinkansen, "100km: 한 시간 넘게 빨라지면 신칸센을 탄다 (" + route.minutes + "분)");
                assertTrue(route.fareYen > RailFareModel.jrFare(route.railKm), "특급요금이 붙는다");
            }
        }
    }

    @Test
    void bypassTrackDoesNotCreateALinkThatSkipsAStation() throws Exception {
        // A2 를 지나지 않는 통과선(A1 과 A3 을 바로 잇는 선로)이 있어도 A1–A3 을 이웃으로 만들지 않는다
        RawNetwork network = network();
        RailNetworkParser.RawSection bypass = new RailNetworkParser.RawSection();
        bypass.railClass = "11";
        bypass.operatorType = "2";
        bypass.line = "山手線";
        bypass.operator = "東日本旅客鉄道";
        bypass.points.add(new double[]{LAT, LNG + 0.001});                  // A1 승강장 끝
        bypass.points.add(new double[]{LAT + 0.002, LNG + STEP});            // A2 옆을 살짝 비켜 지나간다
        bypass.points.add(new double[]{LAT, LNG + STEP * 2 - 0.001});        // A3 승강장 끝
        network.sections.add(bypass);
        List<String> links = linkNames(RailTopologyBuilder.build(network), "山手線");
        assertEquals(List.of("A1-A2", "A2-A3", "A3-A4", "A4-A5"), links);
    }

    // ---------------------------------------------------------------- 가상 노선망

    private static RailTopologyBuilder.Result topology() throws Exception {
        return RailTopologyBuilder.build(network());
    }

    /** GeoJSON 문자열을 만들어 실제 파서로 읽는다 (파일 형식까지 함께 검증하기 위해). */
    private static RawNetwork network() throws Exception {
        List<String> stations = new ArrayList<>();
        List<String> sections = new ArrayList<>();

        // JR 山手線: 동서 방향. 선로는 0.005도 간격의 꼭짓점으로 이루어지고, 여러 조각으로 나뉘어 있다.
        for (int i = 0; i < 5; i++) {
            double lng = LNG + STEP * i;
            stations.add(stationFeature("11", "2", "山手線", "東日本旅客鉄道", "A" + (i + 1), "A" + i, i == 2 ? "G-CENTER" : i == 4 ? "G-EAST" : "GA" + i,
                    new double[][]{{LAT, lng - 0.001}, {LAT, lng}, {LAT, lng + 0.001}}));
        }
        sections.add(sectionFeature("11", "2", "山手線", "東日本旅客鉄道", horizontal(LAT, LNG - 0.001, LNG + STEP * 2 + 0.001)));
        sections.add(sectionFeature("11", "2", "山手線", "東日本旅客鉄道", horizontal(LAT, LNG + STEP * 2 + 0.001, LNG + STEP * 4 + 0.001)));

        // 지하철 銀座線: 남북 방향, 가운데 역은 A3 과 같은 자리·같은 그룹
        double crossLng = LNG + STEP * 2;
        String[] subwayNames = {"B1", "中央", "B3"};
        for (int i = 0; i < 3; i++) {
            double lat = LAT + STEP * (i - 1);
            stations.add(stationFeature("12", "4", "銀座線", "東京地下鉄", subwayNames[i], "B" + i, i == 1 ? "G-CENTER" : "GB" + i,
                    new double[][]{{lat - 0.001, crossLng}, {lat, crossLng}, {lat + 0.001, crossLng}}));
        }
        sections.add(sectionFeature("12", "4", "銀座線", "東京地下鉄", vertical(crossLng, LAT - STEP - 0.001, LAT + STEP + 0.001)));

        // 신칸센: C1 은 A5 와 같은 그룹, C2 는 1.1도(약 100km) 동쪽
        double eastLng = LNG + STEP * 4;
        stations.add(stationFeature("11", "1", "東海道新幹線", "東海旅客鉄道", "C1", "C0", "G-EAST",
                new double[][]{{LAT + 0.0005, eastLng}, {LAT + 0.0005, eastLng + 0.001}}));
        stations.add(stationFeature("11", "1", "東海道新幹線", "東海旅客鉄道", "C2", "C1", "GC1",
                new double[][]{{LAT + 0.0005, eastLng + 1.1}, {LAT + 0.0005, eastLng + 1.101}}));
        sections.add(sectionFeature("11", "1", "東海道新幹線", "東海旅客鉄道", horizontal(LAT + 0.0005, eastLng, eastLng + 1.101)));

        // 노면전차: D1 은 B3 에서 북쪽으로 약 300m (이름·그룹이 달라도 걸어서 환승)
        double tramLat = LAT + STEP + 0.0027;
        stations.add(stationFeature("21", "4", "電車線", "路面電鉄", "D1", "D0", "GD0", new double[][]{{tramLat, crossLng}, {tramLat, crossLng + 0.001}}));
        stations.add(stationFeature("21", "4", "電車線", "路面電鉄", "D2", "D1", "GD1", new double[][]{{tramLat, crossLng + 0.03}, {tramLat, crossLng + 0.031}}));
        sections.add(sectionFeature("21", "4", "電車線", "路面電鉄", horizontal(tramLat, crossLng, crossLng + 0.031)));

        RawNetwork network = new RawNetwork();
        RailNetworkParser.readStations(stream(stations), network);
        RailNetworkParser.readSections(stream(sections), network);
        return network;
    }

    private static ByteArrayInputStream stream(List<String> features) {
        String json = "{\"type\":\"FeatureCollection\",\"features\":[" + String.join(",", features) + "]}";
        return new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8));
    }

    private static double[][] horizontal(double lat, double fromLng, double toLng) {
        List<double[]> points = new ArrayList<>();
        int steps = (int) Math.round((toLng - fromLng) / 0.001);
        for (int i = 0; i <= steps; i++) points.add(new double[]{lat, fromLng + 0.001 * i});
        return points.toArray(new double[0][]);
    }

    private static double[][] vertical(double lng, double fromLat, double toLat) {
        List<double[]> points = new ArrayList<>();
        int steps = (int) Math.round((toLat - fromLat) / 0.001);
        for (int i = 0; i <= steps; i++) points.add(new double[]{fromLat + 0.001 * i, lng});
        return points.toArray(new double[0][]);
    }

    private static String stationFeature(String railClass, String operatorType, String line, String operator, String name,
                                         String code, String group, double[][] points) {
        return String.format(Locale.ROOT, "{\"type\":\"Feature\",\"properties\":{\"N02_001\":\"%s\",\"N02_002\":\"%s\",\"N02_003\":\"%s\","
                        + "\"N02_004\":\"%s\",\"N02_005\":\"%s\",\"N02_005c\":\"%s\",\"N02_005g\":\"%s\"},\"geometry\":%s}",
                railClass, operatorType, line, operator, name, code, group, lineString(points));
    }

    private static String sectionFeature(String railClass, String operatorType, String line, String operator, double[][] points) {
        return String.format(Locale.ROOT, "{\"type\":\"Feature\",\"properties\":{\"N02_001\":\"%s\",\"N02_002\":\"%s\",\"N02_003\":\"%s\","
                + "\"N02_004\":\"%s\"},\"geometry\":%s}", railClass, operatorType, line, operator, lineString(points));
    }

    private static String lineString(double[][] points) {
        StringBuilder sb = new StringBuilder("{\"type\":\"LineString\",\"coordinates\":[");
        for (int i = 0; i < points.length; i++) {
            if (i > 0) sb.append(',');
            sb.append(String.format(Locale.ROOT, "[%.5f,%.5f]", points[i][1], points[i][0]));
        }
        return sb.append("]}").toString();
    }

    private static RailNetworkParser.RawStation rawStation(String railClass, String operatorType, String line, String operator,
                                                           String name, String code, String group, double[][] points) {
        RailNetworkParser.RawStation s = new RailNetworkParser.RawStation();
        s.railClass = railClass;
        s.operatorType = operatorType;
        s.line = line;
        s.operator = operator;
        s.name = name;
        s.code = code;
        s.groupCode = group;
        for (double[] p : points) s.points.add(p);
        return s;
    }

    private static RailTopologyBuilder.StationRecord station(RailTopologyBuilder.Result topology, String name) {
        return topology.stations.stream().filter(s -> s.name.equals(name)).findFirst().orElseThrow();
    }

    /** 그 노선의 이웃 역 쌍을 "이름-이름"(가나다순)으로 */
    private static List<String> linkNames(RailTopologyBuilder.Result topology, String line) {
        List<String> names = new ArrayList<>();
        for (RailTopologyBuilder.LinkRecord link : topology.links) {
            RailTopologyBuilder.StationRecord a = topology.stations.get(link.from);
            RailTopologyBuilder.StationRecord b = topology.stations.get(link.to);
            if (!line.equals(a.line)) continue;
            names.add(a.name.compareTo(b.name) <= 0 ? a.name + "-" + b.name : b.name + "-" + a.name);
        }
        names.sort(String::compareTo);
        return names;
    }

    private static List<RailGraph.Step> rides(RailGraph.Route route) {
        List<RailGraph.Step> rides = new ArrayList<>();
        for (RailGraph.Step s : route.steps) if (s.kind == RailGraph.Step.Kind.RIDE) rides.add(s);
        return rides;
    }
}
