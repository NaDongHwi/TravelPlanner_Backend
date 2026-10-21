package com.travel.planner.util;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 장기 투어에서 자동으로 더할 수 있는 주요 관광 도시 (이름은 장소 DB 의 도시 이름과 같은 한국어 표기, 좌표는 도심 대략값).
 * 더할 때는 장소 DB 에 장소가 넉넉한 도시(RICH_PLACES 곳 이상)를 먼저 고른다. 그런 도시가 근처에 없을 때만 DB 에 없는 도시를 더하고, 그 도시는 일정 생성 때 구글에서 장소를 모은다.
 * 섬 구분: M 본토(혼슈·시코쿠·규슈), H 홋카이도, O 오키나와. 바다를 건너는 도시는 같은 섬을 고른 경우에만 더한다.
 */
public final class JapanCities {

    private JapanCities() {}

    public static final class City {
        public final String name;
        public final double lat;
        public final double lng;
        public final char island;

        City(String name, double lat, double lng, char island) {
            this.name = name;
            this.lat = lat;
            this.lng = lng;
            this.island = island;
        }
    }

    public static final Map<String, City> CITIES = new LinkedHashMap<>();

    private static void add(String name, double lat, double lng, char island) {
        CITIES.put(name, new City(name, lat, lng, island));
    }

    static {
        add("도쿄", 35.681, 139.767, 'M');
        add("요코하마", 35.444, 139.638, 'M');
        add("가마쿠라", 35.319, 139.547, 'M');
        add("하코네", 35.232, 139.107, 'M');
        add("닛코", 36.750, 139.598, 'M');
        add("가와구치코", 35.497, 138.756, 'M');
        add("가루이자와", 36.348, 138.597, 'M');
        add("나고야", 35.170, 136.882, 'M');
        add("시즈오카", 34.972, 138.389, 'M');
        add("하마마쓰", 34.703, 137.735, 'M');
        add("다카야마", 36.146, 137.252, 'M');
        add("가나자와", 36.578, 136.648, 'M');
        add("오사카", 34.702, 135.495, 'M');
        add("교토", 35.011, 135.768, 'M');
        add("나라", 34.685, 135.805, 'M');
        add("고베", 34.690, 135.196, 'M');
        add("히메지", 34.827, 134.690, 'M');
        add("와카야마", 34.226, 135.167, 'M');
        add("오카야마", 34.666, 133.918, 'M');
        add("히로시마", 34.397, 132.475, 'M');
        add("다카마쓰", 34.340, 134.047, 'M');
        add("마쓰야마", 33.839, 132.765, 'M');
        add("후쿠오카", 33.590, 130.420, 'M');
        add("기타큐슈", 33.884, 130.883, 'M');
        add("나가사키", 32.750, 129.878, 'M');
        add("구마모토", 32.803, 130.708, 'M');
        add("벳푸", 33.284, 131.491, 'M');
        add("유후시", 33.267, 131.368, 'M');            // 유후인 (DB 도시 이름이 "유후시")
        add("가고시마", 31.597, 130.557, 'M');
        add("니가타", 37.916, 139.036, 'M');
        // 장소 DB 에 이미 있는 도시 (이름은 DB 의 표기 그대로)
        add("누마즈", 35.082, 138.860, 'M');
        add("아타미", 35.097, 139.077, 'M');
        add("후지노미야", 35.233, 138.608, 'M');
        add("에노시마", 35.300, 139.480, 'M');
        add("돗토리", 35.515, 133.921, 'M');
        add("마쓰에", 35.474, 133.050, 'M');
        add("센다이", 38.268, 140.870, 'M');
        add("아오모리", 40.822, 140.747, 'M');
        add("삿포로", 43.062, 141.354, 'H');
        add("오타루", 43.190, 140.994, 'H');
        add("하코다테", 41.773, 140.726, 'H');
        add("아사히카와", 43.771, 142.365, 'H');
        add("후라노", 43.342, 142.383, 'H');
        add("비에이조", 43.588, 142.466, 'H');
        add("노보리베쓰", 42.494, 141.143, 'H');
        add("오키나와", 26.212, 127.681, 'O');
    }

