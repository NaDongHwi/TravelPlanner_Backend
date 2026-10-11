package com.travel.planner.service;

import com.travel.planner.dto.FarePairDto;
import com.travel.planner.dto.FareTableDto;
import com.travel.planner.dto.LineKmDto;
import com.travel.planner.dto.TransportPassDto;
import com.travel.planner.entity.TransitFareTable;
import com.travel.planner.entity.TransportPass;
import com.travel.planner.transit.FareBook;
import com.travel.planner.transit.PassAdvisor;
import com.travel.planner.transit.RailCategory;
import com.travel.planner.transit.RailGraph;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 요금 자료·패스의 등록과 관리 테스트, 그리고 프로젝트에 들어 있는 기본 자료(fare-tables.json, passes.json) 자체의 점검.
 * 기본 자료의 값은 운영사가 공개한 요금과 맞는지 대표 구간으로 확인한다.
 */
class FareDataServiceTest {

    // ---------------------------------------------------------------- 기본 자료 점검

    @Test
    void bundledFilesLoadCompletely() {
        FareMemory memory = new FareMemory();
        String message = memory.service.syncBundled();

        assertFalse(message.contains("읽지 못한 항목"), message);
        FareDataService.FareSeed fares = FareDataService.bundledFares();
        FareDataService.PassSeed passes = FareDataService.bundledPasses();
        assertEquals(fares.getTables().size(), memory.tables.size());
        assertEquals(fares.getPairs().size(), memory.pairs.size());
        assertEquals(fares.getLineKm().size(), memory.lineKms.size());
        assertEquals(passes.getPasses().size(), memory.passes.size());
        assertTrue(memory.tables.size() >= 20 && memory.passes.size() >= 20, "기본 자료가 비어 있으면 안 된다");
        assertTrue(memory.tables.stream().allMatch(t -> TransitFareTable.BUNDLED.equals(t.getSource())));

        // 모든 항목에 확인한 날과 출처가 적혀 있어야 한다 (어디서 본 값인지 모르는 숫자를 넣지 않는다)
        for (FareTableDto table : fares.getTables()) {
            assertNotNull(table.getVerifiedAt(), table.getKey());
            assertTrue(table.getReference() != null && table.getReference().startsWith("http"), table.getKey());
        }
        for (FarePairDto pair : fares.getPairs()) assertTrue(pair.getReference() != null && pair.getReference().startsWith("http"), pair.getA() + "–" + pair.getB());
        for (LineKmDto line : fares.getLineKm()) assertNotNull(line.getNote(), line.getLine());
        Set<String> keys = new HashSet<>();
        for (TransportPassDto pass : passes.getPasses()) {
            assertTrue(keys.add(pass.getKey()), "패스 키가 겹친다: " + pass.getKey());
            assertTrue(pass.getUrl() != null && pass.getUrl().startsWith("http"), pass.getKey());
            assertNotNull(pass.getVerifiedAt(), pass.getKey());
            assertNotNull(pass.getCoverageDescription(), pass.getKey());
            assertFalse(pass.getCoverage().isEmpty(), pass.getKey());
        }

        // 메모리에 올린 결과
        FareBook book = memory.service.fareBook();
        assertEquals(memory.tables.size(), book.tableCount());
        assertEquals(memory.pairs.size(), book.pairCount());
        assertEquals(8, book.lineKmCount(), "신칸센 8개 노선");
        assertEquals(memory.passes.size(), memory.service.passes().size());
        assertEquals(book.tableCount(), memory.service.status().get("fareTables"));
    }

    /** 요금 구간은 거리가 늘면 요금도 같거나 늘어야 한다 (옮겨 적다 생기는 오타를 잡는다) */
    @Test
    void bundledFareBandsNeverDecrease() {
        for (FareTableDto dto : FareDataService.bundledFares().getTables()) {
            int[][] bands = FareBook.parseBands(dto.getBands());
            for (int i = 1; i < bands.length; i++) {
                assertTrue(bands[i][1] >= bands[i - 1][1], dto.getKey() + ": " + bands[i][0] + "km 요금이 앞 구간보다 싸다");
                assertTrue(bands[i][1] - bands[i - 1][1] <= 600, dto.getKey() + ": " + bands[i][0] + "km 에서 요금이 너무 크게 뛴다");
            }
            assertTrue(bands[0][1] >= 100 && bands[0][1] <= 300, dto.getKey() + ": 첫 구간 요금 " + bands[0][1]);
        }
    }

