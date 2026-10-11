package com.travel.planner.transit;

import com.travel.planner.dto.TransitLegDto;
import com.travel.planner.service.FareDataService;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 경로 탐색 → 요금 자료 적용 → 구간 안내(TransitLegDto)까지 이어서 확인한다.
 * 실제 역 이름·좌표·거리로 만든 작은 노선망에, 프로젝트에 들어 있는 기본 요금 자료를 그대로 쓴다.
 *
 *   오사카메트로 미도스지선 : 難波 ─ 梅田
 *   JR 도카이도선          : 大阪 ─ 新大阪 ─ 京都          (大阪 는 梅田 에서 걸어서 환승)
 *   JR 산인선              : 京都 ─ 嵯峨嵐山
 *   도카이도 신칸센         : 新大阪 ─ 京都 ─ 名古屋
 */
class FareRouteTest {

    private static final String JR_WEST = "西日本旅客鉄道";
    private static final double[] NAMBA = {34.6667, 135.5008};
    private static final double[] OSAKA = {34.7025, 135.4959};
    private static final double[] SHIN_OSAKA = {34.7335, 135.5002};
    private static final double[] KYOTO = {34.9858, 135.7588};
    private static final double[] SAGA = {35.0186, 135.6810};
    private static final double[] NAGOYA = {35.1709, 136.8815};

    @Test
    void withoutFareDataEverythingIsEstimatedAsBefore() {
        RailGraph graph = graph();
        RailGraph.Route route = graph.route(NAMBA[0], NAMBA[1], KYOTO[0], KYOTO[1]);
        assertNotNull(route);
        assertEquals(2, route.fareBlocks.size());
        for (RailGraph.FareBlock block : route.fareBlocks) assertEquals(FareBook.Basis.ESTIMATE, block.basis);
        assertEquals(RailFareModel.fare(RailCategory.SUBWAY, "大阪市高速電気軌道", 4.1) + RailFareModel.jrFare(42.8), route.fareYen);

        TransitLegDto leg = TransitLegPlanner.plan(graph, TransitLegPlanner.ORIGINAL, NAMBA[0], NAMBA[1], "숙소", KYOTO[0], KYOTO[1], "교토역", "대중교통", 90);
        assertEquals("estimate", leg.getFareSource());
        assertTrue(leg.getNote().contains("전국 철도망 기준 추정"), leg.getNote());
    }

    @Test
    void registeredFaresReplaceTheEstimates() {
        RailGraph graph = graph();
        graph.setFareBook(FareDataService.bundledBook());

        // 난바 → 교토역: 지하철 4.1km 는 오사카메트로 요금표(240엔), JR 오사카–교토는 특정 구간 운임(580엔)
        RailGraph.Route route = graph.route(NAMBA[0], NAMBA[1], KYOTO[0], KYOTO[1]);
        assertEquals(2, route.fareBlocks.size());
        assertEquals(240, route.fareBlocks.get(0).fareYen);
        assertEquals(FareBook.Basis.TABLE, route.fareBlocks.get(0).basis);
        assertEquals("osaka-metro", route.fareBlocks.get(0).tableKey);
        assertEquals(580, route.fareBlocks.get(1).fareYen);
        assertEquals(FareBook.Basis.OFFICIAL, route.fareBlocks.get(1).basis);
        assertEquals(820, route.fareYen);
        assertEquals(120 + 290, route.childFareYen);

        TransitLegDto leg = TransitLegPlanner.plan(graph, TransitLegPlanner.ORIGINAL, NAMBA[0], NAMBA[1], "숙소", KYOTO[0], KYOTO[1], "교토역", "대중교통", 90);
        assertEquals(820, leg.getFareYen());
        assertEquals("table", leg.getFareSource(), "추정이 없고 요금표를 쓴 구간이 있다");
        List<TransitLegDto.Step> rides = leg.getSteps().stream().filter(s -> "승차".equals(s.getType())).collect(Collectors.toList());
        assertEquals(2, rides.size());
        assertEquals(240, rides.get(0).getFareYen());
        assertEquals("table", rides.get(0).getFareBasis());
        assertEquals(Boolean.FALSE, rides.get(0).getFareConfirmed());
        assertEquals(580, rides.get(1).getFareYen());
        assertEquals("official", rides.get(1).getFareBasis());
        assertEquals(Boolean.TRUE, rides.get(1).getFareConfirmed());
        assertNull(rides.get(1).getFareThroughTo());
        assertTrue(leg.getNote().contains("운영사 요금표"), leg.getNote());
        assertTrue(leg.getNote().contains("확정 요금"), leg.getNote());
        assertFalse(leg.getNote().contains("운영사별 실제 요금표는 반영하지 않았습니다"), leg.getNote());

        // 패스 계산용 정보: 요금을 낸 단위마다 운영사·종류·좌표가 원문으로 남는다 (응답 JSON 에는 나가지 않는다)
        assertEquals(2, leg.getFareRides().size());
        PassAdvisor.Ride subway = leg.getFareRides().get(0);
        assertEquals(240, subway.fareYen);
        assertFalse(subway.estimated);
        assertEquals("大阪市高速電気軌道", subway.legs.get(0).operator);
        assertEquals(RailCategory.SUBWAY, subway.legs.get(0).category);
        assertTrue(subway.legs.get(0).hasCoordinates);
        assertEquals("難波", subway.legs.get(0).from);
    }

