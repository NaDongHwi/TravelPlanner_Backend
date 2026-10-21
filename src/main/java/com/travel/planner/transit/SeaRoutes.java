package com.travel.planner.transit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.travel.planner.util.DistanceUtil;
import com.travel.planner.util.TravelTimeEstimator;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 바다를 건너는 구간(배·비행기).
 *
 * 철도·버스 노선 자료에는 배와 비행기가 없어서, 섬으로 가는 구간이 "택시"로 나오던 문제를 고친다.
 *  1) 두 지점이 어느 땅(본토·홋카이도·오키나와 본섬·주요 섬 17곳)에 있는지 본다. 섬 모양은 국토수치정보 행정구역 자료(resources/sea/islands.json).
 *  2) 땅이 다르면 아래 표의 배·비행기 노선으로 가장 빠른 길을 찾는다 (갈아타기 포함, 최대 3번 타기).
 *  3) 사용자가 섬 이동 수단(비행기/배)을 골랐으면 그 수단을 먼저 쓰고, 그 수단으로 갈 수 없을 때만 다른 수단을 쓴다.
 *
 * 본토(혼슈·규슈·시코쿠)와 홋카이도는 철도(세이칸 터널)로 이어져 있으므로, 비행기를 고른 경우에만 바다 건너는 구간으로 본다.
 *
 * 운임·소요 시간은 2026년 10월 기준 공개 자료(운항사 안내를 옮긴 여행 정보 사이트)로 넣은 대략값이다. 계절·연료 할증으로 바뀐다.
 * 비행기 요금은 예약 시점에 따라 크게 달라 넣지 않는다(null).
 */
public final class SeaRoutes {

    private SeaRoutes() {}

    // ------------------------------------------------------------------ 섬 이동 수단 선택

    public enum Pref {
        AUTO, SHIP, FLIGHT;

        /** "비행기"/"항공"/"flight" → FLIGHT, "배"/"선박"/"페리"/"ship" → SHIP, 나머지 → AUTO */
        public static Pref of(String text) {
            if (text == null) return AUTO;
            String t = text.trim().toLowerCase(Locale.ROOT);
            if (t.isEmpty()) return AUTO;
            if (t.contains("비행") || t.contains("항공") || t.contains("flight") || t.contains("plane") || t.contains("air")) return FLIGHT;
            if (t.equals("배") || t.contains("선박") || t.contains("페리") || t.contains("여객선") || t.contains("ferry")
                    || t.contains("ship") || t.contains("boat")) return SHIP;
            return AUTO;
        }
    }

    /**
     * 이동 수단 문자열에 섬 이동 수단을 붙인다. 구간 계산(TransitLegPlanner)과 이동 시간 추정(TravelTimeEstimator)은
     * 이동 수단 문자열만 받으므로, 여기에 붙여 넘긴다. 기존의 "도보"·"대중교통"·"렌트" 판단에는 영향이 없다.
     */
    public static String tag(String transportation, String islandTransport) {
        Pref pref = Pref.of(islandTransport);
        String base = untag(transportation);
        if (pref == Pref.AUTO) return base;
        return (base == null ? "" : base) + TAG + pref.name();
    }

    static final String TAG = " #island=";

    public static String untag(String transportation) {
        if (transportation == null) return null;
        int i = transportation.indexOf(TAG);
        return i < 0 ? transportation : transportation.substring(0, i);
    }

    public static Pref prefOf(String transportation) {
        if (transportation == null) return Pref.AUTO;
        int i = transportation.indexOf(TAG);
        if (i < 0) return Pref.AUTO;
        try {
            return Pref.valueOf(transportation.substring(i + TAG.length()).trim());
        } catch (IllegalArgumentException e) {
            return Pref.AUTO;
        }
    }

    // ------------------------------------------------------------------ 땅 구분