    @Test
    void bundledLineKilometresIncreaseAlongTheLine() {
        for (LineKmDto dto : FareDataService.bundledFares().getLineKm()) {
            double previous = -1;
            for (double km : FareBook.parseKm(dto.getStations()).values()) {
                assertTrue(km > previous, dto.getLine() + ": 누적 거리가 줄어든다 (" + km + ")");
                previous = km;
            }
        }
    }

    @Test
    void bundledValuesReproducePublishedFares() {
        FareBook book = FareDataService.bundledBook();

        // 지하철: 운영사 공식 요금표
        assertEquals(180, fare(book, RailCategory.SUBWAY, "東京地下鉄", "3号線銀座線", "A", "B", 6.0));
        assertEquals(210, fare(book, RailCategory.SUBWAY, "東京地下鉄", "3号線銀座線", "A", "B", 6.4));
        assertEquals(260, fare(book, RailCategory.SUBWAY, "東京地下鉄", "3号線銀座線", "浅草", "渋谷", 14.3));
        assertEquals(220, fare(book, RailCategory.SUBWAY, "東京都", "12号線大江戸線", "A", "B", 5.0));
        assertEquals(240, fare(book, RailCategory.SUBWAY, "大阪市高速電気軌道", "1号線(御堂筋線)", "梅田", "難波", 4.1));
        assertEquals(390, fare(book, RailCategory.SUBWAY, "大阪市高速電気軌道", "1号線(御堂筋線)", "A", "B", 24.5), "19km 를 넘으면 5구 390엔");
        assertEquals(220, fare(book, RailCategory.SUBWAY, "京都市", "烏丸線", "京都", "四条", 1.8));
        assertEquals(210, fare(book, RailCategory.SUBWAY, "名古屋市", "1号線東山線", "名古屋", "栄", 2.4));
        assertEquals(260, fare(book, RailCategory.SUBWAY, "福岡市", "1号線(空港線)", "博多", "福岡空港", 3.3));
        assertEquals(290, fare(book, RailCategory.SUBWAY, "札幌市", "南北線", "真駒内", "さっぽろ", 9.4));

        // JR: 2026년 3월 개정 뒤의 동일본 (도쿄–신주쿠 10.3km 260엔), 홋카이도의 공항 가산
        assertEquals(260, fare(book, RailCategory.JR, "東日本旅客鉄道", "中央線", "東京", "新宿", 10.3));
        assertEquals(160, fare(book, RailCategory.JR, "東日本旅客鉄道", "山手線", "A", "B", 1.2));
        assertEquals(1230, fare(book, RailCategory.JR, "北海道旅客鉄道", "千歳線", "札幌", "新千歳空港", 46.6));
        // JR 특정 구간 운임
        assertEquals(580, fare(book, RailCategory.JR, "西日本旅客鉄道", "東海道線", "大阪", "京都", 42.8));
        assertEquals(620, fare(book, RailCategory.JR, "東日本旅客鉄道", "中央線", "新宿", "八王子", 37.1));
        assertEquals(350, fare(book, RailCategory.JR, "東日本旅客鉄道", "東海道線", "品川", "横浜", 22.0));
        assertEquals(720, fare(book, RailCategory.JR, "西日本旅客鉄道", "奈良線", "京都", "奈良", 41.7));

        // 게이한신 전차특정구간: 범위 안에서는 싼 표, 밖으로 나가면 간선 표
        RailGraph.Step loop = ride(RailCategory.JR, "西日本旅客鉄道", "大阪環状線", "大阪", "天王寺", 10.7);
        locate(loop, 34.7025, 135.4959, 34.6473, 135.5139);
        assertEquals(240, book.price(List.of(loop)).fareYen);
        assertEquals("jr-west-keihanshin", book.price(List.of(loop)).tableKey);
        RailGraph.Step airport = ride(RailCategory.JR, "西日本旅客鉄道", "関西空港線", "天王寺", "関西空港", 46.0);
        locate(airport, 34.6473, 135.5139, 34.4320, 135.2440);
        assertEquals(1060, book.price(List.of(airport)).fareYen, "840엔 + 간사이공항선 가산 220엔");
        RailGraph.Step okayama = ride(RailCategory.JR, "西日本旅客鉄道", "山陽線", "姫路", "岡山", 88.6);
        locate(okayama, 34.8274, 134.6906, 34.6665, 133.9177);
        assertEquals("jr-west", book.price(List.of(okayama)).tableKey);
        assertEquals(1520, book.price(List.of(okayama)).fareYen);

        // 사철: 공항 가산 포함
        assertEquals(970, fare(book, RailCategory.PRIVATE, "南海電気鉄道", "空港線", "難波", "関西空港", 42.8));
        assertEquals(330, fare(book, RailCategory.PRIVATE, "京浜急行電鉄", "空港線", "品川", "羽田空港第1・第2ターミナル", 14.5));
        assertEquals(280, fare(book, RailCategory.PRIVATE, "京浜急行電鉄", "空港線", "品川", "羽田空港第3ターミナル", 12.2), "제3터미널은 가산 없음");
        assertEquals(410, fare(book, RailCategory.PRIVATE, "阪急電鉄", "京都本線", "大阪梅田", "京都河原町", 47.7));
        assertEquals(420, fare(book, RailCategory.PRIVATE, "阪神電気鉄道", "阪神なんば線", "大阪難波", "神戸三宮", 32.1));
        assertEquals(680, fare(book, RailCategory.PRIVATE, "近畿日本鉄道", "奈良線", "大阪難波", "近鉄奈良", 32.8));
        assertEquals(490, fare(book, RailCategory.PRIVATE, "京阪電気鉄道", "京阪本線", "淀屋橋", "祇園四条", 49.3));
        assertEquals(550, fare(book, RailCategory.PRIVATE, "京阪電気鉄道", "鴨東線", "淀屋橋", "出町柳", 51.6), "490엔 + 오토선 가산 60엔");

        // 신칸센: 영업킬로 + 운임표 + 자유석 특급요금
        assertEquals(13870, fare(book, RailCategory.SHINKANSEN, "東海旅客鉄道", "東海道新幹線", "東京", "新大阪", 515.4));
        assertEquals(10560, fare(book, RailCategory.SHINKANSEN, "東海旅客鉄道", "東海道新幹線", "東京", "名古屋", 342.0));
        assertEquals(13320, fare(book, RailCategory.SHINKANSEN, "東海旅客鉄道", "東海道新幹線", "東京", "京都", 476.3));
        assertEquals(9890, fare(book, RailCategory.SHINKANSEN, "西日本旅客鉄道", "山陽新幹線", "新大阪", "広島", 305.4));
        assertEquals(5610, fare(book, RailCategory.SHINKANSEN, "西日本旅客鉄道", "山陽新幹線", "新大阪", "岡山", 160.8));
        assertEquals(14750, fare(book, RailCategory.SHINKANSEN, "西日本旅客鉄道", "山陽新幹線", "新大阪", "博多", 553.7));
        // 도쿄–하카타: 도카이도 + 산요를 이어 탄 한 묶음 (1,174.9km → 14,080엔 + 8,140엔)
        assertEquals(22220, book.price(List.of(
                ride(RailCategory.SHINKANSEN, "東海旅客鉄道", "東海道新幹線", "東京", "新大阪", 515.4),
                ride(RailCategory.SHINKANSEN, "西日本旅客鉄道", "山陽新幹線", "新大阪", "博多", 553.7))).fareYen);
    }

