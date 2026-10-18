package com.travel.planner.transit;

import com.travel.planner.util.DistanceUtil;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 전국 버스 정류장과 "그 정류장에 서는 노선" (국토수치정보 버스 정류장 P11, 2022년).
 *
 * 이 자료에는 시각표·정류장 순서가 없다. 그래서 할 수 있는 것은
 * "출발지 근처 정류장과 도착지 근처 정류장에 같은 노선이 함께 서는가"(직행)와
 * "한 번 갈아타면 이어지는가"(환승 1회)를 찾는 것이다.
 * 시간은 거리 기준(도로 우회 1.3배, 시속 15km, 기다림 10분), 요금은 거리 기준 추정이다.
 *
 * 운영사가 시각표(GTFS)를 공개한 곳은 그쪽이 먼저이고, 이 색인은 그 자료로 경로를 못 찾은 구간에만 쓴다.
 * DB·스프링과 무관한 순수 계산이다.
 */
public final class BusStopIndex {

    /** 걸어서 타러 가는 정류장의 최대 거리(km). 정류장까지 걷는 시간은 경로 시간에 들어간다(최대 약 14분) */
    public static final double ACCESS_KM = 0.8;
    /** 먼 구간(FAR_KM 이상)에서 가까운 정류장이 없을 때 넓혀 보는 거리(km) */
    static final double WIDE_ACCESS_KM = 1.0;
    static final double FAR_KM = 5.0;
    /** 갈아탈 때 걸어서 옮길 수 있는 최대 거리(km) */
    static final double TRANSFER_KM = 0.25;
    /** 도로를 따라 가는 거리 = 직선거리 × 이 값 */
    static final double ROAD_FACTOR = 1.3;
    /** 버스 평균 속도 (정차 포함, km/h) */
    static final double BUS_KMH = 15.0;
    /** 시각표가 없으므로 한 번 탈 때마다 기다리는 시간으로 잡는 분 */
    static final int WAIT_MINUTES = 10;
    /** 한 정류장에서 볼 노선 수 상한 (노선이 수십 개 서는 터미널에서 계산이 길어지지 않게) */
    private static final int MAX_ROUTES_PER_SIDE = 60;

    /** 국토수치정보 버스 구분 코드 */
    public static final int TYPE_PRIVATE = 1;
    public static final int TYPE_PUBLIC = 2;
    public static final int TYPE_COMMUNITY = 3;

    private static final double CELL = 0.01;   // 격자 한 칸 (위도 약 1.1km)

    // 정류장
    private final double[] lat;
    private final double[] lng;
    private final String[] name;
    private final int[][] routesOfStop;
    // 노선 (운영사 + 노선 이름이 같으면 같은 노선)
    private final String[] routeName;
    private final String[] routeOperator;
    private final int[] routeType;
    private final int[][] stopsOfRoute;
    private final Map<Long, int[]> grid = new HashMap<>();
    private final String sourceName;

    /** 읽는 중에 정류장을 모으는 도구 */
    public static final class Builder {
        private final List<double[]> points = new ArrayList<>();
        private final List<String> names = new ArrayList<>();
        private final List<int[]> stopRoutes = new ArrayList<>();
        private final Map<String, Integer> routeIds = new HashMap<>();
        private final List<String> routeNames = new ArrayList<>();
        private final List<String> routeOperators = new ArrayList<>();
        private final List<Integer> routeTypes = new ArrayList<>();
        private final Map<String, String> interned = new HashMap<>();

        /**
         * @param routes 그 정류장에 서는 노선 이름들
         * @param types  노선마다의 버스 구분 코드 (모르면 0)
         */
        public void add(String stopName, String operator, double latitude, double longitude, List<String> routes, List<Integer> types) {
            if (stopName == null || operator == null || routes == null || routes.isEmpty()) return;
            if (!(latitude > 20 && latitude < 46 && longitude > 122 && longitude < 154)) return;   // 일본 밖 좌표는 버린다
            String op = intern(operator.trim());
            int[] ids = new int[routes.size()];
            int count = 0;
            for (int i = 0; i < routes.size(); i++) {
                String route = routes.get(i);
                if (route == null || route.isBlank() || route.trim().equals("-")) continue;
                String key = op + "|" + route.trim();
                Integer id = routeIds.get(key);
                if (id == null) {
                    id = routeNames.size();
                    routeIds.put(key, id);
                    routeNames.add(route.trim());
                    routeOperators.add(op);
                    routeTypes.add(types != null && i < types.size() && types.get(i) != null ? types.get(i) : 0);
                }
                ids[count++] = id;
            }
            if (count == 0) return;
            points.add(new double[]{latitude, longitude});
            names.add(stopName.trim());
            stopRoutes.add(count == ids.length ? ids : java.util.Arrays.copyOf(ids, count));
        }