    public static final String MAINLAND = "MAINLAND";        // 혼슈·규슈·시코쿠 (다리·철도로 이어진 섬 포함)
    public static final String HOKKAIDO = "HOKKAIDO";
    public static final String OKINAWA = "OKINAWA";          // 오키나와 본섬 (다리로 이어진 섬 포함)
    public static final String OKINAWA_OTHER = "OKINAWA_OTHER";   // 표에 없는 오키나와의 다른 섬

    private static final class Island {
        final String id;
        final String name;
        final List<double[][]> rings;   // [i] = {lng, lat}
        final double minLat, maxLat, minLng, maxLng;

        Island(String id, String name, List<double[][]> rings) {
            this.id = id;
            this.name = name;
            this.rings = rings;
            double a = 90, b = -90, c = 180, d = -180;
            for (double[][] r : rings) {
                for (double[] p : r) {
                    a = Math.min(a, p[1]); b = Math.max(b, p[1]); c = Math.min(c, p[0]); d = Math.max(d, p[0]);
                }
            }
            minLat = a; maxLat = b; minLng = c; maxLng = d;
        }

        boolean contains(double lat, double lng) {
            if (lat < minLat || lat > maxLat || lng < minLng || lng > maxLng) return false;
            for (double[][] r : rings) {
                if (inRing(r, lat, lng)) return true;
            }
            return false;
        }
    }

    private static boolean inRing(double[][] ring, double lat, double lng) {
        boolean in = false;
        for (int i = 0, j = ring.length - 1; i < ring.length; j = i++) {
            double xi = ring[i][0], yi = ring[i][1], xj = ring[j][0], yj = ring[j][1];
            if ((yi > lat) != (yj > lat) && lng < (xj - xi) * (lat - yi) / (yj - yi) + xi) in = !in;
        }
        return in;
    }

    private static final List<Island> ISLANDS = loadIslands();
    private static final Map<String, String> NAMES = new LinkedHashMap<>();

    static {
        NAMES.put(MAINLAND, "본토");
        NAMES.put(HOKKAIDO, "홋카이도");
        NAMES.put(OKINAWA, "오키나와 본섬");
        NAMES.put(OKINAWA_OTHER, "오키나와의 섬");
        for (Island i : ISLANDS) NAMES.put(i.id, i.name);
    }

    private static List<Island> loadIslands() {
        try (InputStream in = SeaRoutes.class.getResourceAsStream("/sea/islands.json")) {
            if (in == null) return List.of();
            JsonNode root = new ObjectMapper().readTree(in);
            List<Island> out = new ArrayList<>();
            for (JsonNode n : root.path("islands")) {
                List<double[][]> rings = new ArrayList<>();
                for (JsonNode ring : n.path("rings")) {
                    double[][] r = new double[ring.size()][];
                    for (int k = 0; k < ring.size(); k++) r[k] = new double[]{ring.get(k).get(0).asDouble(), ring.get(k).get(1).asDouble()};
                    rings.add(r);
                }
                out.add(new Island(n.path("id").asText(), n.path("name").asText(), rings));
            }
            return Collections.unmodifiableList(out);
        } catch (Exception e) {
            System.out.println("[배·비행기] 섬 자료를 읽지 못했습니다: " + e.getMessage());
            return List.of();
        }
    }

    /** 그 지점이 있는 땅. 일본 밖이거나 좌표가 없으면 null */
    public static String landOf(double lat, double lng) {
        if (lat < 20 || lat > 46 || lng < 122 || lng > 154) return null;
        // 한반도·제주(서쪽), 부산 앞(쓰시마 북쪽), 연해주(동해 건너)는 일본이 아니다
        if ((lng < 128.9 && lat > 32.9) || (lat > 34.75 && lng < 130.0) || (lat > 38.6 && lng < 139.0)) return null;
        for (Island i : ISLANDS) {
            if (i.contains(lat, lng)) return i.id;
        }
        if (lat >= 24.0 && lat <= 27.9 && lng >= 122.9 && lng <= 131.4) {
            if (lat >= 26.05 && lat <= 26.95 && lng >= 127.62 && lng <= 128.35) return OKINAWA;
            return OKINAWA_OTHER;
        }
        // 쓰가루 해협 북쪽 (오마 반도 41.5N 140.9E 는 아오모리, 마쓰마에 41.4N 140.1E 는 홋카이도)
        if (lat > 41.6 || (lat > 41.35 && lng < 140.35)) return HOKKAIDO;
        return MAINLAND;
    }

