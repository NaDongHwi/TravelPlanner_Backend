package com.travel.planner.transit;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 등록한 요금 자료로 요금을 내는 계산 테스트: 역 쌍 요금 → 운영사 요금표 → 추정의 순서, 영업킬로, 가산운임, 아동 요금.
 * 값은 설명하기 쉬운 가상의 표를 쓴다 (실제 자료는 BundledFareDataTest 에서 확인).
 */
class FareBookTest {

    private static final String JR_WEST = "西日本旅客鉄道";
    private static final String JR_CENTRAL = "東海旅客鉄道";
    private static final String METRO = "東京地下鉄";

    // ---------------------------------------------------------------- 요금표

    @Test
    void tableFareUsesDistanceRoundedUpToWholeKilometres() {
        FareBook book = book(tables(table("metro", METRO, "6:180,11:210,19:260")), none(), noKm());

        assertFare(book, 180, FareBook.Basis.TABLE, ride(RailCategory.SUBWAY, METRO, "銀座線", "A", "B", 6.0));
        // 6.1km 는 7km 로 올려 다음 구간 요금
        assertFare(book, 210, FareBook.Basis.TABLE, ride(RailCategory.SUBWAY, METRO, "銀座線", "A", "B", 6.1));
        assertFare(book, 180, FareBook.Basis.TABLE, ride(RailCategory.SUBWAY, METRO, "銀座線", "A", "B", 0.4));
        assertEquals("metro", book.price(List.of(ride(RailCategory.SUBWAY, METRO, "銀座線", "A", "B", 3))).tableKey);
    }

    @Test
    void distanceBeyondTheTableFallsBackToEstimate() {
        FareBook book = book(tables(table("metro", METRO, "6:180,11:210")), none(), noKm());
        RailGraph.Step far = ride(RailCategory.SUBWAY, METRO, "銀座線", "A", "B", 30);

        FareBook.Quote quote = book.price(List.of(far));
        assertEquals(FareBook.Basis.ESTIMATE, quote.basis);
        assertNull(quote.tableKey);
        assertEquals(RailFareModel.fare(RailCategory.SUBWAY, METRO, 30), quote.fareYen, "표를 벗어나면 종류별 대표 요금표로 추정");
    }

    @Test
    void ridesOfTheSameOperatorAreChargedOnceOnTheSummedDistance() {
        FareBook book = book(tables(table("metro", METRO, "6:180,11:210,19:260")), none(), noKm());
        // 4km + 4km = 8km → 210엔 한 번 (180엔 두 번이 아니다)
        FareBook.Quote quote = book.price(List.of(
                ride(RailCategory.SUBWAY, METRO, "銀座線", "A", "B", 4),
                ride(RailCategory.SUBWAY, METRO, "丸ノ内線", "B", "C", 4)));
        assertEquals(210, quote.fareYen);
        assertEquals(8.0, quote.fareKm, 1e-9);
    }

    @Test
    void operatorWithoutATableIsEstimatedAsBefore() {
        FareBook book = book(tables(table("metro", METRO, "6:180")), none(), noKm());
        RailGraph.Step other = ride(RailCategory.PRIVATE, "小田急電鉄", "小田原線", "A", "B", 12);

        FareBook.Quote quote = book.price(List.of(other));
        assertEquals(FareBook.Basis.ESTIMATE, quote.basis);
        assertEquals(RailFareModel.fare(RailCategory.PRIVATE, "小田急電鉄", 12), quote.fareYen);
        assertEquals(RailFareModel.childFare(quote.fareYen), quote.childFareYen);

        // 자료가 하나도 없을 때도 같은 값
        assertEquals(quote.fareYen, FareBook.EMPTY.price(List.of(other)).fareYen);
    }

