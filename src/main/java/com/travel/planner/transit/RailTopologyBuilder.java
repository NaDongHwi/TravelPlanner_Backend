package com.travel.planner.transit;

import com.travel.planner.transit.RailNetworkParser.RawNetwork;
import com.travel.planner.transit.RailNetworkParser.RawSection;
import com.travel.planner.transit.RailNetworkParser.RawStation;
import com.travel.planner.util.DistanceUtil;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;

/**
 * 선로 도형에서 "어느 역 다음이 어느 역이고 그 사이가 몇 km 인지"를 계산한다.
 *
 * 원본 데이터에는 역 순서가 없다. 역은 선로 위의 짧은 선(승강장 범위)으로, 선로는 여러 조각의 선으로만 들어 있다.
 * 그래서 노선(운영 회사 + 노선명)마다
 *   1) 선로 조각의 꼭짓점을 이어 그래프를 만들고
 *   2) 역이 놓인 꼭짓점을 찾아 표시한 뒤
 *   3) 각 역에서 선로를 따라가다 처음 만나는 다른 역을 "이웃 역"으로 기록한다 (거리는 선로를 따라 잰 길이).
 * 결과는 DB 와 무관한 순수 계산이라 그대로 테스트할 수 있다.
 */
public final class RailTopologyBuilder {

    private RailTopologyBuilder() {}

    /** 좌표를 이 자릿수(약 1m)로 반올림해 같은 점인지 본다 */
    private static final double COORD_SCALE = 1e5;
    /** 역의 점이 선로 꼭짓점과 정확히 겹치지 않을 때, 이 거리(km) 안의 가장 가까운 꼭짓점에 붙인다 */
    private static final double SNAP_KM = 0.15;

    public static class StationRecord {
        public int index;
        public String code;
        public String groupCode;
        public String name;
        public String operator;
        public String line;
        public String railClass;
        public String operatorType;
        public RailCategory category;
        public double latitude;
        public double longitude;
    }

    public static class LinkRecord {
        public int from;
        public int to;
        public double km;

        LinkRecord(int from, int to, double km) {
            this.from = from;
            this.to = to;
            this.km = km;
        }
    }

    public static class Result {
        public final List<StationRecord> stations = new ArrayList<>();
        public final List<LinkRecord> links = new ArrayList<>();
        /** 선로에 붙이지 못해 이웃 역을 찾지 못한 역 수 */
        public int unlinkedStations;
        public int lineCount;
    }

    private static class LineData {
        final List<RawSection> sections = new ArrayList<>();
        /** 역명 → 그 역의 원본 도형들 (큰 역은 승강장별로 여러 건이 들어 있다) */
        final Map<String, List<RawStation>> stations = new LinkedHashMap<>();
    }

    public static Result build(RawNetwork network) {
        Map<String, LineData> lines = new LinkedHashMap<>();
        for (RawSection s : network.sections) {
            lines.computeIfAbsent(key(s.operator, s.line), k -> new LineData()).sections.add(s);
        }
        for (RawStation s : network.stations) {
            lines.computeIfAbsent(key(s.operator, s.line), k -> new LineData())
                    .stations.computeIfAbsent(s.name, n -> new ArrayList<>()).add(s);
        }

        Result result = new Result();
        result.lineCount = lines.size();
        for (LineData line : lines.values()) buildLine(line, result);
        // 실행할 때마다 같은 순서가 되도록 정렬한다
        result.links.sort((a, b) -> a.from != b.from ? Integer.compare(a.from, b.from) : Integer.compare(a.to, b.to));
        return result;
    }

    private static String key(String operator, String line) {
        return (operator == null ? "" : operator) + "|" + (line == null ? "" : line);
    }

