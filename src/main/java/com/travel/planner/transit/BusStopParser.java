package com.travel.planner.transit;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * 국토교통성 "국토수치정보 버스 정류장(P11)" 파일 읽기.
 *
 * 받는 곳: https://nlftp.mlit.go.jp/ksj/gml/datalist/KsjTmplt-P11-2022.html  ("シェープ、geojson形式")
 *  - 전국 파일 P11-22_SHP.zip 은 안에 도도부현별 zip(P11-22_01_GML.zip …)이 들어 있고, 그 안에 .geojson 이 있다.
 *  - 도도부현 파일 하나(P11-22_22_SHP.zip 등)를 그대로 넣어도 읽는다.
 * 압축은 풀지 않고 그대로 읽는다.
 *
 * 속성: P11_001 정류장 이름, P11_002 운영사, P11_003_01~35 노선(계통) 이름, P11_004_01~35 버스 구분(1 민영, 2 공영, 3 커뮤니티, 5 기타)
 *   (실제 파일은 한 칸에 "西部循環線,北街道線,…"처럼 쉼표로 여러 노선을 적고, 넘치면 다음 칸에 이어 적는다. 구분 코드도 "1,1,…")
 * 좌표: GeoJSON Point [경도, 위도] (JGD2011, 세계측지계와 같다고 본다)
 * 사용 조건: CC BY 4.0 (출처: 국토수치정보 버스 정류장 데이터, 국토교통성)
 */
public final class BusStopParser {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final JsonFactory FACTORY = MAPPER.getFactory();

    private BusStopParser() {}

    public static BusStopIndex read(Path zip) throws IOException {
        BusStopIndex.Builder builder = new BusStopIndex.Builder();
        try (InputStream in = Files.newInputStream(zip)) {
            readZip(in, builder, 0);
        }
        if (builder.size() == 0) throw new IOException("버스 정류장을 하나도 읽지 못했습니다. 국토수치정보 버스 정류장(P11) geojson 이 들어 있는 zip 인지 확인해 주세요: " + zip.getFileName());
        return builder.build(zip.getFileName().toString());
    }

    /** 바깥 zip 안의 zip 은 다시 열고, .geojson 은 읽는다 */
    static void readZip(InputStream in, BusStopIndex.Builder builder, int depth) throws IOException {
        ZipInputStream zin = new ZipInputStream(in, java.nio.charset.Charset.forName("MS932"));
        ZipEntry entry;
        while ((entry = zin.getNextEntry()) != null) {
            String name = entry.getName().toLowerCase();
            if (entry.isDirectory()) continue;
            InputStream body = new FilterInputStream(zin) {
                @Override public void close() { /* 바깥 zip 을 닫지 않는다 */ }
            };
            if (name.endsWith(".zip") && depth < 2) {
                readZip(body, builder, depth + 1);
            } else if (name.endsWith(".geojson")) {
                readGeoJson(body, builder);
            }
        }
    }

    /** GeoJSON 의 features 를 하나씩 읽는다 (파일 전체를 메모리에 올리지 않는다) */
    public static void readGeoJson(InputStream in, BusStopIndex.Builder builder) throws IOException {
        try (JsonParser p = FACTORY.createParser(new FilterInputStream(in) {
            @Override public void close() { }
        })) {
            if (p.nextToken() != JsonToken.START_OBJECT) return;
            while (p.nextToken() == JsonToken.FIELD_NAME) {
                String field = p.currentName();
                p.nextToken();
                if (!"features".equals(field) || p.currentToken() != JsonToken.START_ARRAY) {
                    p.skipChildren();
                    continue;
                }
                while (p.nextToken() == JsonToken.START_OBJECT) {
                    JsonNode feature = MAPPER.readTree(p);
                    add(feature, builder);
                }
            }
        }
    }

    static void add(JsonNode feature, BusStopIndex.Builder builder) {
        JsonNode props = feature.path("properties");
        JsonNode coords = feature.path("geometry").path("coordinates");
        if (!coords.isArray() || coords.size() < 2) return;
        double lng = coords.get(0).asDouble(Double.NaN);
        double lat = coords.get(1).asDouble(Double.NaN);
        String stop = text(props, "P11_001");
        String operator = text(props, "P11_002");
        // 노선 이름은 쉼표로 이어 적혀 있고, 길면(셰이프파일 글자 수 제한) 다음 칸(_02, _03 …)으로 이어진다. 구분 코드도 같은 순서로 적혀 있다.
        List<String> routes = new ArrayList<>();
        List<Integer> types = new ArrayList<>();
        for (int i = 1; i <= 35; i++) {
            String suffix = String.format("%02d", i);
            String route = text(props, "P11_003_" + suffix);
            if (route != null) {
                for (String part : route.split("[,，]")) {
                    if (!part.isBlank()) routes.add(part.trim());
                }
            }
            String type = text(props, "P11_004_" + suffix);
            if (type != null) {
                for (String part : type.split("[,，]")) {
                    Integer code = null;
                    try {
                        code = Integer.parseInt(part.trim());
                    } catch (NumberFormatException ignored) {
                        // 알 수 없는 코드
                    }
                    types.add(code);
                }
            }
        }
        builder.add(stop, operator, lat, lng, routes, types);
    }

    private static String text(JsonNode props, String key) {
        JsonNode v = props.get(key);
        if (v == null || v.isNull()) return null;
        String s = v.asText().trim();
        return s.isEmpty() ? null : s;
    }
}