    @Test
    void tableCanBeLimitedByCategoryAndLine() {
        FareBook.Table subwayOnly = table("toei", "東京都", "4:180,9:220");
        subwayOnly.categories.add(RailCategory.SUBWAY);
        subwayOnly.exceptLines.add("日暮里・舎人ライナー");
        FareBook.Table mainLine = table("meitetsu", "名古屋鉄道", "3:180,7:250");
        mainLine.lines.add("名古屋本線");
        FareBook book = book(tables(subwayOnly, mainLine), none(), noKm());

        assertFare(book, 180, FareBook.Basis.TABLE, ride(RailCategory.SUBWAY, "東京都", "10号線新宿線", "A", "B", 3));
        // 같은 운영사라도 종류가 다르면(노면전차) 이 표를 쓰지 않는다
        assertEquals(FareBook.Basis.ESTIMATE, book.price(List.of(ride(RailCategory.TRAM, "東京都", "荒川線", "A", "B", 3))).basis);
        // 제외한 노선
        assertEquals(FareBook.Basis.ESTIMATE, book.price(List.of(ride(RailCategory.SUBWAY, "東京都", "日暮里・舎人ライナー", "A", "B", 3))).basis);
        // 지정한 노선만 탔을 때만 적용: 다른 노선이 섞이면 추정
        assertFare(book, 250, FareBook.Basis.TABLE, ride(RailCategory.PRIVATE, "名古屋鉄道", "名古屋本線", "A", "B", 5));
        assertEquals(FareBook.Basis.ESTIMATE, book.price(List.of(
                ride(RailCategory.PRIVATE, "名古屋鉄道", "名古屋本線", "A", "B", 3),
                ride(RailCategory.PRIVATE, "名古屋鉄道", "常滑線", "B", "C", 2))).basis);
    }

    @Test
    void areaTableWinsOnlyWhenEveryStationIsInside() {
        FareBook.Table company = table("jr-west", JR_WEST, "10:200,20:330,50:860");
        FareBook.Table area = table("jr-west-area", JR_WEST, "10:200,20:320,50:840");
        area.boxes.add(new double[]{34.0, 135.0, 35.0, 136.0});
        FareBook book = book(tables(company, area), none(), noKm());

        RailGraph.Step inside = at(ride(RailCategory.JR, JR_WEST, "東海道線", "A", "B", 15), 34.5, 135.5, 34.6, 135.6);
        RailGraph.Step leaving = at(ride(RailCategory.JR, JR_WEST, "山陽線", "B", "C", 15), 34.6, 135.6, 34.6, 134.5);

        FareBook.Quote in = book.price(List.of(inside));
        assertEquals(320, in.fareYen, "범위 안에서만 탔으면 도시권 표");
        assertEquals("jr-west-area", in.tableKey);

        FareBook.Quote out = book.price(List.of(inside, leaving));
        assertEquals(860, out.fareYen, "한 역이라도 범위 밖이면 회사 전체 표 (30km)");
        assertEquals("jr-west", out.tableKey);

        // 좌표를 모르는 승차는 범위 안이라고 볼 수 없다
        assertEquals("jr-west", book.price(List.of(ride(RailCategory.JR, JR_WEST, "東海道線", "A", "B", 15))).tableKey);
    }

    @Test
    void jrBlockAcrossCompaniesUsesTheTableOfTheLongestPart() {
        FareBook.Table west = table("jr-west", JR_WEST, "50:1000,100:2000,200:3000");
        FareBook.Table central = table("jr-central", JR_CENTRAL, "50:1100,100:2100,200:3100");
        FareBook book = book(tables(west, central), none(), noKm());

        FareBook.Quote quote = book.price(List.of(
                ride(RailCategory.JR, JR_WEST, "東海道線", "A", "B", 30),
                ride(RailCategory.JR, JR_CENTRAL, "東海道線", "B", "C", 80)));
        assertEquals(3100, quote.fareYen, "110km 를 더 길게 탄 도카이의 표로");
        assertEquals("jr-central", quote.tableKey);
    }

    @Test
    void stationSurchargeIsAddedOnceForTheLargerEnd() {
        FareBook.Table table = table("jr-west", JR_WEST, "10:200,60:960");
        table.stationAdds.put("関西空港", 220);
        table.stationAdds.put("りんくうタウン", 160);
        FareBook book = book(tables(table), none(), noKm());

        assertFare(book, 1180, FareBook.Basis.TABLE, ride(RailCategory.JR, JR_WEST, "関西空港線", "大阪", "関西空港", 57));
        assertFare(book, 1180, FareBook.Basis.TABLE, ride(RailCategory.JR, JR_WEST, "関西空港線", "関西空港", "大阪", 57));
        assertFare(book, 960, FareBook.Basis.TABLE, ride(RailCategory.JR, JR_WEST, "阪和線", "大阪", "日根野", 46));
        // 양쪽 다 가산 역이면 큰 쪽만
        assertFare(book, 420, FareBook.Basis.TABLE, ride(RailCategory.JR, JR_WEST, "関西空港線", "りんくうタウン", "関西空港", 6));
    }

