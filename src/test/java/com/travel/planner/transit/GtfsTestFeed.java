package com.travel.planner.transit;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * 테스트용 가상 버스망의 GTFS 파일 (위도 35.0, 경도 0.01도 ≈ 0.91km).
 *
 *   10번 "駅前～温泉線" : 駅前 ─ 市役所前 ─ 城跡公園 ─ 温泉入口   (구간 요금 150~250엔, 평일 6회 · 토·휴일 3회, 왕복)
 *   "市内循環"          : 駅前 → 北町 → 東町 → 市役所前 → 駅前        (균일 200엔, 10분 간격: frequencies.txt)
 *   "山頂線"            : 温泉入口 ─ 山頂                              (500엔, 여름(2027-07-01 ~ 08-31)에만 하루 2회)
 */
public final class GtfsTestFeed {

    private GtfsTestFeed() {}

    public static final double LAT = 35.0;
    public static final double LNG = 135.0;

    public static Path zip() throws IOException {
        return zip(files());
    }

    public static Path zip(Map<String, String> files) throws IOException {
        Path file = Files.createTempFile("gtfs-test", ".zip");
        file.toFile().deleteOnExit();
        try (OutputStream out = Files.newOutputStream(file); ZipOutputStream zip = new ZipOutputStream(out)) {
            for (Map.Entry<String, String> e : files.entrySet()) {
                zip.putNextEntry(new ZipEntry(e.getKey()));
                zip.write(e.getValue().getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        }
        return file;
    }

    /** 가상 버스망의 GTFS 파일들. 실제 파일에서 흔한 모양(BOM, CRLF, 큰따옴표, 뒤섞인 줄 순서)을 일부러 섞었다. */
    public static Map<String, String> files() {
        Map<String, String> files = new LinkedHashMap<>();
        files.put("agency.txt", "﻿agency_id,agency_name,agency_url,agency_timezone\r\nA1,もぎ交通,https://example.jp,Asia/Tokyo\r\n");
        files.put("feed_info.txt", "feed_publisher_name,feed_publisher_url,feed_lang,feed_start_date,feed_end_date,feed_version\n"
                + "もぎ市,https://example.jp,ja,20260401,20270930,1\n");
        files.put("stops.txt", "﻿\"stop_id\",\"stop_name\",\"stop_desc\",\"stop_lat\",\"stop_lon\",\"zone_id\",\"location_type\",\"parent_station\"\r\n"
                + "S1,駅前,\"駅の前, \"\"東口\"\"\"," + LAT + "," + LNG + ",Z1,0,P1\r\n"
                + "S2,市役所前,," + LAT + "," + (LNG + 0.01) + ",Z2,0,\r\n"
                + "S3,城跡公園,," + LAT + "," + (LNG + 0.02) + ",Z3,0,\r\n"
                + "S4,温泉入口,," + LAT + "," + (LNG + 0.04) + ",Z4,0,\r\n"
                + "S5,山頂,," + LAT + "," + (LNG + 0.08) + ",Z5,0,\r\n"
                + "L1,北町,," + (LAT + 0.01) + "," + LNG + ",,0,\r\n"
                + "L2,東町,," + (LAT + 0.01) + "," + (LNG + 0.01) + ",,0,\r\n"
                + "P1,駅前,,,,,1,\r\n"                       // 상위 정류장 (좌표 없음): 시각표에 쓰이지 않는다
                + "X9,廃止停留所,,,,,0,\r\n");                // 좌표 없는 정류장
        files.put("routes.txt", "route_id,agency_id,route_short_name,route_long_name,route_type\n"
                + "R1,A1,10,駅前～温泉線,3\n"
                + "R2,A1,,市内循環,3\n"
                + "R3,A1,山頂線,,3\n");
        files.put("calendar.txt", "service_id,monday,tuesday,wednesday,thursday,friday,saturday,sunday,start_date,end_date\n"
                + "WD,1,1,1,1,1,0,0,20260401,20270930\n"
                + "HD,0,0,0,0,0,1,1,20260401,20270930\n"
                + "DAILY,1,1,1,1,1,1,1,20260401,20270930\n"
                + "SUMMER,1,1,1,1,1,1,1,20270701,20270831\n");
        // 평일이 공휴일인 날은 휴일 시간표로 바꾼다 (일부만 적어도 대표값은 흔들리지 않는다)
        files.put("calendar_dates.txt", "service_id,date,exception_type\n"
                + "WD,20261012,2\nHD,20261012,1\nWD,20261103,2\nHD,20261103,1\n");

        StringBuilder trips = new StringBuilder("route_id,service_id,trip_id,trip_headsign\n");
        StringBuilder times = new StringBuilder("trip_id,arrival_time,departure_time,stop_id,stop_sequence,pickup_type,drop_off_type\n");
        int[] weekdayOut = {8, 10, 12, 14, 16, 18};
        int[] holidayOut = {9, 13, 17};
        int[] weekdayIn = {9, 11, 13, 15, 17, 19};
        int[] holidayIn = {10, 14, 18};
        for (int hour : weekdayOut) addLine10(trips, times, "WD", hour, true, hour == 18 ? 21 : 15);
        for (int hour : holidayOut) addLine10(trips, times, "HD", hour, true, 15);
        for (int hour : weekdayIn) addLine10(trips, times, "WD", hour, false, 15);
        for (int hour : holidayIn) addLine10(trips, times, "HD", hour, false, 15);

        // 순환선: 운행 하나 + frequencies.txt
        trips.append("R2,DAILY,LOOP,\n");
        String[] loopStops = {"S1", "L1", "L2", "S2", "S1"};
        for (int i = 0; i < loopStops.length; i++) {
            String t = clock(7, i * 3);
            times.append("LOOP,").append(t).append(',').append(t).append(',').append(loopStops[i]).append(',').append(i + 1).append(",0,0\n");
        }
        files.put("frequencies.txt", "trip_id,start_time,end_time,headway_secs\nLOOP,07:00:00,21:00:00,600\n");

        // 산정선: 여름에만, 하루 2회 왕복
        for (int hour : new int[]{9, 15}) {
            trips.append("R3,SUMMER,UP").append(hour).append(",山頂行き\n");
            times.append("UP").append(hour).append(',').append(clock(hour, 0)).append(',').append(clock(hour, 0)).append(",S4,1,0,0\n");
            times.append("UP").append(hour).append(',').append(clock(hour, 20)).append(',').append(clock(hour, 20)).append(",S5,2,0,0\n");
            trips.append("R3,SUMMER,DOWN").append(hour).append(",温泉入口行き\n");
            times.append("DOWN").append(hour).append(',').append(clock(hour + 1, 0)).append(',').append(clock(hour + 1, 0)).append(",S5,1,0,0\n");
            times.append("DOWN").append(hour).append(',').append(clock(hour + 1, 20)).append(',').append(clock(hour + 1, 20)).append(",S4,2,0,0\n");
        }
        files.put("trips.txt", trips.toString());
        files.put("stop_times.txt", times.toString());

        files.put("fare_attributes.txt", "fare_id,price,currency_type,payment_method,transfers\n"
                + "F150,150,JPY,0,0\nF180,180,JPY,0,0\nF220,220,JPY,0,0\nF250,250,JPY,0,0\nF260,260,JPY,0,0\nF200,200,JPY,0,0\nF500,500,JPY,0,0\n");
        StringBuilder rules = new StringBuilder("fare_id,route_id,origin_id,destination_id\n");
        String[][] pairs = {{"Z1", "Z2", "F150"}, {"Z1", "Z3", "F180"}, {"Z1", "Z4", "F250"}, {"Z2", "Z3", "F150"}, {"Z2", "Z4", "F220"}, {"Z3", "Z4", "F180"}};
        for (String[] pair : pairs) {
            rules.append(pair[2]).append(",R1,").append(pair[0]).append(',').append(pair[1]).append('\n');
            // 돌아오는 방향은 같은 요금. 단 温泉入口→駅前 만 260엔 (방향을 뒤바꿔 읽으면 드러나도록 일부러 다르게 둔다)
            String back = pair[0].equals("Z1") && pair[1].equals("Z4") ? "F260" : pair[2];
            rules.append(back).append(",R1,").append(pair[1]).append(',').append(pair[0]).append('\n');
        }
        rules.append("F200,R2,,\n");                         // 노선만 지정: 균일 요금
        rules.append("F500,R3,Z4,Z5\nF500,R3,Z5,Z4\n");
        files.put("fare_rules.txt", rules.toString());

        // 현행 형식의 translations.txt
        files.put("translations.txt", "table_name,field_name,language,translation,record_id,record_sub_id,field_value\n"
                + "stops,stop_name,ja-Hrkt,えきまえ,S1,,\n"
                + "stops,stop_name,ja-Hrkt,しやくしょまえ,,,市役所前\n"
                + "stops,stop_name,ja-Hrkt,じょうせきこうえん,S3,,\n"
                + "stops,stop_name,ko,성터 공원,S3,,\n"
                + "stops,stop_name,en,Onsen Iriguchi,S4,,\n"
                + "stops,stop_name,ja-Hrkt,おんせんいりぐち,S4,,\n"
                + "routes,route_long_name,ja-Hrkt,しないじゅんかん,R2,,\n");
        return files;
    }

    /** 10번 버스 한 편. 줄을 일부러 뒤에서부터 적는다 (파일이 정렬되어 있지 않아도 읽어야 한다). */
    private static void addLine10(StringBuilder trips, StringBuilder times, String service, int hour, boolean outbound, int lastLeg) {
        String id = (outbound ? "OUT" : "IN") + "-" + service + "-" + hour;
        trips.append("R1,").append(service).append(',').append(id).append(',').append(outbound ? "温泉入口行き" : "駅前行き").append('\n');
        String[] stops = outbound ? new String[]{"S1", "S2", "S3", "S4"} : new String[]{"S4", "S3", "S2", "S1"};
        int[] minutes = outbound ? new int[]{0, 4, 8, lastLeg} : new int[]{0, 7, 11, 15};
        for (int i = stops.length - 1; i >= 0; i--) {
            String t = clock(hour, minutes[i]);
            // 돌아오는 방향의 市役所前(S2)은 하차 불가
            int dropOff = !outbound && stops[i].equals("S2") ? 1 : 0;
            times.append(id).append(',').append(t).append(',').append(t).append(',').append(stops[i]).append(',').append(i + 1).append(",0,").append(dropOff).append('\n');
        }
    }

    private static String clock(int hour, int minute) {
        return String.format("%d:%02d:00", hour + minute / 60, minute % 60);
    }
}
