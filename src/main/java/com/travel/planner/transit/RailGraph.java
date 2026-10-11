package com.travel.planner.transit;

import com.travel.planner.util.DistanceUtil;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;

/**
 * 메모리에 올린 전국 철도망과 경로 탐색.
 *
 * 점(노드)은 "노선별 역"이다. 같은 역이라도 노선이 다르면 다른 점이고, 그 사이는 환승 간선으로 잇는다.
 *   - 승차 간선: 같은 노선의 이웃 역 (시간 = 거리 ÷ 종류별 평균 속도 + 정차 시간)
 *   - 환승 간선: 같은 역 그룹이거나, 서로 가까운(걸어서 갈 수 있는) 다른 노선의 역
 * 출발지·도착지에서 걸어갈 수 있는 역들을 모두 후보로 두고, 걷는 시간까지 포함해 가장 빨리 도착하는 경로를 찾는다(다익스트라).
 * 열차 시각표가 아니라 평균 속도로 계산하므로 결과는 추정값이다.
 */
public final class RailGraph {

    /** 출발지·도착지에서 역까지 걸어가는 최대 직선거리(km) */
    public static final double ACCESS_KM = 1.5;
    /** 이름이 달라도 이 거리(km) 안의 다른 노선 역은 걸어서 환승할 수 있다고 본다 (예: 오사카역 ↔ 우메다역) */
    public static final double WALK_TRANSFER_KM = 0.4;
    /** 환승할 때 승강장을 옮기고 다음 열차를 기다리는 시간(분) */
    public static final int TRANSFER_MINUTES = 5;
    /** 걷는 속도: 직선거리 1km 당 분 (엔진의 도보 계산과 같은 값) */
    public static final double WALK_MIN_PER_KM = 17.3;
    /** 가까이에 역이 없을 때 한 번 더 넓혀 찾는 거리(km). 공항처럼 넓은 곳은 좌표가 역에서 꽤 떨어져 있다 */
    public static final double WIDE_ACCESS_KM = 3.0;
    /**
     * 경로를 고를 때만 쓰는 가중치(분). 실제 소요 시간에는 더하지 않는다.
     *  - 신칸센은 요금이 훨씬 비싸므로 이만큼 이상 빨라질 때만 탄다 (오사카→교토 같은 짧은 구간은 재래선)
     *  - 환승은 번거로우므로 조금이라도 덜 갈아타는 쪽을 고른다
     */
    static final double SHINKANSEN_PENALTY = 25;
    static final double TRANSFER_PENALTY = 3;

    private static final double CELL = 0.01;       // 격자 한 칸 (위도 약 1.1km)
    private static final int MAX_ACCESS_STATIONS = 16;

    public static final class Station {
        public final int index;
        public final String name;
        public final String operator;
        public final String line;
        public final String groupCode;
        public final RailCategory category;
        public final double latitude;
        public final double longitude;

        public Station(int index, String name, String operator, String line, String groupCode, RailCategory category,
                       double latitude, double longitude) {
            this.index = index;
            this.name = name;
            this.operator = operator;
            this.line = line;
            this.groupCode = groupCode;
            this.category = category;
            this.latitude = latitude;
            this.longitude = longitude;
        }

        String lineKey() {
            return operator + "|" + line;
        }
    }

    private static final class Edge {
        final int to;
        final double km;
        final double minutes;
        /** 탐색에 쓰는 값 = 실제 시간 + 가중치 */
        final double cost;
        final boolean transfer;

        Edge(int to, double km, double minutes, double cost, boolean transfer) {
            this.to = to;
            this.km = km;
            this.minutes = minutes;
            this.cost = cost;
            this.transfer = transfer;
        }
    }

    /** 경로의 한 단계 */
    public static final class Step {
        public enum Kind { WALK, RIDE, TRANSFER }

        public Kind kind;
        public RailCategory category;   // RIDE 일 때
        public String operator;         // RIDE 일 때
        public String line;             // RIDE 일 때
        public String from;             // 역 이름 (WALK 의 한쪽 끝은 null: 출발지·도착지)
        public String to;
        public int minutes;
        public double km;
        /** RIDE: 타고 가는 정거장 수 */
        public int stops;
    }

    public static final class Route {
        public int minutes;
        public int fareYen;
        public int childFareYen;
        public double railKm;
        /** 출발지→첫 역, 마지막 역→도착지를 걷는 직선거리 합(km) */
        public double walkKm;
        public int transfers;
        public final List<Step> steps = new ArrayList<>();
    }

    private final Station[] stations;
    private final List<List<Edge>> edges = new ArrayList<>();
    private final Map<Long, List<Integer>> grid = new HashMap<>();
    private int rideLinks;
    private int transferLinks;

