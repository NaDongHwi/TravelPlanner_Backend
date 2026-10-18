package com.travel.planner.transit;

import com.travel.planner.dto.TransitLegDto;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 국토수치정보 버스 정류장(P11) 읽기와 그 자료로 버스 경로 찾기 */
class BusStopTest {

    // 시즈오카역 근처를 흉내 낸 가짜 좌표 (위도 0.009 ≈ 1km, 경도 0.011 ≈ 1km)
    static final double LAT = 34.97;
    static final double LNG = 138.39;

    /** 실제 파일처럼: 한 칸에 쉼표로 여러 노선, 넘치면 다음 칸(_02)에 이어 적고, 구분 코드도 같은 순서 */
    static String feature(String stop, String operator, double lat, double lng, String routes1, String routes2, String types1, String types2) {
        return "{\"type\":\"Feature\",\"properties\":{\"P11_001\":\"" + stop + "\",\"P11_002\":\"" + operator + "\","
                + "\"P11_003_01\":\"" + routes1 + "\",\"P11_003_02\":" + (routes2 == null ? "null" : "\"" + routes2 + "\"") + ",\"P11_003_03\":null,"
                + "\"P11_004_01\":\"" + types1 + "\",\"P11_004_02\":" + (types2 == null ? "null" : "\"" + types2 + "\"") + ",\"P11_005\":null},"
                + "\"geometry\":{\"type\":\"Point\",\"coordinates\":[" + lng + "," + lat + "]}}";
    }

    static String geojson() {
        String op = "しずてつジャストライン（株）";
        return "{\"type\":\"FeatureCollection\",\"name\":\"P11-22_22\",\"crs\":{\"type\":\"name\",\"properties\":{\"name\":\"urn:ogc:def:crs:EPSG::6668\"}},\"features\":[\n"
                + feature("静岡駅前", op, LAT, LNG, "西部循環線,北街道線", "安倍線,富士山静岡空港静岡線", "1,1", "1,1") + ",\n"
                + feature("安倍線終点", op, LAT + 0.045, LNG, "安倍線", null, "1", null) + ",\n"             // 북쪽 5km
                + feature("北街道中間", op, LAT + 0.009, LNG + 0.022, "北街道線", null, "1", null) + ",\n"      // 동북 2.5km
                + feature("北街道乗換", op, LAT + 0.018, LNG + 0.044, "北街道線", null, "1", null) + ",\n"      // 동북 5km
                + feature("循環バス乗換", "静岡市", LAT + 0.0185, LNG + 0.0445, "ココバス", null, "3", null) + ",\n"   // 바로 옆(갈아타기)
                + feature("循環バス終点", "静岡市", LAT + 0.036, LNG + 0.066, "ココバス", null, "3", null) + ",\n"
                + feature("空港", op, LAT - 0.16, LNG - 0.2, "富士山静岡空港静岡線", null, "1", null) + ",\n"
                + feature("他社停留所", "他社バス（株）", LAT + 0.045, LNG + 0.0165, "山間線", null, "1", null) + "\n"
                + "]}";
    }

    /** 전국 파일처럼 zip 안에 도도부현 zip, 그 안에 폴더/geojson */
    static Path nationalZip() throws Exception {
        ByteArrayOutputStream inner = new ByteArrayOutputStream();
        try (ZipOutputStream z = new ZipOutputStream(inner)) {
            z.putNextEntry(new ZipEntry("P11-22_22/"));
            z.closeEntry();
            z.putNextEntry(new ZipEntry("P11-22_22/P11-22_22.geojson"));
            z.write(geojson().getBytes(StandardCharsets.UTF_8));
            z.closeEntry();
            z.putNextEntry(new ZipEntry("P11-22_22/P11-22_22.dbf"));
            z.write(new byte[]{1, 2, 3});
            z.closeEntry();
        }
        Path file = Files.createTempFile("P11-22_SHP", ".zip");
        try (ZipOutputStream z = new ZipOutputStream(Files.newOutputStream(file))) {
            z.putNextEntry(new ZipEntry("P11-22_22_GML.zip"));
            z.write(inner.toByteArray());
            z.closeEntry();
        }
        file.toFile().deleteOnExit();
        return file;
    }