    /** 이름이 표에 있으면 그 도시 (공백·"시" 붙은 이름도 찾는다) */
    public static City find(String name) {
        if (name == null) return null;
        String n = name.trim();
        City c = CITIES.get(n);
        if (c == null && n.endsWith("시")) c = CITIES.get(n.substring(0, n.length() - 1));
        return c;
    }

    /** 근처 도시를 더할 때 고른 도시에서 이 거리(km) 안의 도시만 본다 */
    public static final double NEARBY_KM = 250;
    /** 투어에서 자동으로 늘릴 수 있는 도시 수의 상한 (고른 도시 포함) */
    public static final int MAX_TOUR_CITIES = 25;

    /**
     * 장기 투어: 일수에 맞게(도시당 약 TripProfile.TOUR_DAYS_PER_CITY 일) 근처 도시를 더하고, 입국 쪽에서 출국 쪽으로 이어지게 순서를 맞춘다.
     * 고른 도시 중 표에 없는 도시가 있으면 순서는 건드리지 않고 그 도시를 그대로 둔다.
     * @return 새 도시 목록 (더할 것이 없으면 원래 목록 그대로)
     */
    public static List<String> expandForTour(List<String> selected, int totalDays, String inCity, String outCity) {
        return expandForTour(selected, totalDays, inCity, outCity, java.util.Collections.emptyMap());
    }

    /** 장소 DB 에 이만큼 이상 장소가 있는 도시를 먼저 더한다 */
    public static final int RICH_PLACES = 30;

    /** @param placeCounts 도시 이름 → 장소 DB 의 장소 수 (모르면 빈 맵: 거리만 본다) */
    public static List<String> expandForTour(List<String> selected, int totalDays, String inCity, String outCity,
                                             Map<String, Long> placeCounts) {
        int target = Math.min(MAX_TOUR_CITIES, (int) Math.ceil(totalDays / (double) TripProfile.TOUR_DAYS_PER_CITY));
        List<String> cities = new ArrayList<>(selected);
        if (cities.size() >= target) return cities;
        List<City> known = new ArrayList<>();
        for (String s : cities) {
            City c = find(s);
            if (c != null) known.add(c);
        }
        if (known.isEmpty()) return cities;

        List<String> added = new ArrayList<>();
        boolean anyCounts = placeCounts != null && !placeCounts.isEmpty();
        while (cities.size() < target) {
            // 1차: 장소가 넉넉한 도시 중 가장 가까운 곳, 2차: 그런 곳이 없으면 아무 도시나 가장 가까운 곳
            City best = nearestCandidate(cities, known, anyCounts ? placeCounts : null);
            if (best == null && anyCounts) best = nearestCandidate(cities, known, null);
            if (best == null) break;
            cities.add(best.name);
            known.add(best);
            added.add(best.name);
        }
        if (added.isEmpty()) return cities;
        return orderAsRoute(cities, inCity, outCity);
    }

    private static City nearestCandidate(List<String> chosen, List<City> known, Map<String, Long> richOnly) {
        City best = null;
        double bestKm = Double.MAX_VALUE;
        for (City cand : CITIES.values()) {
            if (chosen.contains(cand.name)) continue;
            if (richOnly != null && richOnly.getOrDefault(cand.name, 0L) < RICH_PLACES) continue;
            for (City k : known) {
                if (k.island != cand.island) continue;            // 바다 건너는 도시는 같은 섬을 고른 경우에만
                double km = DistanceUtil.calculateDistance(k.lat, k.lng, cand.lat, cand.lng);
                if (km <= NEARBY_KM && km < bestKm) {
                    bestKm = km;
                    best = cand;
                }
            }
        }
        return best;
    }