        private String intern(String s) {
            return interned.computeIfAbsent(s, k -> k);
        }

        public int size() {
            return points.size();
        }

        public BusStopIndex build(String sourceName) {
            return new BusStopIndex(this, sourceName);
        }
    }

    private BusStopIndex(Builder b, String sourceName) {
        this.sourceName = sourceName;
        int n = b.points.size();
        lat = new double[n];
        lng = new double[n];
        name = b.names.toArray(new String[0]);
        routesOfStop = b.stopRoutes.toArray(new int[0][]);
        for (int i = 0; i < n; i++) {
            lat[i] = b.points.get(i)[0];
            lng[i] = b.points.get(i)[1];
        }
        routeName = b.routeNames.toArray(new String[0]);
        routeOperator = b.routeOperators.toArray(new String[0]);
        routeType = new int[routeName.length];
        for (int r = 0; r < routeType.length; r++) routeType[r] = b.routeTypes.get(r);

        int[] counts = new int[routeName.length];
        for (int[] rs : routesOfStop) for (int r : rs) counts[r]++;
        stopsOfRoute = new int[routeName.length][];
        for (int r = 0; r < counts.length; r++) stopsOfRoute[r] = new int[counts[r]];
        int[] fill = new int[routeName.length];
        for (int s = 0; s < n; s++) for (int r : routesOfStop[s]) stopsOfRoute[r][fill[r]++] = s;

        Map<Long, List<Integer>> cells = new HashMap<>();
        for (int s = 0; s < n; s++) cells.computeIfAbsent(cell(lat[s], lng[s]), k -> new ArrayList<>()).add(s);
        for (Map.Entry<Long, List<Integer>> e : cells.entrySet()) {
            grid.put(e.getKey(), e.getValue().stream().mapToInt(Integer::intValue).toArray());
        }
    }

    public int stopCount() { return lat.length; }
    public int routeCount() { return routeName.length; }
    public String sourceName() { return sourceName; }

    // =====================================================================
    // 경로 찾기
    // =====================================================================

    /** 버스 한 번 타기 */
    public static final class Ride {
        public final String operator;
        public final String line;
        public final int busType;
        public final String from;
        public final String to;
        public final double fromLat, fromLng, toLat, toLng;
        public final double km;        // 도로 기준 추정 거리
        public final int minutes;      // 타고 가는 시간 (기다림 제외)

        Ride(String operator, String line, int busType, String from, String to,
             double fromLat, double fromLng, double toLat, double toLng, double km, int minutes) {
            this.operator = operator;
            this.line = line;
            this.busType = busType;
            this.from = from;
            this.to = to;
            this.fromLat = fromLat;
            this.fromLng = fromLng;
            this.toLat = toLat;
            this.toLng = toLng;
            this.km = km;
            this.minutes = minutes;
        }
    }

    /** 찾은 경로: 걸어서 타러 가기 → 버스(1~2번, 사이에 걸어서 갈아타기) → 걸어서 도착 */
    public static final class Trip {
        public final List<Ride> rides = new ArrayList<>();
        public double walkInKm;
        public double transferKm;
        public double walkOutKm;
        public int minutes;

        public int walkInMinutes() { return walkMinutes(walkInKm); }
        public int transferMinutes() { return walkMinutes(transferKm); }
        public int walkOutMinutes() { return walkMinutes(walkOutKm); }
    }

    static int walkMinutes(double km) {
        return km <= 0 ? 0 : Math.max(1, (int) Math.ceil(km * RailGraph.WALK_MIN_PER_KM));
    }

    /**
     * 타고 가는 시간: 도로 거리 5km 까지는 시내 속도(시속 15km), 5~10km 는 시속 25km, 그 너머는 간선·우회도로 속도(시속 40km).
     * (공항 리무진처럼 먼 노선을 시내 속도로 계산하면 실제의 두세 배가 나온다)
     */
    static int rideMinutes(double straightKm) {
        double road = straightKm * ROAD_FACTOR;
        double minutes = Math.min(road, 5) / BUS_KMH * 60
                + Math.max(0, Math.min(road, 10) - 5) / 25.0 * 60
                + Math.max(0, road - 10) / 40.0 * 60;
        return Math.max(2, (int) Math.ceil(minutes));
    }