    // ---------------------------------------------------------------- 역 쌍 요금

    @Test
    void pairFareBeatsTheTableInEitherDirection() {
        FareBook book = book(tables(table("jr-west", JR_WEST, "45:770,50:860")), pairs(pair("JR", "大阪", "京都", 580, false)), noKm());

        FareBook.Quote there = book.price(List.of(ride(RailCategory.JR, JR_WEST, "東海道線", "大阪", "京都", 42.8)));
        assertEquals(580, there.fareYen);
        assertEquals(FareBook.Basis.OFFICIAL, there.basis);
        assertNull(there.tableKey);
        assertEquals(580, book.price(List.of(ride(RailCategory.JR, JR_WEST, "東海道線", "京都", "大阪", 42.8))).fareYen);

        // 묶음의 양 끝이 그 역 쌍일 때만: 한 정거장 더 가면 요금표로
        FareBook.Quote beyond = book.price(List.of(ride(RailCategory.JR, JR_WEST, "東海道線", "大阪", "山科", 48.3)));
        assertEquals(860, beyond.fareYen);
        assertEquals(FareBook.Basis.TABLE, beyond.basis);

        // 갈아타며 이어 탄 묶음도 처음 탄 역과 마지막 내린 역으로 찾는다
        assertEquals(580, book.price(List.of(
                ride(RailCategory.JR, JR_WEST, "東海道線", "大阪", "新大阪", 3.8),
                ride(RailCategory.JR, JR_WEST, "東海道線", "新大阪", "京都", 39.0))).fareYen);
    }

    @Test
    void pairOfAnotherOperatorGroupDoesNotApply() {
        FareBook book = book(tables(), pairs(pair("JR", "大阪", "京都", 580, false)), noKm());
        FareBook.Quote quote = book.price(List.of(ride(RailCategory.PRIVATE, "阪急電鉄", "京都本線", "大阪", "京都", 45)));
        assertEquals(FareBook.Basis.ESTIMATE, quote.basis, "JR 의 역 쌍 요금을 사철에 쓰면 안 된다");
    }

    @Test
    void pairFarFromTheDistanceFareIsIgnoredAsADifferentStation() {
        // 이름이 같은 다른 지역의 역: 등록 620엔인데 거리로는 9,000엔이 넘는 구간
        FareBook book = book(tables(table("jr-west", JR_WEST, "100:1690,600:9460")), pairs(pair("JR", "大久保", "八王子", 620, false)), noKm());

        FareBook.Quote quote = book.price(List.of(ride(RailCategory.JR, JR_WEST, "山陽線", "大久保", "八王子", 580)));
        assertEquals(9460, quote.fareYen);
        assertEquals(FareBook.Basis.TABLE, quote.basis);
    }

    @Test
    void pairChildFareIsUsedWhenGiven() {
        FareBook.Pair withChild = pair("南海電気鉄道", "りんくうタウン", "関西空港", 370, false);
        withChild.childYen = 190;
        FareBook book = book(tables(), pairs(withChild), noKm());
        FareBook.Quote quote = book.price(List.of(ride(RailCategory.PRIVATE, "南海電気鉄道", "空港線", "りんくうタウン", "関西空港", 6.9)));
        assertEquals(370, quote.fareYen);
        assertEquals(190, quote.childFareYen);
    }

    // ---------------------------------------------------------------- 신칸센: 영업킬로와 특급요금