    public static String landName(String land) {
        return NAMES.getOrDefault(land, land);
    }

    // ------------------------------------------------------------------ 항구·공항과 노선표

    public static final class Hub {
        public final String id;
        public final String name;
        public final double lat, lng;
        public final String land;
        final boolean airport;

        Hub(String id, String name, double lat, double lng, String land, boolean airport) {
            this.id = id;
            this.name = name;
            this.lat = lat;
            this.lng = lng;
            this.land = land;
            this.airport = airport;
        }
    }

    public static final class Route {
        public final Hub a, b;
        public final boolean flight;
        /** 타고 있는 시간(분) */
        public final int rideMinutes;
        /** 타기 전 기다림(배: 승선 수속, 비행기: 공항 도착~탑승) + 내린 뒤(비행기: 짐 찾기) */
        public final int boardMinutes;
        public final int afterMinutes;
        /** 성인 편도(엔). 모르면 null */
        public final Integer fareYen;
        public final String operator;
        /** 운항 횟수·다른 배편 안내 */
        public final String note;

        Route(Hub a, Hub b, boolean flight, int rideMinutes, int boardMinutes, int afterMinutes, Integer fareYen, String operator, String note) {
            this.a = a;
            this.b = b;
            this.flight = flight;
            this.rideMinutes = rideMinutes;
            this.boardMinutes = boardMinutes;
            this.afterMinutes = afterMinutes;
            this.fareYen = fareYen;
            this.operator = operator;
            this.note = note;
        }

        int totalMinutes() { return boardMinutes + rideMinutes + afterMinutes; }

        public String modeName() { return flight ? "비행기" : "배"; }
    }

    private static final Map<String, Hub> HUBS = new LinkedHashMap<>();
    private static final List<Route> ROUTES = new ArrayList<>();

    private static Hub port(String id, String name, double lat, double lng, String land) {
        Hub h = new Hub(id, name, lat, lng, land, false);
        HUBS.put(id, h);
        return h;
    }

    private static Hub airport(String id, String name, double lat, double lng, String land) {
        Hub h = new Hub(id, name, lat, lng, land, true);
        HUBS.put(id, h);
        return h;
    }

    /** 배 (짧은 배는 15분 전, 1시간 넘는 배는 30분 전에 타는 곳에 간다) */
    private static void ship(String a, String b, int ride, Integer fare, String operator, String note) {
        ROUTES.add(new Route(HUBS.get(a), HUBS.get(b), false, ride, ride >= 60 ? 30 : 15, 5, fare, operator, note));
    }

    /** 비행기: 비행시간은 거리로 추정 (이륙·착륙 포함 30분 + 1km 당 0.072분). 공항에 1시간 전 도착, 내린 뒤 20분 */
    private static void flight(String a, String b) {
        Hub x = HUBS.get(a), y = HUBS.get(b);
        double km = DistanceUtil.calculateDistance(x.lat, x.lng, y.lat, y.lng);
        int ride = (int) (Math.ceil((30 + km * 0.072) / 5.0) * 5);
        ROUTES.add(new Route(x, y, true, ride, 60, 20, null, null, null));
    }

    private static void flights(String from, String... to) {
        for (String t : to) flight(from, t);
    }