    /** 이 지점에서 가장 가까운 정류장 {위도, 경도, 거리(km)} 와 이름. 없으면 null */
    public Object[] nearestStop(double la, double ln, double radiusKm) {
        int best = -1;
        double bestKm = radiusKm;
        int span = (int) Math.ceil(radiusKm / (CELL * 90)) + 1;
        long cx = (long) Math.floor(la / CELL);
        long cy = (long) Math.floor(ln / CELL);
        for (long x = cx - span; x <= cx + span; x++) {
            for (long y = cy - span; y <= cy + span; y++) {
                int[] stops = grid.get(key(x, y));
                if (stops == null) continue;
                for (int s : stops) {
                    double d = DistanceUtil.calculateDistance(la, ln, lat[s], lng[s]);
                    if (d <= bestKm) {
                        bestKm = d;
                        best = s;
                    }
                }
            }
        }
        return best < 0 ? null : new Object[]{lat[best], lng[best], bestKm, name[best]};
    }

    /** 이 지점에서 radiusKm 안에 정류장이 있는가 (버스 자료가 있는 곳인가) */
    public boolean hasStopNear(double la, double ln, double radiusKm) {
        return !nearestPerRoute(la, ln, radiusKm).isEmpty();
    }

    /**
     * 두 지점을 잇는 버스 경로 (직행이 있으면 직행, 없으면 환승 1회). 없으면 null.
     * @param maxAccessKm 타는 곳·내리는 곳까지 걸을 수 있는 최대 거리
     */
    public Trip find(double fromLat, double fromLng, double toLat, double toLng, double maxAccessKm) {
        double straight = DistanceUtil.calculateDistance(fromLat, fromLng, toLat, toLng);
        Trip trip = find(fromLat, fromLng, toLat, toLng, Math.min(ACCESS_KM, Math.max(0.15, maxAccessKm)), straight);
        // 공항·큰 공원처럼 넓은 곳은 지도 좌표에서 정류장까지 멀 수 있다. 먼 구간이면 1km 까지 넓혀 다시 찾는다.
        if (trip == null && straight >= FAR_KM && maxAccessKm >= ACCESS_KM) {
            trip = find(fromLat, fromLng, toLat, toLng, WIDE_ACCESS_KM, straight);
        }
        return trip;
    }

    private Trip find(double fromLat, double fromLng, double toLat, double toLng, double access, double straight) {
        Map<Integer, double[]> origin = nearestPerRoute(fromLat, fromLng, access);   // 노선 → {정류장, 걷는 거리}
        Map<Integer, double[]> dest = nearestPerRoute(toLat, toLng, access);
        if (origin.isEmpty() || dest.isEmpty()) return null;

        Trip best = null;
        for (Map.Entry<Integer, double[]> o : origin.entrySet()) {
            double[] d = dest.get(o.getKey());
            if (d == null) continue;
            int a = (int) o.getValue()[0];
            int b = (int) d[0];
            if (a == b) continue;
            double rideStraight = dist(a, b);
            if (rideStraight < 0.3 || !sensible(straight, rideStraight)) continue;
            Trip trip = new Trip();
            trip.walkInKm = o.getValue()[1];
            trip.walkOutKm = d[1];
            trip.rides.add(ride(o.getKey(), a, b));
            score(trip);
            if (best == null || trip.minutes < best.minutes) best = trip;
        }
        if (best != null) return best;

        // 환승 1회: 출발 쪽 노선의 정류장과 도착 쪽 노선의 정류장이 가까이(걸어서 갈아탈 거리) 있는 곳
        List<Map.Entry<Integer, double[]>> outs = limit(origin);
        List<Map.Entry<Integer, double[]>> ins = limit(dest);
        for (Map.Entry<Integer, double[]> o : outs) {
            int a = (int) o.getValue()[0];
            for (Map.Entry<Integer, double[]> d : ins) {
                if (o.getKey().equals(d.getKey())) continue;
                int b = (int) d.getValue()[0];
                int[] pair = transferPoint(o.getKey(), a, d.getKey(), b);
                if (pair == null) continue;
                double first = dist(a, pair[0]);
                double second = dist(pair[1], b);
                if (first < 0.3 || second < 0.3 || !sensible(straight, first + second)) continue;
                Trip trip = new Trip();
                trip.walkInKm = o.getValue()[1];
                trip.transferKm = dist(pair[0], pair[1]);
                trip.walkOutKm = d.getValue()[1];
                trip.rides.add(ride(o.getKey(), a, pair[0]));
                trip.rides.add(ride(d.getKey(), pair[1], b));
                score(trip);
                if (best == null || trip.minutes < best.minutes) best = trip;
            }
        }
        return best;
    }