    @Test
    void shinkansenFareUsesRegisteredOperatingKilometres() {
        Map<String, Map<String, Double>> km = new HashMap<>();
        km.put("東海道新幹線", FareBook.parseKm("東京:0,名古屋:366.0,京都:513.6,新大阪:552.6"));
        FareBook.Table central = table("jr-central", JR_CENTRAL, "380:6380,520:8360,540:8580,560:8910");
        FareBook book = book(tables(central), none(), km);

        // 선로 거리는 515km 지만 운임은 영업킬로 552.6km 로 계산한다
        RailGraph.Step nozomi = ride(RailCategory.SHINKANSEN, JR_CENTRAL, "東海道新幹線", "東京", "新大阪", 515.4);
        assertEquals(552.6, book.fareKm(nozomi), 1e-9);
        FareBook.Quote quote = book.price(List.of(nozomi));
        assertEquals(552.6, quote.fareKm, 1e-9);
        assertEquals(8910 + RailFareModel.shinkansenSurcharge(552.6), quote.fareYen);
        assertEquals(RailFareModel.shinkansenSurcharge(552.6), quote.surchargeYen);
        // 특급요금이 추정이므로 전체도 추정으로 표시한다
        assertEquals(FareBook.Basis.ESTIMATE, quote.basis);
        assertEquals("jr-central", quote.tableKey);

        // 표에 없는 역이 끼면 선로 거리를 그대로 쓴다
        RailGraph.Step unknown = ride(RailCategory.SHINKANSEN, JR_CENTRAL, "東海道新幹線", "東京", "静岡", 167.4);
        assertEquals(167.4, book.fareKm(unknown), 1e-9);
        // 방향이 반대여도 같은 거리
        assertEquals(147.6, book.fareKm(ride(RailCategory.SHINKANSEN, JR_CENTRAL, "東海道新幹線", "京都", "名古屋", 134)), 1e-9);
    }

    @Test
    void registeredSurchargeMakesTheShinkansenFareOfficialOrTable() {
        Map<String, Map<String, Double>> km = new HashMap<>();
        km.put("東海道新幹線", FareBook.parseKm("東京:0,新大阪:552.6"));
        km.put("山陽新幹線", FareBook.parseKm("新大阪:0,岡山:180.3"));
        FareBook.Table central = table("jr-central", JR_CENTRAL, "560:8910");
        central.childRoundDown = true;
        FareBook book = book(tables(central, table("jr-west", JR_WEST, "200:3410")),
                pairs(pair("JR", "東京", "新大阪", 4960, true), pair("JR", "新大阪", "岡山", 2530, true), pair("JR", "新大阪", "岡山", 3080, false)), km);

        FareBook.Quote tokyo = book.price(List.of(ride(RailCategory.SHINKANSEN, JR_CENTRAL, "東海道新幹線", "東京", "新大阪", 515.4)));
        assertEquals(13870, tokyo.fareYen, "운임 8,910 + 자유석 특급 4,960");
        assertEquals(4960, tokyo.surchargeYen);
        assertEquals(FareBook.Basis.TABLE, tokyo.basis, "운임은 요금표, 특급요금은 등록값");
        assertEquals(4450 + 2480, tokyo.childFareYen, "아동: 운임 절반(10엔 미만 버림: JR 표) + 특급 절반");

        FareBook.Quote okayama = book.price(List.of(ride(RailCategory.SHINKANSEN, JR_WEST, "山陽新幹線", "新大阪", "岡山", 160.8)));
        assertEquals(5610, okayama.fareYen, "역 쌍 운임 3,080 + 등록 특급 2,530");
        assertEquals(FareBook.Basis.OFFICIAL, okayama.basis);
    }

    @Test
    void surchargeAppliesToTheShinkansenPartOnly() {
        Map<String, Map<String, Double>> km = new HashMap<>();
        km.put("東海道新幹線", FareBook.parseKm("東京:0,新大阪:552.6"));
        FareBook book = book(tables(table("jr-central", JR_CENTRAL, "560:8910,600:9460")), pairs(pair("JR", "東京", "新大阪", 4960, true)), km);

        // 신오사카에서 재래선으로 오사카까지 3.8km 더 간다: 운임은 556.4km, 특급요금은 신칸센을 탄 도쿄–신오사카만
        FareBook.Quote quote = book.price(List.of(
                ride(RailCategory.SHINKANSEN, JR_CENTRAL, "東海道新幹線", "東京", "新大阪", 515.4),
                ride(RailCategory.JR, JR_WEST, "東海道線", "新大阪", "大阪", 3.8)));
        assertEquals(556.4, quote.fareKm, 1e-9);
        assertEquals(8910 + 4960, quote.fareYen);
        assertEquals(4960, quote.surchargeYen);
    }

    // ---------------------------------------------------------------- 아동 요금