    @Test
    void bundledPassesCoverWhatTheySay() {
        List<PassAdvisor.Pass> passes = FareDataService.bundledPassList();
        PassAdvisor.Pass subwayTicket = pass(passes, "tokyo-subway-ticket-24h");
        assertEquals(1000, subwayTicket.priceYen);
        assertEquals(24, subwayTicket.hours);
        assertTrue(subwayTicket.covers(rideOf("東京地下鉄", RailCategory.SUBWAY, "3号線銀座線")));
        assertTrue(subwayTicket.covers(rideOf("東京都", RailCategory.SUBWAY, "12号線大江戸線")));
        assertFalse(subwayTicket.covers(rideOf("東京都", RailCategory.SUBWAY, "日暮里・舎人ライナー")));
        assertFalse(subwayTicket.covers(rideOf("東京都", RailCategory.TRAM, "荒川線")));
        assertFalse(subwayTicket.covers(rideOf("東日本旅客鉄道", RailCategory.JR, "山手線")));

        PassAdvisor.Pass toei = pass(passes, "toei-1day");
        assertTrue(toei.covers(rideOf("東京都", RailCategory.TRAM, "荒川線")));
        assertTrue(toei.covers(rideOf("東京都", RailCategory.SUBWAY, "日暮里・舎人ライナー")));
        assertFalse(toei.covers(rideOf("東京地下鉄", RailCategory.SUBWAY, "3号線銀座線")));

        PassAdvisor.Pass jrPass = pass(passes, "japan-rail-pass-7d");
        assertEquals(7, jrPass.days);
        assertTrue(jrPass.covers(rideOf("東海旅客鉄道", RailCategory.SHINKANSEN, "東海道新幹線")));
        assertTrue(jrPass.covers(rideOf("九州旅客鉄道", RailCategory.JR, "鹿児島線")));
        assertFalse(jrPass.covers(rideOf("東京地下鉄", RailCategory.SUBWAY, "3号線銀座線")));

        PassAdvisor.Pass ecoWeekday = pass(passes, "osaka-enjoy-eco-weekday");
        assertEquals(PassAdvisor.DayRule.WEEKDAY, ecoWeekday.dayRule);
        assertEquals(PassAdvisor.DayRule.HOLIDAY, pass(passes, "osaka-enjoy-eco-holiday").dayRule);
        PassAdvisor.Pass donichi = pass(passes, "nagoya-donichi-eco");
        assertTrue(donichi.extraDaysOfMonth.contains(8));
        assertTrue(pass(passes, "sapporo-donichika").yearEndAsHoliday);

        // 간사이 패스: 게이한선은 되고 오쓰선(노면전차)은 안 된다, JR 은 안 된다
        PassAdvisor.Pass kansai = pass(passes, "kansai-railway-pass-lite-2d");
        assertTrue(kansai.consecutive);
        assertTrue(kansai.covers(rideOf("京阪電気鉄道", RailCategory.PRIVATE, "京阪本線")));
        assertFalse(kansai.covers(rideOf("京阪電気鉄道", RailCategory.TRAM, "京津線")));
        assertFalse(kansai.covers(rideOf("西日本旅客鉄道", RailCategory.JR, "東海道線")));
        assertFalse(kansai.covers(rideOf("京都市", RailCategory.SUBWAY, "烏丸線")));
    }