    static {
        // 본토 공항
        airport("HND", "하네다 공항", 35.5494, 139.7798, MAINLAND);
        airport("NRT", "나리타 공항", 35.7720, 140.3929, MAINLAND);
        airport("CHF", "조후 비행장", 35.6717, 139.5281, MAINLAND);
        airport("KIX", "간사이 공항", 34.4320, 135.2304, MAINLAND);
        airport("ITM", "이타미 공항", 34.7855, 135.4382, MAINLAND);
        airport("UKB", "고베 공항", 34.6328, 135.2239, MAINLAND);
        airport("NGO", "주부(센트레아) 공항", 34.8584, 136.8054, MAINLAND);
        airport("FUK", "후쿠오카 공항", 33.5859, 130.4507, MAINLAND);
        airport("KOJ", "가고시마 공항", 31.8034, 130.7194, MAINLAND);
        airport("HIJ", "히로시마 공항", 34.4361, 132.9195, MAINLAND);
        airport("SDJ", "센다이 공항", 38.1397, 140.9170, MAINLAND);
        // 홋카이도
        airport("CTS", "신치토세 공항", 42.7752, 141.6923, HOKKAIDO);
        airport("HKD", "하코다테 공항", 41.7700, 140.8219, HOKKAIDO);
        // 오키나와·섬
        airport("OKA", "나하 공항", 26.1958, 127.6459, OKINAWA);
        airport("ISG", "이시가키 공항", 24.3964, 124.2451, "ISHIGAKI");
        airport("MMY", "미야코 공항", 24.7828, 125.2950, "MIYAKO");
        airport("UEO", "구메지마 공항", 26.3635, 126.7139, "KUME");
        airport("KUM", "야쿠시마 공항", 30.3856, 130.6590, "YAKUSHIMA");
        airport("RIS", "리시리 공항", 45.2420, 141.1864, "RISHIRI");
        airport("OIM", "오시마 공항", 34.7820, 139.3603, "IZUOSHIMA");

        // 항구
        port("MIYAJIMAGUCHI", "미야지마구치 선착장", 34.3117, 132.3033, MAINLAND);
        port("MIYAJIMA", "미야지마 선착장", 34.3003, 132.3237, "MIYAJIMA");
        port("UNO", "우노항", 34.4897, 133.9555, MAINLAND);
        port("TAKAMATSU", "다카마쓰항", 34.3527, 134.0500, MAINLAND);
        port("MIYANOURA_N", "나오시마 미야노우라항", 34.4622, 133.9757, "NAOSHIMA");
        port("TONOSHO", "쇼도시마 도노쇼항", 34.4870, 134.1802, "SHODOSHIMA");
        port("NIIGATA", "니가타항 사도기선 터미널", 37.9289, 139.0600, MAINLAND);
        port("RYOTSU", "사도 료쓰항", 38.0820, 138.4389, "SADO");
        port("KAGOSHIMA_S", "가고시마항 남부두(고속선)", 31.5874, 130.5652, MAINLAND);
        port("MIYANOURA_Y", "야쿠시마 미야노우라항", 30.4264, 130.5713, "YAKUSHIMA");
        port("KAGOSHIMA_F", "가고시마항 사쿠라지마 페리 터미널", 31.5953, 130.5628, MAINLAND);
        port("SAKURAJIMA", "사쿠라지마항", 31.5913, 130.5993, "SAKURAJIMA");
        port("TOMARI", "나하 도마리항", 26.2262, 127.6829, OKINAWA);
        port("TOKASHIKI", "도카시키항", 26.1967, 127.3612, "TOKASHIKI");
        port("ZAMAMI", "자마미항", 26.2295, 127.3036, "ZAMAMI");
        port("MOTOBU", "모토부항", 26.6555, 127.8798, OKINAWA);
        port("IE", "이에항", 26.7090, 127.8099, "IE");
        port("ISHIGAKI_P", "이시가키항 이도 터미널", 24.3381, 124.1554, "ISHIGAKI");
        port("TAKETOMI", "다케토미항", 24.3346, 124.0936, "TAKETOMI");
        port("UEHARA", "이리오모테 우에하라항", 24.4172, 123.7839, "IRIOMOTE");
        port("OHARA", "이리오모테 오하라항", 24.2674, 123.8789, "IRIOMOTE");
        port("WAKKANAI", "왓카나이항 페리 터미널", 45.4155, 141.6802, HOKKAIDO);
        port("OSHIDOMARI", "리시리 오시도마리항", 45.2412, 141.2333, "RISHIRI");
        port("KAFUKA", "레분 가후카항", 45.3045, 141.0484, "REBUN");
        port("TAKESHIBA", "도쿄 다케시바 부두", 35.6528, 139.7620, MAINLAND);
        port("MOTOMACHI", "오시마 모토마치항", 34.7519, 139.3553, "IZUOSHIMA");

        // 배 (성인 편도, 2026년 10월 기준 대략값)
        ship("MIYAJIMAGUCHI", "MIYAJIMA", 10, 200, "JR 서일본 미야지마 페리 · 미야지마 마쓰다이 기선",
                "약 15분 간격. 섬에 들어갈 때 미야지마 방문세 100엔이 따로 붙습니다.");
        ship("UNO", "MIYANOURA_N", 20, 300, "시코쿠 기선", "페리 20분(여객선 15분), 하루 십여 편");
        ship("TAKAMATSU", "MIYANOURA_N", 50, 520, "시코쿠 기선", "페리 약 50분~1시간. 고속선(약 30분, 요금 다름)도 있습니다.");
        ship("TAKAMATSU", "TONOSHO", 60, 700, "시코쿠 페리 등", "페리 하루 약 15편. 고속선(35분, 1,400엔)은 운휴할 때가 있습니다.");
        ship("NIIGATA", "RYOTSU", 150, 3630, "사도 기선",
                "카페리 2등 기준, 하루 약 5왕복. 제트포일(약 1시간 7분, 8,310엔)도 있습니다.");
        ship("KAGOSHIMA_S", "MIYANOURA_Y", 110, 9200, "다네야쿠 고속선(토피·로켓)",
                "고속선 최단 1시간 50분(들르는 편은 더 걸림). 페리(약 4시간)는 하루 1편.");
        ship("KAGOSHIMA_F", "SAKURAJIMA", 15, 200, "사쿠라지마 페리", "낮에는 약 15~30분 간격 (심야는 줄어듦)");
        ship("TOMARI", "TOKASHIKI", 40, 2530, "도카시키 촌영선",
                "고속선 35~40분 기준, 하루 2~3편. 페리(약 70분, 1,690엔)는 하루 1편. 환경협력세 100엔 별도.");
        ship("TOMARI", "ZAMAMI", 50, 3140, "자마미 촌영선",
                "고속선 직행 약 50분 기준. 페리(약 2시간, 2,150엔)도 있습니다. 환경협력세 100엔 별도.");
        ship("MOTOBU", "IE", 30, null, "이에 촌영 페리", "약 30분, 하루 4편 안팎. 요금은 운항사에서 확인해 주세요.");
        ship("ISHIGAKI_P", "TAKETOMI", 15, 880, "야에야마 관광 페리 · 안에이 관광", "약 10~15분, 30분 안팎 간격. 요금은 연료 할증에 따라 790~880엔.");
        ship("ISHIGAKI_P", "UEHARA", 45, 2990, "야에야마 관광 페리 · 안에이 관광", "약 40~45분. 파도가 높으면 오하라항으로 바뀔 때가 있습니다.");
        ship("ISHIGAKI_P", "OHARA", 40, 2060, "야에야마 관광 페리 · 안에이 관광", "약 35~40분");
        ship("WAKKANAI", "OSHIDOMARI", 100, null, "하트랜드 페리", "약 1시간 40분. 요금·편수는 운항사에서 확인해 주세요.");
        ship("WAKKANAI", "KAFUKA", 115, null, "하트랜드 페리", "약 2시간. 요금·편수는 운항사에서 확인해 주세요.");
        ship("OSHIDOMARI", "KAFUKA", 45, null, "하트랜드 페리", "약 45분, 하루 1~4편");
        ship("TAKESHIBA", "MOTOMACHI", 105, null, "도카이 기선",
                "고속 제트선 약 1시간 45분 기준(대형선은 약 6시간). 요금은 운항사에서 확인해 주세요. 내리는 항구는 날씨에 따라 오카다항으로 바뀝니다.");

        // 비행기 (직항이 있는 구간만)
        flights("OKA", "HND", "NRT", "KIX", "ITM", "UKB", "NGO", "FUK", "KOJ", "HIJ", "SDJ", "CTS");
        flights("ISG", "HND", "NRT", "KIX", "NGO", "FUK", "OKA", "MMY");
        flights("MMY", "HND", "KIX", "OKA");
        flights("UEO", "OKA");
        flights("KUM", "KOJ", "FUK", "ITM");
        flights("RIS", "CTS");
        flights("OIM", "CHF", "HND");
        flights("CTS", "HND", "NRT", "KIX", "ITM", "UKB", "NGO", "FUK", "HIJ", "SDJ");
        flights("HKD", "HND", "ITM", "NGO");
    }