    @Test
    void childFareRoundsDownForJrAndUpForOthers() {
        FareBook.Table jr = table("jr", JR_WEST, "3:150,6:190");
        jr.childRoundDown = true;
        FareBook book = book(tables(jr, table("metro", METRO, "6:180,11:210")), none(), noKm());

        // JR 150엔 → 75엔 → 70엔 (버림), 190엔 → 95엔 → 90엔
        assertEquals(70, book.price(List.of(ride(RailCategory.JR, JR_WEST, "東海道線", "A", "B", 2))).childFareYen);
        assertEquals(90, book.price(List.of(ride(RailCategory.JR, JR_WEST, "東海道線", "A", "B", 5))).childFareYen);
        // 지하철 210엔 → 105엔 → 110엔 (올림)
        assertEquals(110, book.price(List.of(ride(RailCategory.SUBWAY, METRO, "銀座線", "A", "B", 8))).childFareYen);
        assertEquals(90, book.price(List.of(ride(RailCategory.SUBWAY, METRO, "銀座線", "A", "B", 3))).childFareYen);
    }

    // ---------------------------------------------------------------- 표기 읽기

    @Test
    void textFormatsAreParsedAndRejectedWhenBroken() {
        assertEquals("3:150,6:190", FareBook.formatBands(FareBook.parseBands(" 3:150 , 6:190 ")));
        assertThrows(IllegalArgumentException.class, () -> FareBook.parseBands(""));
        assertThrows(IllegalArgumentException.class, () -> FareBook.parseBands("6:190,3:150"), "거리는 점점 커져야 한다");
        assertThrows(IllegalArgumentException.class, () -> FareBook.parseBands("3-150"));
        assertThrows(IllegalArgumentException.class, () -> FareBook.parseBands("3:abc"));
        assertThrows(IllegalArgumentException.class, () -> FareBook.parseBands("3:-10"));

        List<double[]> boxes = FareBook.parseBoxes("34.2,134.5,35.1,136.0; 35.5,139.6,35.8,139.9");
        assertEquals(2, boxes.size());
        assertEquals(35.8, boxes.get(1)[2], 1e-9);
        assertEquals(2, FareBook.parseBoxes(FareBook.formatBoxes(boxes)).size());
        assertTrue(FareBook.parseBoxes(null).isEmpty());
        assertThrows(IllegalArgumentException.class, () -> FareBook.parseBoxes("34,135,35"));
        assertThrows(IllegalArgumentException.class, () -> FareBook.parseBoxes("35,135,34,136"), "남쪽이 북쪽보다 크면 잘못");

        assertEquals(220, FareBook.parseAmounts("関西空港:220,りんくうタウン:160").get("関西空港"));
        assertEquals(50, FareBook.parseAmounts("羽田空港第1・第2ターミナル:50").get("羽田空港第1・第2ターミナル"));
        assertTrue(FareBook.parseAmounts(null).isEmpty());
        assertThrows(IllegalArgumentException.class, () -> FareBook.parseAmounts("関西空港=220"));

        assertEquals(6.8, FareBook.parseKm("東京:0,品川:6.8").get("品川"), 1e-9);
        assertThrows(IllegalArgumentException.class, () -> FareBook.parseKm("東京:0"), "역이 둘 이상 있어야 한다");

        assertTrue(FareBook.parseCategories("jr, SUBWAY").contains(RailCategory.JR));
        assertTrue(FareBook.parseCategories(null).isEmpty());
        assertThrows(IllegalArgumentException.class, () -> FareBook.parseCategories("AIRPLANE"));
    }