    // ---------------------------------------------------------------- 기본 자료와 관리자가 넣은 값

    @Test
    void manualRowsOverrideBundledOnesAndSurviveResync() {
        FareMemory memory = new FareMemory();
        memory.service.syncBundled();
        int bundledCount = memory.tables.size();
        RailGraph.Step ride = ride(RailCategory.SUBWAY, "東京地下鉄", "3号線銀座線", "A", "B", 5);
        assertEquals(180, memory.service.fareBook().price(List.of(ride)).fareYen);

        // 운임이 올랐다고 가정하고 같은 키로 고친다
        FareTableDto raised = tableDto("tokyo-metro", "東京地下鉄", "6:190,11:220,19:270,27:310,40:340");
        FareTableDto saved = memory.service.saveTable(raised);
        assertEquals(TransitFareTable.MANUAL, saved.getSource());
        assertEquals(bundledCount, memory.tables.size(), "같은 키의 기본 자료를 대신한다 (두 장이 되지 않는다)");
        assertEquals(190, memory.service.fareBook().price(List.of(ride)).fareYen);

        // 서버를 다시 켜도(기본 자료를 다시 맞춰도) 관리자가 넣은 값이 남는다
        memory.service.syncBundled();
        assertEquals(bundledCount, memory.tables.size());
        assertEquals(190, memory.service.fareBook().price(List.of(ride)).fareYen);
        assertEquals(1, memory.tables.stream().filter(t -> t.getTableKey().equals("tokyo-metro")).count());

        // 지우고 다시 맞추면 기본 자료가 돌아온다
        assertTrue(memory.service.deleteTable("tokyo-metro"));
        assertFalse(memory.service.deleteTable("tokyo-metro"));
        assertEquals(RailFareModelEstimate.SUBWAY_5KM, memory.service.fareBook().price(List.of(ride)).fareYen, "표가 없으면 추정");
        memory.service.syncBundled();
        assertEquals(180, memory.service.fareBook().price(List.of(ride)).fareYen);
    }

