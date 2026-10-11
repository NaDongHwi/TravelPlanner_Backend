package com.travel.planner.transit;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * 국토교통성 "국토수치정보 철도(N02)" 파일 읽기.
 *
 * 내려받은 zip 안의 GeoJSON 두 개를 읽는다.
 *   - ...Station.geojson         : 역 (노선마다 한 건씩. 승강장 범위가 짧은 선으로 들어 있다)
 *   - ...RailroadSection.geojson : 선로 구간 (선)
 * 속성: N02_001 철도 구분, N02_002 사업자 종별, N02_003 노선명, N02_004 운영 회사, N02_005 역명,
 *       N02_005c 역 코드, N02_005g 그룹 코드(같은 역으로 묶이는 것끼리 같은 값)
 */
public final class RailNetworkParser {

    private RailNetworkParser() {}

    public static class RawStation {
        public String code;
        public String groupCode;
        public String name;
        public String operator;
        public String line;
        public String railClass;
        public String operatorType;
        /** {위도, 경도} 목록 */
        public List<double[]> points = new ArrayList<>();
    }

    public static class RawSection {
        public String operator;
        public String line;
        public String railClass;
        public String operatorType;
        public List<double[]> points = new ArrayList<>();
    }

    public static class RawNetwork {
        public final List<RawStation> stations = new ArrayList<>();
        public final List<RawSection> sections = new ArrayList<>();
        /** 어떤 파일에서 읽었는지 (예: "N02-23_GML.zip") */
        public String source;
    }

    /** zip 파일, 또는 GeoJSON 두 개가 들어 있는 폴더를 읽는다. */
    public static RawNetwork read(Path path) throws IOException {
        if (!Files.exists(path)) throw new IOException("파일이 없습니다: " + path);
        RawNetwork network = new RawNetwork();
        network.source = path.getFileName().toString();

        if (Files.isDirectory(path)) {
            Path stations = null;
            Path sections = null;
            try (Stream<Path> walk = Files.walk(path, 4)) {
                for (Iterator<Path> it = walk.iterator(); it.hasNext(); ) {
                    Path p = it.next();
                    String name = p.toString().replace('\\', '/').toLowerCase(Locale.ROOT);
                    if (isStationFile(name) && better(name, stations == null ? null : stations.toString())) stations = p;
                    if (isSectionFile(name) && better(name, sections == null ? null : sections.toString())) sections = p;
                }
            }
            if (stations == null || sections == null) throw new IOException(missingMessage(path.toString()));
            try (InputStream in = new BufferedInputStream(Files.newInputStream(stations))) { readStations(in, network); }
            try (InputStream in = new BufferedInputStream(Files.newInputStream(sections))) { readSections(in, network); }
            return network;
        }

        // 압축 파일 안의 일본어 폴더 이름(Shift_JIS) 때문에 기본 문자셋으로는 열리지 않는 경우가 있다
        try (ZipFile zip = new ZipFile(path.toFile(), Charset.forName("MS932"))) {
            ZipEntry stations = null;
            ZipEntry sections = null;
            for (Enumeration<? extends ZipEntry> e = zip.entries(); e.hasMoreElements(); ) {
                ZipEntry entry = e.nextElement();
                if (entry.isDirectory()) continue;
                String name = entry.getName().replace('\\', '/').toLowerCase(Locale.ROOT);
                if (isStationFile(name) && better(name, stations == null ? null : stations.getName())) stations = entry;
                if (isSectionFile(name) && better(name, sections == null ? null : sections.getName())) sections = entry;
            }
            if (stations == null || sections == null) throw new IOException(missingMessage(path.toString()));
            try (InputStream in = new BufferedInputStream(zip.getInputStream(stations))) { readStations(in, network); }
            try (InputStream in = new BufferedInputStream(zip.getInputStream(sections))) { readSections(in, network); }
        }
        return network;
    }

    private static boolean isStationFile(String lowerName) {
        return lowerName.endsWith("station.geojson");
    }

    private static boolean isSectionFile(String lowerName) {
        return lowerName.endsWith("railroadsection.geojson");
    }

    /** 같은 파일이 UTF-8 폴더와 Shift-JIS 폴더에 둘 다 있으면 UTF-8 쪽을 쓴다. */
    private static boolean better(String candidateLower, String current) {
        if (current == null) return true;
        boolean candidateUtf8 = candidateLower.contains("utf-8") || candidateLower.contains("utf8");
        String currentLower = current.replace('\\', '/').toLowerCase(Locale.ROOT);
        boolean currentUtf8 = currentLower.contains("utf-8") || currentLower.contains("utf8");
        return candidateUtf8 && !currentUtf8;
    }