    static BusStopIndex index() throws Exception {
        return BusStopParser.read(nationalZip());
    }

    @Test
    void readsNestedZipAndSplitsCommaSeparatedRoutes() throws Exception {
        BusStopIndex idx = index();
        assertEquals(8, idx.stopCount());
        // 운영사+노선이 같으면 한 노선: 西部循環線, 北街道線, 安倍線, 富士山静岡空港静岡線, ココバス, 山間線
        assertEquals(6, idx.routeCount());
        assertEquals("しずてつジャストライン", BusStopIndex.cleanOperator("しずてつジャストライン（株）"));

        // 두 번째 칸(_02)에 이어 적힌 노선도 읽었다: 역 앞 → 북쪽 5km 를 安倍線 으로 바로 간다
        BusStopIndex.Trip trip = idx.find(LAT + 0.001, LNG, LAT + 0.045, LNG + 0.001, 0.6);
        assertNotNull(trip);
        assertEquals(1, trip.rides.size());
        assertEquals("安倍線", trip.rides.get(0).line);
        assertEquals("静岡駅前", trip.rides.get(0).from);
        assertEquals("安倍線終点", trip.rides.get(0).to);
    }

    @Test
    void findsOneTransferAndRejectsDetours() throws Exception {
        BusStopIndex idx = index();
        // 역 앞 → 北街道線 → 갈아타기(옆 정류장) → ココバス(커뮤니티 버스) → 종점
        BusStopIndex.Trip trip = idx.find(LAT, LNG, LAT + 0.036, LNG + 0.066, 0.6);
        assertNotNull(trip);
        assertEquals(2, trip.rides.size());
        assertEquals("北街道線", trip.rides.get(0).line);
        assertEquals("北街道乗換", trip.rides.get(0).to);
        assertEquals("循環バス乗換", trip.rides.get(1).from);
        assertEquals(BusStopIndex.TYPE_COMMUNITY, trip.rides.get(1).busType);
        assertTrue(trip.transferKm < 0.1);

        // 근처에 정류장이 없는 곳은 찾지 않는다
        assertNull(idx.find(LAT + 0.5, LNG + 0.5, LAT + 0.6, LNG + 0.6, 0.6));
        assertTrue(idx.hasStopNear(LAT, LNG, 0.6));
    }

    @Test
    void airportFarFromStopIsReachedWithWiderWalkOnLongTrips() throws Exception {
        BusStopIndex idx = index();
        // 공항 좌표(지도)에서 정류장까지 0.9km: 먼 구간이므로 1km 까지 넓혀서 찾는다
        BusStopIndex.Trip trip = idx.find(LAT - 0.16, LNG - 0.2 + 0.0098, LAT, LNG, BusStopIndex.ACCESS_KM);
        assertNotNull(trip);
        assertEquals("富士山静岡空港静岡線", trip.rides.get(0).line);
        // 먼 노선은 시내 속도로만 계산하지 않는다 (직선 25km 남짓: 1시간 남짓)
        assertTrue(trip.rides.get(0).minutes < 80, "" + trip.rides.get(0).minutes);
    }

    @Test
    void legPlannerUsesBusStopsWhenNoTimetableDataAndWalksWhenNoBusIsFaster() throws Exception {
        BusStopIndex idx = index();
        TransitLegDto leg = TransitLegPlanner.plan(null, idx, TransitLegPlanner.ORIGINAL, LAT + 0.001, LNG, "호텔",
                LAT + 0.045, LNG + 0.001, "온천", "도보 및 대중교통", 30, null, null);
        assertEquals("bus-stops", leg.getSource());
        assertEquals("버스", leg.getMode());
        assertTrue(leg.getSummary().contains("しずてつジャストライン 安倍線 静岡駅前 → 安倍線終点"), leg.getSummary());
        assertTrue(leg.getNote().contains("2022년"));
        assertTrue(leg.getFareYen() > 0);
        assertEquals("estimate", leg.getFareSource());
        assertTrue(leg.getSteps().stream().anyMatch(s -> "대기".equals(s.getType())));

        // 같은 곳을 버스 자료 없이 계산하면 예전처럼 "노선 미확인"
        TransitLegDto unknown = TransitLegPlanner.plan(null, null, TransitLegPlanner.ORIGINAL, LAT + 0.001, LNG, "호텔",
                LAT + 0.045, LNG + 0.001, "온천", "도보 및 대중교통", 30, null, null);
        assertEquals("distance", unknown.getSource());

        // 양쪽에 정류장은 있지만 잇는 노선이 없는 1.5km: 걸어가라고 안내한다
        TransitLegDto walk = TransitLegPlanner.plan(null, idx, TransitLegPlanner.ORIGINAL, LAT + 0.045, LNG, "온천",
                LAT + 0.045, LNG + 0.0165, "카페", "도보 및 대중교통", 15, null, null);
        assertEquals("walk", walk.getSource());
        assertTrue(walk.getNote().contains("도보로 안내"));
    }