    /** 표에 있는 노선 (테스트·안내용) */
    public static List<Route> routes() {
        return Collections.unmodifiableList(ROUTES);
    }

    public static Hub hub(String id) {
        return HUBS.get(id);
    }

    // ------------------------------------------------------------------ 길 찾기

    /** 바다 건너는 길: 출발지 → (항구·공항 사이 이동) → 배/비행기 … → 도착지 */
    public static final class Crossing {
        public final String fromLand, toLand;
        /** 탈 노선들 (순서대로, 방향 맞춤: from → to) */
        public final List<Hub[]> hops = new ArrayList<>();
        public final List<Route> routes = new ArrayList<>();
        /** 전체 추정 시간(분): 항구·공항까지 가는 시간 + 기다림 + 타는 시간 + 내린 뒤 + 도착지까지 */
        public int minutes;
        /** 고른 수단으로 갈 수 없어 다른 수단을 쓴 경우의 안내 */
        public String prefNote;
        /** 길이 없다 (표에 없는 섬 등): 안내만 하고 시간은 대략 */
        public boolean unknown;

        Crossing(String fromLand, String toLand) {
            this.fromLand = fromLand;
            this.toLand = toLand;
        }

        public boolean anyFlight() {
            return routes.stream().anyMatch(r -> r.flight);
        }