    @Test
    void disabledManualTableSwitchesTheBundledOneOff() {
        FareMemory memory = new FareMemory();
        memory.service.syncBundled();
        FareTableDto off = tableDto("tokyo-metro", "東京地下鉄", "6:180");
        off.setEnabled(false);
        memory.service.saveTable(off);
        memory.service.syncBundled();

        RailGraph.Step ride = ride(RailCategory.SUBWAY, "東京地下鉄", "3号線銀座線", "A", "B", 5);
        assertEquals(FareBook.Basis.ESTIMATE, memory.service.fareBook().price(List.of(ride)).basis);
        assertTrue(memory.service.listTables().stream().anyMatch(t -> t.getKey().equals("tokyo-metro") && Boolean.FALSE.equals(t.getEnabled())));
    }

    @Test
    void invalidTableIsRejectedAndNothingIsSaved() {
        FareMemory memory = new FareMemory();
        assertThrows(IllegalArgumentException.class, () -> memory.service.saveTable(tableDto("", "小田急電鉄", "3:140")));
        assertThrows(IllegalArgumentException.class, () -> memory.service.saveTable(tableDto("odakyu", null, "3:140")));
        assertThrows(IllegalArgumentException.class, () -> memory.service.saveTable(tableDto("odakyu", "小田急電鉄", "6:190,3:140")));
        assertThrows(IllegalArgumentException.class, () -> memory.service.saveTable(tableDto("odakyu", "小田急電鉄", null)));
        FareTableDto badCategory = tableDto("odakyu", "小田急電鉄", "3:140");
        badCategory.getCategories().add("TRAIN");
        assertThrows(IllegalArgumentException.class, () -> memory.service.saveTable(badCategory));
        FareTableDto badBox = tableDto("odakyu", "小田急電鉄", "3:140");
        badBox.setBoxes("35,139");
        assertThrows(IllegalArgumentException.class, () -> memory.service.saveTable(badBox));
        FareTableDto badDate = tableDto("odakyu", "小田急電鉄", "3:140");
        badDate.setVerifiedAt("2026/10/11");
        assertThrows(IllegalArgumentException.class, () -> memory.service.saveTable(badDate));
        assertTrue(memory.tables.isEmpty());

        FareTableDto good = tableDto("odakyu", "小田急電鉄", "3:140,7:170");
        good.setStationAdds("新宿:10");
        good.setVerifiedAt("2026-10-11");
        memory.service.saveTable(good);
        assertEquals(1, memory.tables.size());
        FareTableDto listed = memory.service.listTables().get(0);
        assertEquals("3:140,7:170", listed.getBands());
        assertEquals("2026-10-11", listed.getVerifiedAt());
        assertEquals(List.of("小田急電鉄"), listed.getOperators());
    }

    // ---------------------------------------------------------------- 역 쌍 요금 · 영업킬로