    @Test
    void taxiWhenNoTransitAndTooFarToWalk() throws Exception {
        BusStopIndex idx = index();
        // 정류장도 노선도 없는 산간 4km: 택시
        TransitLegDto leg = TransitLegPlanner.plan(null, idx, TransitLegPlanner.ORIGINAL, LAT + 0.3, LNG, "산장",
                LAT + 0.336, LNG, "폭포", "도보 및 대중교통", 20, null, null);
        assertEquals("taxi", leg.getSource());
        assertEquals("택시", leg.getMode());
        assertTrue(leg.getSummary().startsWith("택시 약 "), leg.getSummary());
        assertEquals(TransitLegPlanner.taxiFare(4.0), leg.getFareYen(), 200);
        assertTrue(leg.getNote().contains("택시로 안내"));
        // 택시 요금: 첫 1.1km 500엔, 이후 255m 마다 100엔 (도로 거리 = 직선 × 1.3)
        assertEquals(500, TransitLegPlanner.taxiFare(0.5));
        assertEquals(500 + 13 * 100, TransitLegPlanner.taxiFare(4.3 / 1.3));   // 도로 4.3km: (4.3-1.096)/0.255 = 12.6 → 13칸
    }

    @Test
    void hikingSpotIsReachedOnFootFromTheNearestStop() throws Exception {
        BusStopIndex idx = index();
        // 정상은 안倍線終点 정류장에서 북쪽으로 2km. 역 앞에서 버스로 정류장까지 가서, 거기서부터 걸어서 오른다
        double summitLat = LAT + 0.045 + 0.018;
        TransitLegDto up = TransitLegPlanner.plan(null, idx, TransitLegPlanner.ORIGINAL, LAT + 0.001, LNG, "호텔",
                summitLat, LNG, "산 정상", "도보 및 대중교통", 60, null, null, false, true);
        assertEquals("bus-stops", up.getSource());
        assertTrue(up.getMode().endsWith("·등산"), up.getMode());
        assertTrue(up.getSummary().contains("安倍線"), up.getSummary());
        assertTrue(up.getSummary().contains("安倍線終点 정류장부터 등산로를 걸어 약"), up.getSummary());
        TransitLegDto.Step last = up.getSteps().get(up.getSteps().size() - 1);
        assertEquals("등산", last.getCategory());
        assertEquals("산 정상", last.getTo());
        assertTrue(up.getNote().contains("등산 시간은 방문 시간에 포함"));
        int busMinutes = TransitLegPlanner.plan(null, idx, TransitLegPlanner.ORIGINAL, LAT + 0.001, LNG, "호텔",
                LAT + 0.045, LNG + 0.001, "온천", "도보 및 대중교통", 30, null, null).getMinutes();
        assertTrue(Math.abs(up.getMinutes() - busMinutes) <= 3, "이동 시간에는 등산 시간을 더하지 않는다: " + up.getMinutes());

        // 내려올 때: 정상 → 정류장(하산) → 버스
        TransitLegDto down = TransitLegPlanner.plan(null, idx, TransitLegPlanner.ORIGINAL, summitLat, LNG, "산 정상",
                LAT + 0.001, LNG, "호텔", "도보 및 대중교통", 60, null, null, true, false);
        assertEquals("하산", down.getSteps().get(0).getCategory());
        assertTrue(down.getSummary().startsWith("걸어서 내려와 安倍線終点 정류장까지"), down.getSummary());

        // 렌터카 여행은 그대로 차로
        TransitLegDto car = TransitLegPlanner.plan(null, idx, TransitLegPlanner.ORIGINAL, LAT + 0.001, LNG, "호텔",
                summitLat, LNG, "산 정상", "차량 렌트", 40, null, null, false, true);
        assertEquals("자동차", car.getMode());
    }