        public boolean anyShip() {
            return routes.stream().anyMatch(r -> !r.flight);
        }
    }

    private static final java.util.concurrent.ConcurrentHashMap<String, Integer> MINUTES_CACHE = new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * 일정 엔진용: 바다를 건너면 그 추정 시간(분), 같은 땅이면 null.
     * 엔진은 후보마다 여러 번 부르므로, 땅·수단·대략 위치(약 2km 칸)가 같으면 앞의 계산을 다시 쓴다.
     */
    public static Integer crossingMinutes(double fromLat, double fromLng, double toLat, double toLng, String transportation) {
        String a = landOf(fromLat, fromLng);
        String b = landOf(toLat, toLng);
        if (a == null || b == null || a.equals(b)) return null;
        Pref pref = prefOf(transportation);
        if (isRailLinked(a, b) && pref != Pref.FLIGHT) return null;
        String key = a + '|' + b + '|' + transportation + '|' + Math.round(fromLat * 50) + ',' + Math.round(fromLng * 50)
                + '|' + Math.round(toLat * 50) + ',' + Math.round(toLng * 50);
        Integer cached = MINUTES_CACHE.get(key);
        if (cached != null) return cached;
        int minutes = search(a, b, fromLat, fromLng, toLat, toLng, untag(transportation), pref).minutes;
        if (MINUTES_CACHE.size() > 50_000) MINUTES_CACHE.clear();
        MINUTES_CACHE.put(key, minutes);
        return minutes;
    }

    /** 고르지 않은 수단을 쓸 때 더하는 벌점(분). 고른 수단으로 갈 수 있으면 그쪽을 쓰게 한다 */
    private static final int OTHER_MODE_PENALTY = 1000;