    @Test
    void sameCompanyAcrossATransferIsOneTicket() {
        RailGraph graph = graph();
        graph.setFareBook(FareDataService.bundledBook());

        // 오사카역 → 사가아라시야마: JR 도카이도선 + 산인선 = 53.1km. 게이한신 전차특정구간 표의 51~60km 960엔을 한 번만 낸다
        TransitLegDto leg = TransitLegPlanner.plan(graph, TransitLegPlanner.ORIGINAL, OSAKA[0], OSAKA[1], "오사카역", SAGA[0], SAGA[1], "아라시야마", "대중교통", 120);
        assertEquals(960, leg.getFareYen());
        assertEquals("table", leg.getFareSource());
        List<TransitLegDto.Step> rides = leg.getSteps().stream().filter(s -> "승차".equals(s.getType())).collect(Collectors.toList());
        assertEquals(2, rides.size());
        assertEquals(960, rides.get(0).getFareYen(), "묶음의 첫 승차에 요금이 들어간다");
        assertEquals("嵯峨嵐山역", rides.get(0).getFareThroughTo(), "이 요금으로 어디까지 가는지");
        assertNull(rides.get(1).getFareYen(), "이어 탄 구간에는 요금을 다시 적지 않는다");
        assertNull(rides.get(1).getFareBasis());
        assertEquals(1, leg.getFareRides().size());
        assertEquals(2, leg.getFareRides().get(0).legs.size());
    }

    @Test
    void shinkansenUsesOperatingKilometresAndShowsTheSurcharge() {
        RailGraph graph = graph();
        RailGraph.Route estimated = graph.route(SHIN_OSAKA[0], SHIN_OSAKA[1], NAGOYA[0], NAGOYA[1]);
        graph.setFareBook(FareDataService.bundledBook());
        RailGraph.Route route = graph.route(SHIN_OSAKA[0], SHIN_OSAKA[1], NAGOYA[0], NAGOYA[1]);

        // 선로 거리는 173km 지만 영업킬로는 552.6 − 366.0 = 186.6km → 운임 3,410엔 (173km 로 치면 3,080엔)
        RailGraph.FareBlock block = route.fareBlocks.get(0);
        assertEquals(186.6, block.fareKm, 0.01);
        assertEquals(2530, block.surchargeYen);
        assertEquals(3410 + 2530, block.fareYen);
        assertEquals("jr-central", block.tableKey);
        assertEquals(FareBook.Basis.ESTIMATE, block.basis, "이 구간의 특급요금은 등록되어 있지 않아 추정");
        assertEquals(3080 + 2530, estimated.fareYen);

        TransitLegDto leg = TransitLegPlanner.plan(graph, TransitLegPlanner.ORIGINAL, SHIN_OSAKA[0], SHIN_OSAKA[1], "신오사카역", NAGOYA[0], NAGOYA[1], "나고야역", "대중교통", 120);
        TransitLegDto.Step ride = leg.getSteps().stream().filter(s -> "승차".equals(s.getType())).findFirst().orElseThrow();
        assertEquals(5940, ride.getFareYen());
        assertEquals(2530, ride.getSurchargeYen());
        assertEquals("estimate", ride.getFareBasis());
        assertTrue(leg.getNote().contains("자유석"), leg.getNote());
    }