    /** 탄 거리가 직선거리보다 지나치게 길면(반대 방향·크게 도는 경로) 쓰지 않는다 */
    private static boolean sensible(double straight, double rideKm) {
        return rideKm <= straight * 1.6 + 0.5;
    }

    private void score(Trip trip) {
        int minutes = trip.walkInMinutes() + trip.walkOutMinutes() + trip.transferMinutes();
        for (Ride r : trip.rides) minutes += WAIT_MINUTES + r.minutes;
        trip.minutes = minutes;
    }

    private Ride ride(int route, int from, int to) {
        double straight = dist(from, to);
        return new Ride(routeOperator[route], routeName[route], routeType[route], name[from], name[to],
                lat[from], lng[from], lat[to], lng[to], Math.round(straight * ROAD_FACTOR * 10.0) / 10.0, rideMinutes(straight));
    }

    /**
     * 노선 r1(정류장 a 에서 탐)과 노선 r2(정류장 b 에서 내림)를 갈아탈 정류장 한 쌍.
     * 걸어서 옮길 수 있는 거리 안에서, 타고 가는 거리 합이 가장 짧은 쌍을 고른다.
     */
    private int[] transferPoint(int r1, int a, int r2, int b) {
        int[] best = null;
        double bestCost = Double.MAX_VALUE;
        for (int x : stopsOfRoute[r1]) {
            if (x == a) continue;
            for (int y : stopsOfRoute[r2]) {
                if (y == b) continue;
                if (Math.abs(lat[x] - lat[y]) > 0.003) continue;          // 빠른 걸러내기 (약 330m)
                double walk = dist(x, y);
                if (walk > TRANSFER_KM) continue;
                double cost = dist(a, x) + dist(y, b) + walk * 2;
                if (cost < bestCost) {
                    bestCost = cost;
                    best = new int[]{x, y};
                }
            }
        }
        return best;
    }

    /** 반경 안의 정류장에서, 노선마다 가장 가까운 정류장 {정류장 번호, 걷는 거리} */
    private Map<Integer, double[]> nearestPerRoute(double la, double ln, double radiusKm) {
        Map<Integer, double[]> out = new HashMap<>();
        int span = (int) Math.ceil(radiusKm / (CELL * 90)) + 1;   // 경도 1칸 ≈ 0.9km (일본 위도)
        long cx = (long) Math.floor(la / CELL);
        long cy = (long) Math.floor(ln / CELL);
        for (long x = cx - span; x <= cx + span; x++) {
            for (long y = cy - span; y <= cy + span; y++) {
                int[] stops = grid.get(key(x, y));
                if (stops == null) continue;
                for (int s : stops) {
                    double d = DistanceUtil.calculateDistance(la, ln, lat[s], lng[s]);
                    if (d > radiusKm) continue;
                    for (int r : routesOfStop[s]) {
                        double[] cur = out.get(r);
                        if (cur == null || d < cur[1]) out.put(r, new double[]{s, d});
                    }
                }
            }
        }
        return out;
    }

    private static List<Map.Entry<Integer, double[]>> limit(Map<Integer, double[]> byRoute) {
        List<Map.Entry<Integer, double[]>> list = new ArrayList<>(byRoute.entrySet());
        list.sort((p, q) -> Double.compare(p.getValue()[1], q.getValue()[1]));
        return list.size() > MAX_ROUTES_PER_SIDE ? list.subList(0, MAX_ROUTES_PER_SIDE) : list;
    }

    private double dist(int a, int b) {
        return DistanceUtil.calculateDistance(lat[a], lng[a], lat[b], lng[b]);
    }

    private static long cell(double la, double ln) {
        return key((long) Math.floor(la / CELL), (long) Math.floor(ln / CELL));
    }

    private static long key(long x, long y) {
        return x * 100_000L + y;
    }

    /** "しずてつジャストライン（株）" → "しずてつジャストライン" */
    public static String cleanOperator(String operator) {
        if (operator == null) return null;
        return operator.replace("（株）", "").replace("(株)", "").replace("㈱", "").replace("株式会社", "")
                .replace("（有）", "").replace("(有)", "").replace("（一社）", "").replace("（公財）", "").trim();
    }
}