    /**
     * 두 지점이 서로 다른 땅이면 바다 건너는 길, 같은 땅이면 null.
     * @param transportation 땅 위에서의 이동 수단 (항구·공항까지 가는 시간 추정에 쓴다). 섬 이동 수단 태그가 붙어 있으면 그것도 읽는다
     */
    public static Crossing crossing(double fromLat, double fromLng, double toLat, double toLng, String transportation) {
        String a = landOf(fromLat, fromLng);
        String b = landOf(toLat, toLng);
        if (a == null || b == null || a.equals(b)) return null;
        Pref pref = prefOf(transportation);
        // 본토 ↔ 홋카이도는 철도로 갈 수 있다: 비행기를 고른 경우에만 바다 건너는 길로 본다
        if (isRailLinked(a, b) && pref != Pref.FLIGHT) return null;
        String ground = untag(transportation);
        return search(a, b, fromLat, fromLng, toLat, toLng, ground, pref);
    }

    private static boolean isRailLinked(String a, String b) {
        return (MAINLAND.equals(a) && HOKKAIDO.equals(b)) || (HOKKAIDO.equals(a) && MAINLAND.equals(b));
    }

    /** 땅 위 이동 시간 (항구·공항까지). 철도·버스 경로 대신 직선거리 추정을 쓴다 (택시를 타도 비슷하다) */
    private static int ground(double lat1, double lng1, double lat2, double lng2, String ground) {
        String mode = ground == null || ground.isBlank() ? "대중교통" : ground;
        // 걸어서만 다니는 여행이라도 항구·공항까지는 차나 버스를 탄다고 본다
        if (mode.contains("도보") && !mode.contains("대중교통") && DistanceUtil.calculateDistance(lat1, lng1, lat2, lng2) > 2.0) mode = "대중교통";
        return TravelTimeEstimator.minutesForDistance(DistanceUtil.calculateDistance(lat1, lng1, lat2, lng2), mode);
    }