    @Test
    void fareDataCanBeSwappedWithoutRebuildingTheNetwork() {
        RailGraph graph = graph();
        graph.setFareBook(FareDataService.bundledBook());
        assertEquals(820, graph.route(NAMBA[0], NAMBA[1], KYOTO[0], KYOTO[1]).fareYen);

        // 관리자가 요금표를 고친 경우: 그래프는 그대로 두고 요금 자료만 바꾼다
        FareBook.Table raised = FareBookTest.table("osaka-metro", "大阪市高速電気軌道", "3:200,7:250");
        List<FareBook.Table> tables = new ArrayList<>();
        tables.add(raised);
        graph.setFareBook(new FareBook(tables, new ArrayList<>(), new java.util.HashMap<>()));
        RailGraph.Route route = graph.route(NAMBA[0], NAMBA[1], KYOTO[0], KYOTO[1]);
        assertEquals(250, route.fareBlocks.get(0).fareYen);
        assertEquals(FareBook.Basis.ESTIMATE, route.fareBlocks.get(1).basis);

        graph.setFareBook(null);
        assertTrue(graph.fareBook().isEmpty());
    }

    // ---------------------------------------------------------------- 가상 노선망

    private static RailGraph graph() {
        List<RailGraph.Station> stations = new ArrayList<>();
        List<double[]> links = new ArrayList<>();
        String metro = "大阪市高速電気軌道";
        add(stations, "難波", metro, "1号線(御堂筋線)", "M-NAMBA", RailCategory.SUBWAY, NAMBA);                    // 0
        add(stations, "梅田", metro, "1号線(御堂筋線)", "M-UMEDA", RailCategory.SUBWAY, new double[]{34.7033, 135.4984}); // 1
        add(stations, "大阪", JR_WEST, "東海道線", "J-OSAKA", RailCategory.JR, OSAKA);                               // 2
        add(stations, "新大阪", JR_WEST, "東海道線", "J-SHINOSAKA", RailCategory.JR, SHIN_OSAKA);                    // 3
        add(stations, "京都", JR_WEST, "東海道線", "J-KYOTO", RailCategory.JR, KYOTO);                               // 4
        add(stations, "京都", JR_WEST, "山陰線", "J-KYOTO", RailCategory.JR, KYOTO);                                 // 5
        add(stations, "嵯峨嵐山", JR_WEST, "山陰線", "J-SAGA", RailCategory.JR, SAGA);                               // 6
        add(stations, "新大阪", "東海旅客鉄道", "東海道新幹線", "J-SHINOSAKA", RailCategory.SHINKANSEN, SHIN_OSAKA);  // 7
        add(stations, "京都", "東海旅客鉄道", "東海道新幹線", "J-KYOTO", RailCategory.SHINKANSEN, KYOTO);             // 8
        add(stations, "名古屋", "東海旅客鉄道", "東海道新幹線", "J-NAGOYA", RailCategory.SHINKANSEN, NAGOYA);         // 9
        links.add(new double[]{0, 1, 4.1});
        links.add(new double[]{2, 3, 3.8});
        links.add(new double[]{3, 4, 39.0});
        links.add(new double[]{5, 6, 10.3});
        links.add(new double[]{7, 8, 39.0});
        links.add(new double[]{8, 9, 134.0});
        return new RailGraph(stations, links);
    }

    private static void add(List<RailGraph.Station> stations, String name, String operator, String line, String group, RailCategory category, double[] at) {
        stations.add(new RailGraph.Station(stations.size(), name, operator, line, group, category, at[0], at[1]));
    }
}