    @Test
    void pairsAreUpsertedByStationPairRegardlessOfOrder() {
        FareMemory memory = new FareMemory();
        assertEquals(2, memory.service.savePairs(List.of(pairDto("JR", "名古屋", "岐阜", 470, null), pairDto("JR", "東京", "仙台", 5040, "SURCHARGE"))));
        assertEquals(2, memory.pairs.size());
        RailGraph.Step ride = ride(RailCategory.JR, "東海旅客鉄道", "東海道線", "岐阜", "名古屋", 30.3);
        assertEquals(470, memory.service.fareBook().price(List.of(ride)).fareYen);

        // 방향을 바꿔 다시 보내면 같은 쌍으로 보고 값을 바꾼다
        memory.service.savePairs(List.of(pairDto("JR", "岐阜", "名古屋", 480, null)));
        assertEquals(2, memory.pairs.size());
        assertEquals(480, memory.service.fareBook().price(List.of(ride)).fareYen);
        // 운임과 특급요금은 같은 역 쌍이어도 따로다
        memory.service.savePairs(List.of(pairDto("JR", "名古屋", "岐阜", 870, "SURCHARGE")));
        assertEquals(3, memory.pairs.size());
        assertEquals(2, memory.service.listPairs("JR").stream().filter(p -> p.getA().equals("名古屋") || p.getB().equals("名古屋")).count());
        assertTrue(memory.service.listPairs("阪急電鉄").isEmpty());

        // 하나라도 틀리면 아무것도 넣지 않는다
        assertThrows(IllegalArgumentException.class, () -> memory.service.savePairs(List.of(
                pairDto("JR", "大阪", "神戸", 460, null), pairDto("JR", "大阪", "大阪", 100, null))));
        assertThrows(IllegalArgumentException.class, () -> memory.service.savePairs(List.of(pairDto("JR", "大阪", "神戸", 0, null))));
        assertThrows(IllegalArgumentException.class, () -> memory.service.savePairs(List.of(pairDto("JR", "大阪", "神戸", 460, "DISCOUNT"))));
        assertThrows(IllegalArgumentException.class, () -> memory.service.savePairs(List.of(pairDto("阪急電鉄", "大阪梅田", "神戸三宮", 330, "SURCHARGE"))));
        assertThrows(IllegalArgumentException.class, () -> memory.service.savePairs(new ArrayList<>()));
        assertEquals(3, memory.pairs.size());

        Long id = memory.service.listPairs(null).get(0).getId();
        assertTrue(memory.service.deletePair(id));
        assertFalse(memory.service.deletePair(id));
        assertFalse(memory.service.deletePair(null));
        assertEquals(2, memory.pairs.size());
    }

    @Test
    void lineKilometresCanBeRegisteredAndReplaced() {
        FareMemory memory = new FareMemory();
        LineKmDto dto = new LineKmDto();
        dto.setLine("秋田新幹線");
        dto.setStations("盛岡:0,田沢湖:40.1,秋田:127.3");
        LineKmDto saved = memory.service.saveLineKm(dto);
        assertNotNull(saved.getId());
        RailGraph.Step ride = ride(RailCategory.SHINKANSEN, "東日本旅客鉄道", "秋田新幹線", "盛岡", "秋田", 120);
        assertEquals(127.3, memory.service.fareBook().fareKm(ride), 1e-9);

        dto.setStations("盛岡:0,秋田:127.4");
        memory.service.saveLineKm(dto);
        assertEquals(1, memory.lineKms.size(), "같은 노선은 바꾼다");
        assertEquals(127.4, memory.service.fareBook().fareKm(ride), 1e-9);

        dto.setStations("盛岡:0");
        assertThrows(IllegalArgumentException.class, () -> memory.service.saveLineKm(dto));
        dto.setLine(" ");
        assertThrows(IllegalArgumentException.class, () -> memory.service.saveLineKm(dto));

        assertTrue(memory.service.deleteLineKm(memory.service.listLineKm().get(0).getId()));
        assertEquals(120, memory.service.fareBook().fareKm(ride), 1e-9);
    }

    // ---------------------------------------------------------------- 패스

