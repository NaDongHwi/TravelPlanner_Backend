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
        // 공항 좌표(지도)에서 정류장까지 0.8km: 먼 구간이므로 1km 까지 넓혀서 찾는다
        BusStopIndex.Trip trip = idx.find(LAT - 0.16, LNG - 0.2 + 0.0088, LAT, LNG, 0.6);
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
}