    private static String missingMessage(String where) {
        return "'" + where + "' 안에서 Station.geojson / RailroadSection.geojson 을 찾지 못했습니다. "
                + "국토수치정보 '鉄道' 데이터의 GeoJSON 이 포함된 연도(2020년 이후) 파일인지 확인해 주세요.";
    }

    public static void readStations(InputStream in, RawNetwork network) throws IOException {
        forEachFeature(in, feature -> {
            JsonNode props = feature.path("properties");
            RawStation station = new RawStation();
            station.railClass = text(props, "N02_001");
            station.operatorType = text(props, "N02_002");
            station.line = text(props, "N02_003");
            station.operator = text(props, "N02_004");
            station.name = text(props, "N02_005");
            station.code = text(props, "N02_005c");
            station.groupCode = text(props, "N02_005g");
            for (List<double[]> part : geometryParts(feature.path("geometry"))) station.points.addAll(part);
            if (station.name != null && !station.points.isEmpty()) network.stations.add(station);
        });
    }

    public static void readSections(InputStream in, RawNetwork network) throws IOException {
        forEachFeature(in, feature -> {
            JsonNode props = feature.path("properties");
            String railClass = text(props, "N02_001");
            String operatorType = text(props, "N02_002");
            String line = text(props, "N02_003");
            String operator = text(props, "N02_004");
            for (List<double[]> part : geometryParts(feature.path("geometry"))) {
                if (part.size() < 2) continue;
                RawSection section = new RawSection();
                section.railClass = railClass;
                section.operatorType = operatorType;
                section.line = line;
                section.operator = operator;
                section.points = part;
                network.sections.add(section);
            }
        });
    }

    private interface FeatureHandler {
        void accept(JsonNode feature);
    }

    /** "features" 배열을 한 건씩 읽는다 (파일 전체를 메모리에 올리지 않는다). */
    private static void forEachFeature(InputStream in, FeatureHandler handler) throws IOException {
        ObjectMapper mapper = new ObjectMapper();
        try (JsonParser parser = mapper.getFactory().createParser(in)) {
            if (parser.nextToken() != JsonToken.START_OBJECT) throw new IOException("GeoJSON 형식이 아닙니다.");
            while (parser.nextToken() == JsonToken.FIELD_NAME) {
                String field = parser.getCurrentName();
                JsonToken value = parser.nextToken();
                if ("features".equals(field) && value == JsonToken.START_ARRAY) {
                    while (parser.nextToken() == JsonToken.START_OBJECT) {
                        JsonNode feature = mapper.readTree(parser);
                        handler.accept(feature);
                    }
                } else {
                    parser.skipChildren();
                }
            }
        }
    }

    /** 속성 값을 문자열로. 연도에 따라 대소문자가 다른 경우가 있어 키는 대소문자를 가리지 않고 찾는다. */
    private static String text(JsonNode props, String key) {
        JsonNode value = props.get(key);
        if (value == null) {
            for (Iterator<String> it = props.fieldNames(); it.hasNext(); ) {
                String name = it.next();
                if (name.equalsIgnoreCase(key)) {
                    value = props.get(name);
                    break;
                }
            }
        }
        if (value == null || value.isNull()) return null;
        String s = value.asText().trim();
        return s.isEmpty() ? null : s;
    }

    /** Point / LineString / MultiLineString 을 "점 목록의 목록"으로 푼다. 좌표는 [경도, 위도] 순서로 들어 있다. */
    private static List<List<double[]>> geometryParts(JsonNode geometry) {
        List<List<double[]>> parts = new ArrayList<>();
        String type = geometry.path("type").asText("");
        JsonNode coordinates = geometry.path("coordinates");
        switch (type) {
            case "Point": {
                List<double[]> one = new ArrayList<>();
                addPoint(coordinates, one);
                if (!one.isEmpty()) parts.add(one);
                break;
            }
            case "LineString":
            case "MultiPoint":
                parts.add(line(coordinates));
                break;
            case "MultiLineString":
            case "Polygon":
                for (JsonNode part : coordinates) parts.add(line(part));
                break;
            default:
                break;
        }
        return parts;
    }

    private static List<double[]> line(JsonNode coordinates) {
        List<double[]> points = new ArrayList<>();
        for (JsonNode c : coordinates) addPoint(c, points);
        return points;
    }

    private static void addPoint(JsonNode c, List<double[]> into) {
        if (c == null || !c.isArray() || c.size() < 2) return;
        double lng = c.get(0).asDouble(Double.NaN);
        double lat = c.get(1).asDouble(Double.NaN);
        if (Double.isNaN(lat) || Double.isNaN(lng)) return;
        into.add(new double[]{lat, lng});
    }
}