    @Test
    void passesAreSavedListedAndSwitchedOff() {
        FareMemory memory = new FareMemory();
        // 예전 코드가 만든 행: 범위 정보가 없어 추천 대상이 아니다
        TransportPass legacy = new TransportPass();
        legacy.setId(999L);
        legacy.setName("옛 패스");
        legacy.setCity("도쿄");
        legacy.setPriceEnyen(1000);
        legacy.setValidityDays(1);
        memory.passes.add(legacy);

        TransportPassDto dto = passDto("hiroshima-tram-1day", "히로시마 전차 1일 승차권", "히로시마", 700);
        dto.setDays(1);
        dto.setChildPriceYen(350);
        dto.setUrl("https://example.com/pass");
        dto.setVerifiedAt("2026-10-11");
        TransportPassDto saved = memory.service.savePass(dto);
        assertEquals("manual", saved.getSource());
        assertEquals(1, saved.getDays());
        assertNull(saved.getHours());
        assertEquals(List.of("広島電鉄"), saved.getCoverage().get(0).getOperators());
        assertEquals(List.of("TRAM"), saved.getCoverage().get(0).getCategories());

        assertEquals(1, memory.service.passes().size());
        assertEquals("hiroshima-tram-1day", memory.service.passes().get(0).key);
        assertEquals("히로시마", memory.service.passRow("hiroshima-tram-1day").getCity());
        assertNull(memory.service.passRow("없는 키"));
        assertEquals(1, memory.service.listPasses(null, false).size(), "공개 목록에는 옛 행이 나오지 않는다");
        assertEquals(2, memory.service.listPasses(null, true).size());
        assertEquals(1, memory.service.listPasses("히로시마", false).size());
        assertTrue(memory.service.listPasses("오사카", false).isEmpty());

        // 가격이 바뀌면 같은 키로 다시 보낸다
        dto.setPriceYen(800);
        memory.service.savePass(dto);
        assertEquals(2, memory.passes.size());
        assertEquals(800, memory.service.passes().get(0).priceYen);

        // 꺼 두기
        dto.setEnabled(false);
        memory.service.savePass(dto);
        assertTrue(memory.service.passes().isEmpty());
        assertTrue(memory.service.listPasses(null, false).isEmpty());

        assertTrue(memory.service.deletePass("hiroshima-tram-1day"));
        assertFalse(memory.service.deletePass("hiroshima-tram-1day"));
        assertEquals(1, memory.passes.size());
    }

    @Test
    void passValidation() {
        FareMemory memory = new FareMemory();
        TransportPassDto both = passDto("p", "패스", "도쿄", 700);
        both.setDays(1);
        both.setHours(24);
        assertThrows(IllegalArgumentException.class, () -> memory.service.savePass(both), "일수와 시간을 함께 적으면 안 된다");
        assertThrows(IllegalArgumentException.class, () -> memory.service.savePass(passDto("p", "패스", "도쿄", 700)), "기간이 없다");

        TransportPassDto noPrice = passDto("p", "패스", "도쿄", 0);
        noPrice.setDays(1);
        assertThrows(IllegalArgumentException.class, () -> memory.service.savePass(noPrice));

        TransportPassDto noCoverage = passDto("p", "패스", "도쿄", 700);
        noCoverage.setDays(1);
        noCoverage.getCoverage().clear();
        assertThrows(IllegalArgumentException.class, () -> memory.service.savePass(noCoverage));

        // 조건이 하나도 없는 범위는 "모든 교통수단"이 되어 버리므로 받지 않는다
        TransportPassDto everything = passDto("p", "패스", "도쿄", 700);
        everything.setDays(1);
        everything.getCoverage().get(0).getOperators().clear();
        everything.getCoverage().get(0).getCategories().clear();
        assertThrows(IllegalArgumentException.class, () -> memory.service.savePass(everything));

        TransportPassDto badRule = passDto("p", "패스", "도쿄", 700);
        badRule.setDays(1);
        badRule.setDayRule("WEEKEND");
        assertThrows(IllegalArgumentException.class, () -> memory.service.savePass(badRule));

        TransportPassDto badDay = passDto("p", "패스", "도쿄", 700);
        badDay.setDays(1);
        badDay.getExtraDaysOfMonth().add(32);
        assertThrows(IllegalArgumentException.class, () -> memory.service.savePass(badDay));
        assertTrue(memory.passes.isEmpty());

        TransportPassDto hours = passDto("p", "패스", "도쿄", 700);
        hours.setHours(48);
        hours.setDayRule("holiday");
        hours.getExtraDaysOfMonth().add(8);
        TransportPassDto saved = memory.service.savePass(hours);
        assertEquals(48, saved.getHours());
        assertNull(saved.getDays());
        assertEquals("HOLIDAY", saved.getDayRule());
        assertEquals(2, memory.passes.get(0).getValidityDays(), "48시간권은 이틀에 걸친다 (예전 필드에도 값을 채운다)");
        PassAdvisor.Pass pass = memory.service.passes().get(0);
        assertEquals(48, pass.hours);
        assertEquals(0, pass.days);
        assertTrue(pass.extraDaysOfMonth.contains(8));
    }