    private static void buildLine(LineData line, Result result) {
        if (line.stations.isEmpty()) return;

        // ---- 1) 선로 그래프 ----
        Map<Long, Integer> nodeByCoord = new HashMap<>();
        List<double[]> nodes = new ArrayList<>();
        List<List<double[]>> adjacency = new ArrayList<>();   // {이웃 노드, km}
        for (RawSection section : line.sections) {
            int previous = -1;
            for (double[] p : section.points) {
                int node = nodeByCoord.computeIfAbsent(coordKey(p), k -> {
                    nodes.add(p);
                    adjacency.add(new ArrayList<>());
                    return nodes.size() - 1;
                });
                if (previous >= 0 && previous != node) {
                    double km = DistanceUtil.calculateDistance(nodes.get(previous)[0], nodes.get(previous)[1], p[0], p[1]);
                    adjacency.get(previous).add(new double[]{node, km});
                    adjacency.get(node).add(new double[]{previous, km});
                }
                previous = node;
            }
        }

        // ---- 2) 역 등록 + 역이 놓인 꼭짓점 찾기 ----
        int firstIndex = result.stations.size();
        int[] owner = new int[nodes.size()];           // 꼭짓점 → 그 자리의 역(전체 목록에서의 번호), 없으면 -1
        Arrays.fill(owner, -1);
        List<List<Integer>> stationNodes = new ArrayList<>();
        List<Double> halfLength = new ArrayList<>();

        for (List<RawStation> group : line.stations.values()) {
            RawStation first = group.get(0);
            StationRecord record = new StationRecord();
            record.index = result.stations.size();
            record.name = first.name;
            record.operator = first.operator;
            record.line = first.line;
            record.railClass = first.railClass;
            record.operatorType = first.operatorType;
            record.category = RailFareModel.categoryOf(first.railClass, first.operatorType, first.operator);

            double latSum = 0;
            double lngSum = 0;
            int count = 0;
            double longest = 0;
            List<Integer> mine = new ArrayList<>();
            for (RawStation raw : group) {
                if (record.code == null) record.code = raw.code;
                if (record.groupCode == null) record.groupCode = raw.groupCode;
                double length = 0;
                for (int i = 0; i < raw.points.size(); i++) {
                    double[] p = raw.points.get(i);
                    latSum += p[0];
                    lngSum += p[1];
                    count++;
                    if (i > 0) length += DistanceUtil.calculateDistance(raw.points.get(i - 1)[0], raw.points.get(i - 1)[1], p[0], p[1]);
                    Integer node = nodeByCoord.get(coordKey(p));
                    if (node != null) mine.add(node);
                }
                longest = Math.max(longest, length);
            }
            record.latitude = latSum / count;
            record.longitude = lngSum / count;

            if (mine.isEmpty()) {
                // 역의 점이 선로 꼭짓점과 겹치지 않는 경우: 가장 가까운 꼭짓점에 붙인다
                int nearest = -1;
                double best = SNAP_KM;
                for (int n = 0; n < nodes.size(); n++) {
                    double km = DistanceUtil.calculateDistance(record.latitude, record.longitude, nodes.get(n)[0], nodes.get(n)[1]);
                    if (km < best) {
                        best = km;
                        nearest = n;
                    }
                }
                if (nearest >= 0) mine.add(nearest);
            }
            for (int node : mine) {
                if (owner[node] < 0) owner[node] = record.index;
            }
            stationNodes.add(mine);
            halfLength.add(longest / 2.0);
            result.stations.add(record);
        }

        // ---- 3) 각 역에서 선로를 따라 처음 만나는 다른 역 찾기 ----
        Map<Long, Double> best = new HashMap<>();
        double[] dist = new double[nodes.size()];
        int stationCount = result.stations.size() - firstIndex;
        for (int s = 0; s < stationCount; s++) {
            int self = firstIndex + s;
            List<Integer> mine = stationNodes.get(s);
            if (mine.isEmpty()) {
                result.unlinkedStations++;
                continue;
            }
            Arrays.fill(dist, Double.MAX_VALUE);
            PriorityQueue<double[]> queue = new PriorityQueue<>((a, b) -> Double.compare(a[0], b[0]));
            for (int node : mine) {
                if (owner[node] != self) continue;        // 다른 역과 같은 꼭짓점을 쓰는 드문 경우
                dist[node] = 0;
                queue.add(new double[]{0, node});
            }
            while (!queue.isEmpty()) {
                double[] top = queue.poll();
                int u = (int) top[1];
                if (top[0] > dist[u]) continue;
                int other = owner[u];
                if (other >= 0 && other != self) {
                    // 이웃 역에 닿았다: 기록하고 그 너머로는 가지 않는다
                    double km = top[0] + halfLength.get(s) + halfLength.get(other - firstIndex);
                    long pair = pairKey(Math.min(self, other), Math.max(self, other));
                    best.merge(pair, km, Math::min);
                    continue;
                }
                for (double[] edge : adjacency.get(u)) {
                    int v = (int) edge[0];
                    double next = top[0] + edge[1];
                    if (next < dist[v]) {
                        dist[v] = next;
                        queue.add(new double[]{next, v});
                    }
                }
            }
        }
        List<LinkRecord> lineLinks = new ArrayList<>();
        for (Map.Entry<Long, Double> e : best.entrySet()) {
            int a = (int) (e.getKey() >> 32);
            int b = (int) (e.getKey() & 0xFFFFFFFFL);
            lineLinks.add(new LinkRecord(a, b, Math.round(e.getValue() * 1000.0) / 1000.0));
        }
        result.links.addAll(dropBypassLinks(lineLinks));
    }