    private static Crossing search(String fromLand, String toLand, double fLat, double fLng, double tLat, double tLng, String ground, Pref pref) {
        List<Hub> hubs = new ArrayList<>(HUBS.values());
        int n = hubs.size();
        Map<String, Integer> index = new LinkedHashMap<>();
        for (int i = 0; i < n; i++) index.put(hubs.get(i).id, i);

        // 상태: 허브 i 에 "내린 상태"(배·비행기를 막 탄 뒤) 또는 "출발지에서 막 온 상태". 갈아타기 횟수는 최대 3번 타기.
        final int maxRides = 3;
        double[][] best = new double[maxRides + 1][n];
        int[][] prevHub = new int[maxRides + 1][n];
        int[][] prevRoute = new int[maxRides + 1][n];
        for (double[] row : best) Arrays.fill(row, Double.MAX_VALUE);
        for (int[] row : prevHub) Arrays.fill(row, -1);

        // 0번 탄 상태: 출발지가 있는 땅의 허브까지 땅 위로 이동
        for (int i = 0; i < n; i++) {
            Hub h = hubs.get(i);
            if (h.land.equals(fromLand)) best[0][i] = ground(fLat, fLng, h.lat, h.lng, ground);
        }
        for (int k = 0; k < maxRides; k++) {
            // 같은 땅의 다른 허브로 옮겨 가기 (이시가키 공항 → 이시가키항 등)
            double[] here = best[k].clone();
            int[] moveFrom = new int[n];
            Arrays.fill(moveFrom, -1);
            for (int i = 0; i < n; i++) {
                if (best[k][i] == Double.MAX_VALUE) continue;
                Hub hi = hubs.get(i);
                for (int j = 0; j < n; j++) {
                    if (i == j) continue;
                    Hub hj = hubs.get(j);
                    if (!hj.land.equals(hi.land)) continue;
                    double c = best[k][i] + ground(hi.lat, hi.lng, hj.lat, hj.lng, ground);
                    if (c < here[j]) {
                        here[j] = c;
                        moveFrom[j] = i;
                    }
                }
            }
            for (int r = 0; r < ROUTES.size(); r++) {
                Route route = ROUTES.get(r);
                for (int dir = 0; dir < 2; dir++) {
                    Hub x = dir == 0 ? route.a : route.b;
                    Hub y = dir == 0 ? route.b : route.a;
                    int xi = index.get(x.id), yi = index.get(y.id);
                    if (here[xi] == Double.MAX_VALUE) continue;
                    double penalty = (pref == Pref.SHIP && route.flight) || (pref == Pref.FLIGHT && !route.flight) ? OTHER_MODE_PENALTY : 0;
                    double c = here[xi] + route.totalMinutes() + penalty;
                    if (c < best[k + 1][yi]) {
                        best[k + 1][yi] = c;
                        prevRoute[k + 1][yi] = r * 2 + dir;
                        prevHub[k + 1][yi] = moveFrom[xi] >= 0 ? moveFrom[xi] : xi;   // 이 노선을 타기 전(옮기기 전)에 있던 허브
                    }
                }
            }
        }
        // 도착: 도착지가 있는 땅의 허브에서 땅 위로
        double bestCost = Double.MAX_VALUE;
        int bestK = -1, bestHub = -1;
        for (int k = 1; k <= maxRides; k++) {
            for (int i = 0; i < n; i++) {
                if (best[k][i] == Double.MAX_VALUE) continue;
                Hub h = hubs.get(i);
                if (!h.land.equals(toLand)) continue;
                double c = best[k][i] + ground(h.lat, h.lng, tLat, tLng, ground);
                if (c < bestCost) {
                    bestCost = c;
                    bestK = k;
                    bestHub = i;
                }
            }
        }

        Crossing out = new Crossing(fromLand, toLand);
        if (bestK < 0) {
            out.unknown = true;
            out.minutes = unknownMinutes(fLat, fLng, tLat, tLng);
            return out;
        }
        // 거꾸로 따라가며 노선을 모은다
        List<Route> rs = new ArrayList<>();
        List<Hub[]> hops = new ArrayList<>();
        int k = bestK, at = bestHub;
        while (k > 0) {
            int code = prevRoute[k][at];
            Route route = ROUTES.get(code / 2);
            boolean forward = code % 2 == 0;
            rs.add(0, route);
            hops.add(0, forward ? new Hub[]{route.a, route.b} : new Hub[]{route.b, route.a});
            at = prevHub[k][at];   // 같은 땅 안 이동 시간은 아래에서 다시 계산한다
            k--;
        }
        out.routes.addAll(rs);
        out.hops.addAll(hops);
        // 시간 다시 계산 (벌점 없이)
        int minutes = ground(fLat, fLng, hops.get(0)[0].lat, hops.get(0)[0].lng, ground);
        for (int i = 0; i < rs.size(); i++) {
            if (i > 0) {
                Hub prevEnd = hops.get(i - 1)[1], start = hops.get(i)[0];
                if (prevEnd != start) minutes += ground(prevEnd.lat, prevEnd.lng, start.lat, start.lng, ground);
            }
            minutes += rs.get(i).totalMinutes();
        }
        Hub last = hops.get(hops.size() - 1)[1];
        minutes += ground(last.lat, last.lng, tLat, tLng, ground);
        out.minutes = minutes;
        if (pref == Pref.SHIP && out.anyFlight()) {
            out.prefNote = out.anyShip()
                    ? "배로만 가는 길이 없어 일부 구간은 비행기로 안내합니다."
                    : "이 구간은 다닐 만한 배편이 없어 비행기로 안내합니다.";
        } else if (pref == Pref.FLIGHT && out.anyShip()) {
            out.prefNote = out.anyFlight()
                    ? "공항이 없는 섬이라 마지막 구간은 배로 안내합니다."
                    : "이 구간은 비행기 편이 없어 배로 안내합니다.";
        }
        return out;
    }

    /** 표에 없는 섬: 배를 탄다고 보고 대략 (항구까지 30분 + 기다림 30분 + 바다 위 시속 25km + 내려서 20분) */
    private static int unknownMinutes(double fLat, double fLng, double tLat, double tLng) {
        double km = DistanceUtil.calculateDistance(fLat, fLng, tLat, tLng);
        return (int) (Math.ceil((80 + km / 25.0 * 60) / 5.0) * 5);
    }
}