    /**
     * @param links {역 번호 a, 역 번호 b, km} — 같은 노선의 이웃 역
     */
    public RailGraph(List<Station> stationList, List<double[]> links) {
        stations = stationList.toArray(new Station[0]);
        for (int i = 0; i < stations.length; i++) {
            if (stations[i].index != i) throw new IllegalArgumentException("역 번호는 0부터 순서대로여야 합니다.");
            edges.add(new ArrayList<>());
            grid.computeIfAbsent(cell(stations[i].latitude, stations[i].longitude), k -> new ArrayList<>()).add(i);
        }

        for (double[] link : links) {
            int a = (int) link[0];
            int b = (int) link[1];
            if (a < 0 || b < 0 || a >= stations.length || b >= stations.length || a == b) continue;
            double km = link[2];
            double minutes = RailFareModel.rideMinutes(stations[a].category, km);
            edges.get(a).add(new Edge(b, km, minutes, minutes, false));
            edges.get(b).add(new Edge(a, km, minutes, minutes, false));
            rideLinks++;
        }

        // 환승: 같은 그룹 코드, 또는 가까이 있는 다른 노선의 역
        for (int i = 0; i < stations.length; i++) {
            Station a = stations[i];
            for (int j : near(a.latitude, a.longitude, 0.8)) {
                if (j <= i) continue;
                Station b = stations[j];
                if (a.lineKey().equals(b.lineKey())) continue;
                double km = DistanceUtil.calculateDistance(a.latitude, a.longitude, b.latitude, b.longitude);
                boolean sameGroup = a.groupCode != null && !a.groupCode.isBlank() && a.groupCode.equals(b.groupCode);
                boolean sameName = a.name != null && a.name.equals(b.name) && km <= 0.8;
                if (!sameGroup && !sameName && km > WALK_TRANSFER_KM) continue;
                double walk = km * WALK_MIN_PER_KM;
                double toB = walk + transferWait(a, b);
                double toA = walk + transferWait(b, a);
                edges.get(i).add(new Edge(j, km, toB, toB + TRANSFER_PENALTY + boardingPenalty(b.category), true));
                edges.get(j).add(new Edge(i, km, toA, toA + TRANSFER_PENALTY + boardingPenalty(a.category), true));
                transferLinks++;
            }
        }
    }

    /** 파일에서 계산한 결과로 바로 그래프를 만든다 (DB 를 거치지 않는 검증·테스트용) */
    public static RailGraph of(RailTopologyBuilder.Result topology) {
        List<Station> list = new ArrayList<>();
        for (RailTopologyBuilder.StationRecord r : topology.stations) {
            list.add(new Station(r.index, r.name, r.operator, r.line, r.groupCode, r.category, r.latitude, r.longitude));
        }
        List<double[]> links = new ArrayList<>();
        for (RailTopologyBuilder.LinkRecord l : topology.links) links.add(new double[]{l.from, l.to, l.km});
        return new RailGraph(list, links);
    }

    /** 갈아탈 때 걸리는 시간(걷는 시간 제외). 같은 회사의 JR 노선끼리는 승강장만 옮기면 되므로 짧게 잡는다. */
    private static double transferWait(Station from, Station to) {
        if (to.category == RailCategory.SHINKANSEN) return RailFareModel.boardingMinutes(to.category);
        boolean sameJr = from.category == RailCategory.JR && to.category == RailCategory.JR && from.operator.equals(to.operator);
        return sameJr ? 3 : TRANSFER_MINUTES;
    }

    private static double boardingPenalty(RailCategory boarding) {
        return boarding == RailCategory.SHINKANSEN ? SHINKANSEN_PENALTY : 0;
    }

    public int stationCount() { return stations.length; }
    public int rideLinkCount() { return rideLinks; }
    public int transferLinkCount() { return transferLinks; }
    public Station station(int index) { return stations[index]; }

    /** 그 지점에서 가장 가까운 역과 거리(km). 역이 하나도 없으면 null */
    public Station nearestStation(double lat, double lng, double withinKm) {
        Station best = null;
        double bestKm = withinKm;
        for (int i : near(lat, lng, withinKm)) {
            double km = DistanceUtil.calculateDistance(lat, lng, stations[i].latitude, stations[i].longitude);
            if (km <= bestKm) {
                bestKm = km;
                best = stations[i];
            }
        }
        return best;
    }

    // =====================================================================
    // 경로 탐색
    // =====================================================================