    /**
     * 역을 지나지 않는 우회 선로(화물선·통과선) 때문에 생긴 "한 역을 건너뛰는" 연결을 지운다.
     * 예: 야마노테선 신주쿠–하라주쿠 직결(2.03km)은 신주쿠–요요기–하라주쿠(2.27km)와 사실상 같은 길이다.
     * 어떤 연결을 빼도 다른 역들을 거쳐 거의 같은 거리(1.25배 + 0.3km 이내)로 갈 수 있으면 그 연결은 건너뛰기로 본다.
     * 긴 연결부터 검사하고, 지운 연결은 다음 검사에서 쓰지 않는다.
     */
    private static List<LinkRecord> dropBypassLinks(List<LinkRecord> links) {
        if (links.size() < 3) return links;
        List<LinkRecord> ordered = new ArrayList<>(links);
        ordered.sort((a, b) -> Double.compare(b.km, a.km));
        List<LinkRecord> kept = new ArrayList<>(links);
        for (LinkRecord candidate : ordered) {
            double limit = candidate.km * 1.25 + 0.3;
            if (viaOtherStations(kept, candidate, limit)) kept.remove(candidate);
        }
        return kept;
    }

    /** candidate 를 쓰지 않고, 두 개 이상의 연결을 거쳐 limit 이내로 갈 수 있는가 */
    private static boolean viaOtherStations(List<LinkRecord> links, LinkRecord candidate, double limit) {
        Map<Integer, List<LinkRecord>> byStation = new HashMap<>();
        for (LinkRecord l : links) {
            if (l == candidate) continue;
            byStation.computeIfAbsent(l.from, k -> new ArrayList<>()).add(l);
            byStation.computeIfAbsent(l.to, k -> new ArrayList<>()).add(l);
        }
        Map<Integer, Double> dist = new HashMap<>();
        PriorityQueue<double[]> queue = new PriorityQueue<>((a, b) -> Double.compare(a[0], b[0]));
        dist.put(candidate.from, 0.0);
        queue.add(new double[]{0, candidate.from});
        while (!queue.isEmpty()) {
            double[] top = queue.poll();
            int u = (int) top[1];
            if (top[0] > dist.getOrDefault(u, Double.MAX_VALUE)) continue;
            if (u == candidate.to) return true;
            for (LinkRecord l : byStation.getOrDefault(u, List.of())) {
                int v = l.from == u ? l.to : l.from;
                double next = top[0] + l.km;
                if (next > limit || next >= dist.getOrDefault(v, Double.MAX_VALUE)) continue;
                dist.put(v, next);
                queue.add(new double[]{next, v});
            }
        }
        return false;
    }

    private static long coordKey(double[] p) {
        return Math.round(p[0] * COORD_SCALE) * 100_000_000L + Math.round(p[1] * COORD_SCALE);
    }

    private static long pairKey(int a, int b) {
        return ((long) a << 32) | (b & 0xFFFFFFFFL);
    }
}