    @Test
    void hikeGateIsStationNearbyThenParkingThenFarStop() throws Exception {
        BusStopIndex idx = index();
        // 1) 정상 1km 북쪽에 安倍線終点 정류장: 대중교통으로 가서 걸어 오른다 (다카오산형)
        double nearLat = LAT + 0.045 + 0.009;
        TransitLegDto near = TransitLegPlanner.plan(null, idx, TransitLegPlanner.ORIGINAL, LAT + 0.001, LNG, "호텔",
                nearLat, LNG, "산", "도보 및 대중교통", 60, null, null, null, TransitLegPlanner.Summit.hike(null, null, null));
        assertEquals("bus-stops", near.getSource());
        assertTrue(near.getSummary().contains("安倍線終点 정류장부터 등산로를 걸어"), near.getSummary());

        // 2) 정류장은 2km 밖이고 정상 200m 옆에 주차장: 주차장까지(대중교통이 없으니 택시) → 걸어서 10분
        double summitLat = LAT + 0.045 + 0.018;
        TransitLegPlanner.Summit withParking = TransitLegPlanner.Summit.hike(summitLat - 0.0018, LNG, "산 정상 주차장");
        TransitLegDto parking = TransitLegPlanner.plan(null, idx, TransitLegPlanner.ORIGINAL, LAT + 0.001, LNG, "호텔",
                summitLat, LNG, "산 정상", "도보 및 대중교통", 60, null, null, null, withParking);
        assertEquals("taxi", parking.getSource());
        assertTrue(parking.getSummary().startsWith("택시 약 "), parking.getSummary());
        assertTrue(parking.getSummary().endsWith("산 정상 주차장부터 등산로를 걸어 약 10분(방문 시간에 포함)"), parking.getSummary());

        // 렌터카면 주차장까지 차로
        TransitLegDto car = TransitLegPlanner.plan(null, idx, TransitLegPlanner.ORIGINAL, LAT + 0.001, LNG, "호텔",
                summitLat, LNG, "산 정상", "차량 렌트", 40, null, null, null, withParking);
        assertEquals("자동차", car.getSteps().isEmpty() ? car.getMode() : car.getMode().replace("·등산", ""));
        assertTrue(car.getSummary().contains("산 정상 주차장부터 등산로"), car.getSummary());

        // 3) 주차장을 모르면 4km 안의 가장 가까운 정류장에서 걸어 오른다 (차로 더 가까이 갈 수 있을 수 있다고 안내)
        TransitLegDto far = TransitLegPlanner.plan(null, idx, TransitLegPlanner.ORIGINAL, LAT + 0.001, LNG, "호텔",
                summitLat, LNG, "산 정상", "도보 및 대중교통", 60, null, null, null, TransitLegPlanner.Summit.hike(null, null, null));
        assertTrue(far.getNote().contains("차로 가면 더 가까이"), far.getNote());
    }

    @Test
    void mountFujiGoesToTheFifthStation() throws Exception {
        BusStopIndex idx = index();
        TransitLegPlanner.Summit fuji = TransitLegPlanner.Summit.fuji(LAT + 0.045, LNG + 0.001, "후지산 테스트 5합목");
        TransitLegDto leg = TransitLegPlanner.plan(null, idx, TransitLegPlanner.ORIGINAL, LAT + 0.001, LNG, "호텔",
                LAT + 0.09, LNG, "후지산", "도보 및 대중교통", 60, null, null, null, fuji);
        assertEquals("bus-stops", leg.getSource(), "5합목까지 버스");
        assertTrue(leg.getSummary().endsWith("후지산 테스트 5합목 도착"), leg.getSummary());
        assertTrue(leg.getNote().contains("정상 등반은 7월 초~9월 초"), leg.getNote());
        assertTrue(leg.getSteps().stream().noneMatch(s -> "등산".equals(s.getCategory())), "등산 단계는 없다");
    }
}