    /**
     * 두 지점 사이의 가장 빠른 철도 경로. 걸어갈 수 있는 역이 한쪽이라도 없거나 이어지는 경로가 없으면 null.
     */
    public Route route(double fromLat, double fromLng, double toLat, double toLng) {
        Map<Integer, Double> access = accessStations(fromLat, fromLng);
        Map<Integer, Double> egress = accessStations(toLat, toLng);
        if (access.isEmpty() || egress.isEmpty()) return null;

        double[] dist = new double[stations.length];
        int[] previous = new int[stations.length];
        Edge[] via = new Edge[stations.length];
        Arrays.fill(dist, Double.MAX_VALUE);
        Arrays.fill(previous, -1);

        PriorityQueue<double[]> queue = new PriorityQueue<>((a, b) -> Double.compare(a[0], b[0]));
        for (Map.Entry<Integer, Double> e : access.entrySet()) {
            int s = e.getKey();
            double start = e.getValue() * WALK_MIN_PER_KM + RailFareModel.boardingMinutes(stations[s].category)
                    + boardingPenalty(stations[s].category);
            if (start < dist[s]) {
                dist[s] = start;
                queue.add(new double[]{start, s});
            }
        }

        double bestTotal = Double.MAX_VALUE;
        int bestEnd = -1;
        while (!queue.isEmpty()) {
            double[] top = queue.poll();
            int u = (int) top[1];
            if (top[0] > dist[u]) continue;
            if (top[0] >= bestTotal) break;                 // 더 빠른 도착은 나올 수 없다
            Double walkOut = egress.get(u);
            // 열차를 한 번도 타지 않고 "역까지 걸어갔다가 다시 걸어 나오는" 것은 경로가 아니다
            if (walkOut != null && previous[u] >= 0 && riddenBefore(u, previous, via)) {
                double total = top[0] + walkOut * WALK_MIN_PER_KM;
                if (total < bestTotal) {
                    bestTotal = total;
                    bestEnd = u;
                }
            }
            for (Edge edge : edges.get(u)) {
                double next = top[0] + edge.cost;
                if (next < dist[edge.to]) {
                    dist[edge.to] = next;
                    previous[edge.to] = u;
                    via[edge.to] = edge;
                    queue.add(new double[]{next, edge.to});
                }
            }
        }
        if (bestEnd < 0) return null;

        // 경로 복원
        List<Integer> path = new ArrayList<>();
        for (int at = bestEnd; at >= 0; at = previous[at]) path.add(0, at);
        return describe(path, via, fromLat, fromLng, toLat, toLng);
    }

    private static boolean riddenBefore(int node, int[] previous, Edge[] via) {
        for (int at = node; previous[at] >= 0; at = previous[at]) {
            if (!via[at].transfer) return true;
        }
        return false;
    }

    /** 걸어갈 수 있는 역 → 직선거리(km). 가까운 순으로, 노선이 겹치지 않게 고른다. */
    private Map<Integer, Double> accessStations(double lat, double lng) {
        List<double[]> found = within(lat, lng, ACCESS_KM);
        if (found.isEmpty()) found = within(lat, lng, WIDE_ACCESS_KM);
        Map<Integer, Double> result = new HashMap<>();
        Map<String, Integer> perLine = new HashMap<>();
        for (double[] f : found) {
            Station s = stations[(int) f[1]];
            // 같은 노선에서는 가장 가까운 두 역까지만 (양쪽 방향)
            if (perLine.merge(s.lineKey(), 1, Integer::sum) > 2) continue;
            result.put(s.index, f[0]);
            if (result.size() >= MAX_ACCESS_STATIONS) break;
        }
        return result;
    }

    private List<double[]> within(double lat, double lng, double radiusKm) {
        List<double[]> found = new ArrayList<>();
        for (int i : near(lat, lng, radiusKm)) {
            double km = DistanceUtil.calculateDistance(lat, lng, stations[i].latitude, stations[i].longitude);
            if (km <= radiusKm) found.add(new double[]{km, i});
        }
        found.sort(Comparator.comparingDouble(a -> a[0]));
        return found;
    }