    /**
     * 입국 도시 가까운 곳에서 시작해 가장 가까운 도시로 이어 가고, 출국 도시 가까운 곳을 마지막에 둔다 (표에 없는 도시는 맨 뒤).
     * 가장 가까운 도시로만 이으면 길에서 벗어난 도시(가나자와 등)가 끝까지 남아 마지막에 크게 되돌아가므로,
     * 이은 뒤 구간 뒤집기(2-opt)로 엇갈린 구간을 편다. 첫 도시와 마지막 도시는 그대로 둔다.
     */
    public static List<String> orderAsRoute(List<String> cities, String inCity, String outCity) {
        List<City> known = new ArrayList<>();
        List<String> unknown = new ArrayList<>();
        for (String s : cities) {
            City c = find(s);
            if (c != null) known.add(c);
            else unknown.add(s);
        }
        if (known.size() < 2) return cities;
        City in = nearestTo(known, find(inCity));
        City out = nearestTo(known, find(outCity));
        if (in == null) in = known.get(0);
        List<City> rest = new ArrayList<>(known);
        rest.remove(in);
        if (out != null && out != in) rest.remove(out);
        List<String> route = new ArrayList<>();
        route.add(in.name);
        City cur = in;
        while (!rest.isEmpty()) {
            City next = null;
            double nextKm = Double.MAX_VALUE;
            for (City c : rest) {
                double km = DistanceUtil.calculateDistance(cur.lat, cur.lng, c.lat, c.lng);
                if (km < nextKm) {
                    nextKm = km;
                    next = c;
                }
            }
            route.add(next.name);
            rest.remove(next);
            cur = next;
        }
        if (out != null && out != in) route.add(out.name);
        // 입국·출국 도시가 같으면 마지막에 다시 입국 도시로 돌아오는 길이므로, 돌아오는 구간까지 넣어 편다
        route = untangle(route, out == null || out == in);
        route.addAll(unknown);
        return route;
    }

    /**
     * 2-opt: 두 구간을 바꿔 이으면 전체 거리가 줄어드는 동안 가운데를 뒤집는다.
     * @param returnsToStart true 면 마지막 도시 뒤에 첫 도시로 돌아오는 구간이 있다고 보고(첫 도시만 고정), false 면 양 끝 고정
     */
    static List<String> untangle(List<String> route, boolean returnsToStart) {
        int size = route.size();
        if (size < (returnsToStart ? 3 : 4)) return route;
        int n = returnsToStart ? size + 1 : size;
        City[] c = new City[n];
        for (int i = 0; i < size; i++) c[i] = find(route.get(i));
        if (returnsToStart) c[size] = c[0];   // 돌아오는 자리 (계산용)
        boolean improved = true;
        for (int pass = 0; improved && pass < 50; pass++) {
            improved = false;
            for (int i = 0; i < n - 3; i++) {
                for (int j = i + 2; j < n - 1; j++) {
                    double before = km(c[i], c[i + 1]) + km(c[j], c[j + 1]);
                    double after = km(c[i], c[j]) + km(c[i + 1], c[j + 1]);
                    if (after + 1e-6 < before) {
                        for (int a = i + 1, b = j; a < b; a++, b--) {
                            City t = c[a]; c[a] = c[b]; c[b] = t;
                        }
                        improved = true;
                    }
                }
            }
        }
        List<String> out = new ArrayList<>(size);
        for (int i = 0; i < size; i++) out.add(c[i].name);
        return out;
    }

    private static double km(City a, City b) {
        return DistanceUtil.calculateDistance(a.lat, a.lng, b.lat, b.lng);
    }

    private static City nearestTo(List<City> cities, City point) {
        if (point == null) return null;
        City best = null;
        double bestKm = Double.MAX_VALUE;
        for (City c : cities) {
            double km = DistanceUtil.calculateDistance(point.lat, point.lng, c.lat, c.lng);
            if (km < bestKm) {
                bestKm = km;
                best = c;
            }
        }
        return best;
    }
}