    @Test
    void brokenRowsInTheDatabaseDoNotBreakFareCalculation() {
        FareMemory memory = new FareMemory();
        memory.service.saveTable(tableDto("ok", "阪急電鉄", "4:170,9:200"));
        TransitFareTable broken = new TransitFareTable();
        broken.setId(500L);
        broken.setTableKey("broken");
        broken.setName("깨진 표");
        broken.setOperators("阪神電気鉄道");
        broken.setBands("이건 숫자가 아님");
        memory.tables.add(broken);
        memory.service.invalidate();

        FareBook book = memory.service.fareBook();
        assertEquals(1, book.tableCount(), "읽을 수 없는 표만 건너뛴다");
        assertEquals(200, book.price(List.of(ride(RailCategory.PRIVATE, "阪急電鉄", "神戸本線", "A", "B", 8))).fareYen);
    }

    // ---------------------------------------------------------------- 도우미

    /** 표가 없을 때의 추정값 (RailFareModel 의 지하철 대표 요금표: 7km 까지 230엔) */
    private static final class RailFareModelEstimate {
        static final int SUBWAY_5KM = com.travel.planner.transit.RailFareModel.fare(RailCategory.SUBWAY, "東京地下鉄", 5);
    }

    private static int fare(FareBook book, RailCategory category, String operator, String line, String from, String to, double km) {
        return book.price(List.of(ride(category, operator, line, from, to, km))).fareYen;
    }

    private static RailGraph.Step ride(RailCategory category, String operator, String line, String from, String to, double km) {
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

    private static void locate(RailGraph.Step step, double fromLat, double fromLng, double toLat, double toLng) {
        step.hasCoordinates = true;
        step.fromLat = fromLat;
        step.fromLng = fromLng;
        step.toLat = toLat;
        step.toLng = toLng;
    }

    private static PassAdvisor.Pass pass(List<PassAdvisor.Pass> passes, String key) {
        return passes.stream().filter(p -> p.key.equals(key)).findFirst().orElseThrow(() -> new AssertionError("패스가 없다: " + key));
    }

    private static PassAdvisor.Ride rideOf(String operator, RailCategory category, String line) {
        PassAdvisor.Ride ride = new PassAdvisor.Ride();
        PassAdvisor.Leg leg = new PassAdvisor.Leg();
        leg.operator = operator;
        leg.category = category;
        leg.line = line;
        ride.legs.add(leg);
        ride.fareYen = 200;
        return ride;
    }

    private static FareTableDto tableDto(String key, String operator, String bands) {
        FareTableDto dto = new FareTableDto();
        dto.setKey(key);
        dto.setName("표 " + key);
        if (operator != null) dto.getOperators().add(operator);
        dto.setBands(bands);
        return dto;
    }

    private static FarePairDto pairDto(String group, String a, String b, int yen, String kind) {
        FarePairDto dto = new FarePairDto();
        dto.setGroup(group);
        dto.setA(a);
        dto.setB(b);
        dto.setAdultYen(yen);
        dto.setKind(kind);
        return dto;
    }

    private static TransportPassDto passDto(String key, String name, String city, int price) {
        TransportPassDto dto = new TransportPassDto();
        dto.setKey(key);
        dto.setName(name);
        dto.setCity(city);
        dto.setPriceYen(price);
        TransportPassDto.CoverDto cover = new TransportPassDto.CoverDto();
        cover.getOperators().add("広島電鉄");
        cover.getCategories().add("TRAM");
        dto.getCoverage().add(cover);
        return dto;
    }
}