    /** 역 번호의 나열을 "걷기 → 승차 → 환승 → 승차 → 걷기" 단계로 정리하고 요금을 계산한다. */
    private Route describe(List<Integer> path, Edge[] via, double fromLat, double fromLng, double toLat, double toLng) {
        // 앞뒤의 "타지 않고 환승만 한" 부분은 잘라낸다: 실제로는 처음 타는 역까지 바로 걸어간 것이다
        int first = 0;
        while (first + 1 < path.size() && via[path.get(first + 1)].transfer) first++;
        int last = path.size() - 1;
        while (last > first && via[path.get(last)].transfer) last--;

        Route route = new Route();
        Station board = stations[path.get(first)];
        Station alight = stations[path.get(last)];

        Step walkIn = new Step();
        walkIn.kind = Step.Kind.WALK;
        walkIn.to = board.name;
        walkIn.km = DistanceUtil.calculateDistance(fromLat, fromLng, board.latitude, board.longitude);
        walkIn.minutes = (int) Math.ceil(walkIn.km * WALK_MIN_PER_KM) + RailFareModel.boardingMinutes(board.category);
        route.steps.add(walkIn);

        Step ride = null;
        double exactMinutes = 0;
        for (int i = first + 1; i <= last; i++) {
            int node = path.get(i);
            Edge edge = via[node];
            Station from = stations[path.get(i - 1)];
            Station to = stations[node];
            if (edge.transfer) {
                if (ride != null) {
                    ride.minutes = (int) Math.ceil(exactMinutes);
                    ride = null;
                }
                Step transfer = new Step();
                transfer.kind = Step.Kind.TRANSFER;
                transfer.from = from.name;
                transfer.to = to.name;
                transfer.km = edge.km;
                transfer.minutes = (int) Math.ceil(edge.minutes);
                route.steps.add(transfer);
                route.transfers++;
            } else {
                if (ride == null) {
                    ride = new Step();
                    ride.kind = Step.Kind.RIDE;
                    ride.category = from.category;
                    ride.operator = from.operator;
                    ride.line = from.line;
                    ride.from = from.name;
                    exactMinutes = 0;
                    route.steps.add(ride);
                }
                ride.to = to.name;
                ride.km += edge.km;
                ride.stops++;
                exactMinutes += edge.minutes;
                route.railKm += edge.km;
            }
        }
        if (ride != null) ride.minutes = (int) Math.ceil(exactMinutes);

        Step walkOut = new Step();
        walkOut.kind = Step.Kind.WALK;
        walkOut.from = alight.name;
        walkOut.km = DistanceUtil.calculateDistance(alight.latitude, alight.longitude, toLat, toLng);
        walkOut.minutes = (int) Math.ceil(walkOut.km * WALK_MIN_PER_KM);
        route.steps.add(walkOut);

        for (Step step : route.steps) route.minutes += step.minutes;
        route.walkKm = walkIn.km + walkOut.km;
        route.railKm = Math.round(route.railKm * 10.0) / 10.0;
        route.fareYen = fareOf(route.steps);
        route.childFareYen = RailFareModel.childFare(route.fareYen);
        return route;
    }

    /**
     * 요금: 이어서 탄 같은 요금 단위(JR 전체, 또는 한 운영사)끼리 거리를 합쳐 한 번만 낸다.
     * 중간에 다른 회사를 타면 요금이 끊긴다. 신칸센을 탄 구간에는 특급요금이 붙는다.
     */
    static int fareOf(List<Step> steps) {
        int total = 0;
        String group = null;
        String operator = null;
        RailCategory category = null;
        double km = 0;
        double shinkansenKm = 0;
        for (Step step : steps) {
            if (step.kind != Step.Kind.RIDE) continue;
            String g = RailFareModel.fareGroup(step.category, step.operator);
            if (group != null && !group.equals(g)) {
                total += blockFare(category, operator, km, shinkansenKm);
                km = 0;
                shinkansenKm = 0;
            }
            group = g;
            operator = step.operator;
            // JR 재래선과 신칸센이 섞인 구간은 JR 로 계산한다
            category = step.category == RailCategory.SHINKANSEN ? RailCategory.JR : step.category;
            km += step.km;
            if (step.category == RailCategory.SHINKANSEN) shinkansenKm += step.km;
        }
        if (group != null) total += blockFare(category, operator, km, shinkansenKm);
        return total;
    }

    private static int blockFare(RailCategory category, String operator, double km, double shinkansenKm) {
        return RailFareModel.fare(category, operator, km) + RailFareModel.shinkansenSurcharge(shinkansenKm);
    }

    // =====================================================================
    // 격자 검색
    // =====================================================================

    private static long cell(double lat, double lng) {
        return (long) Math.floor(lat / CELL) * 100_000L + (long) Math.floor(lng / CELL);
    }

    /** 그 지점 주변 격자에 있는 역 번호들 (정확한 거리는 호출한 쪽에서 다시 잰다) */
    private List<Integer> near(double lat, double lng, double km) {
        int span = (int) Math.ceil(km / 0.9) + 1;        // 경도 0.01도 ≈ 0.9km (일본 위도 기준)
        List<Integer> result = new ArrayList<>();
        long baseLat = (long) Math.floor(lat / CELL);
        long baseLng = (long) Math.floor(lng / CELL);
        for (long dy = -span; dy <= span; dy++) {
            for (long dx = -span; dx <= span; dx++) {
                List<Integer> bucket = grid.get((baseLat + dy) * 100_000L + (baseLng + dx));
                if (bucket != null) result.addAll(bucket);
            }
        }
        return result;
    }
}