    @Test
    void routeIsSplitIntoFareBlocksPerOperatorGroup() {
        FareBook book = book(tables(table("jr-west", JR_WEST, "10:200,50:860"), table("metro", "大阪市高速電気軌道", "3:190,7:240")),
                pairs(pair("JR", "大阪", "京都", 580, false)), noKm());
        RailGraph.Route route = new RailGraph.Route();
        route.steps.add(step(RailGraph.Step.Kind.WALK));
        route.steps.add(ride(RailCategory.SUBWAY, "大阪市高速電気軌道", "御堂筋線", "難波", "梅田", 4.1));
        route.steps.add(step(RailGraph.Step.Kind.TRANSFER));
        route.steps.add(ride(RailCategory.JR, JR_WEST, "東海道線", "大阪", "新大阪", 3.8));
        route.steps.add(step(RailGraph.Step.Kind.TRANSFER));
        route.steps.add(ride(RailCategory.JR, JR_WEST, "東海道線", "新大阪", "京都", 39.0));
        route.steps.add(step(RailGraph.Step.Kind.TRANSFER));
        route.steps.add(ride(RailCategory.PRIVATE, "京福電気鉄道", "嵐山本線", "四条大宮", "嵐山", 7.2));
        route.steps.add(step(RailGraph.Step.Kind.WALK));

        RailGraph.priceRoute(route, book);

        assertEquals(3, route.fareBlocks.size(), "지하철 / JR(이어 탄 두 구간) / 사철");
        assertEquals(240, route.fareBlocks.get(0).fareYen);
        assertEquals(FareBook.Basis.TABLE, route.fareBlocks.get(0).basis);
        assertEquals(2, route.fareBlocks.get(1).rides.size());
        assertEquals(580, route.fareBlocks.get(1).fareYen);
        assertEquals(FareBook.Basis.OFFICIAL, route.fareBlocks.get(1).basis);
        assertEquals(FareBook.Basis.ESTIMATE, route.fareBlocks.get(2).basis);
        assertEquals(240 + 580 + route.fareBlocks.get(2).fareYen, route.fareYen);
        assertEquals(2, route.confirmedFareRides, "역 쌍 요금으로 낸 JR 두 구간");
        assertEquals(2, route.estimatedFareRides, "요금표에 거리를 넣은 지하철 + 추정한 사철");
        assertEquals(1, route.tableFareRides);
        // 승차 단계마다 자기 묶음을 가리킨다
        assertEquals(0, route.steps.get(1).fareBlock);
        assertEquals(1, route.steps.get(3).fareBlock);
        assertEquals(1, route.steps.get(5).fareBlock);
        assertEquals(2, route.steps.get(7).fareBlock);
        assertEquals(-1, route.steps.get(0).fareBlock);
    }

    // ---------------------------------------------------------------- 도우미

    private static void assertFare(FareBook book, int expected, FareBook.Basis basis, RailGraph.Step ride) {
        FareBook.Quote quote = book.price(List.of(ride));
        assertEquals(expected, quote.fareYen);
        assertEquals(basis, quote.basis);
    }

    static RailGraph.Step ride(RailCategory category, String operator, String line, String from, String to, double km) {
        RailGraph.Step step = new RailGraph.Step();
        step.kind = RailGraph.Step.Kind.RIDE;
        step.category = category;
        step.operator = operator;
        step.line = line;
        step.from = from;
        step.to = to;
        step.km = km;
        return step;
    }

    static RailGraph.Step at(RailGraph.Step step, double fromLat, double fromLng, double toLat, double toLng) {
        step.hasCoordinates = true;
        step.fromLat = fromLat;
        step.fromLng = fromLng;
        step.toLat = toLat;
        step.toLng = toLng;
        return step;
    }

    private static RailGraph.Step step(RailGraph.Step.Kind kind) {
        RailGraph.Step step = new RailGraph.Step();
        step.kind = kind;
        return step;
    }

    static FareBook.Table table(String key, String operator, String bands) {
        FareBook.Table table = new FareBook.Table();
        table.key = key;
        table.name = key;
        table.operators.add(operator);
        table.bands = FareBook.parseBands(bands);
        return table;
    }

    static FareBook.Pair pair(String group, String a, String b, int yen, boolean surcharge) {
        FareBook.Pair pair = new FareBook.Pair();
        pair.group = group;
        pair.stationA = a;
        pair.stationB = b;
        pair.adultYen = yen;
        pair.surcharge = surcharge;
        return pair;
    }

    private static FareBook book(List<FareBook.Table> tables, List<FareBook.Pair> pairs, Map<String, Map<String, Double>> km) {
        return new FareBook(tables, pairs, km);
    }

    private static List<FareBook.Table> tables(FareBook.Table... tables) {
        return new ArrayList<>(Arrays.asList(tables));
    }

    private static List<FareBook.Pair> pairs(FareBook.Pair... pairs) {
        return new ArrayList<>(Arrays.asList(pairs));
    }

    private static List<FareBook.Pair> none() {
        return new ArrayList<>();
    }

    private static Map<String, Map<String, Double>> noKm() {
        return new HashMap<>();
    }
}
