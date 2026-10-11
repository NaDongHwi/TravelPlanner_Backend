package com.travel.planner.transit;

import com.travel.planner.util.DistanceUtil;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;

/**
 * 메모리에 올린 교통망(전국 철도망 + GTFS 로 가져온 버스·노면전차·배)과 경로 탐색.
 *
 * 점(노드)은 세 가지다.
 *   - 노선별 역: 국토수치정보 철도망. 같은 역이라도 노선이 다르면 다른 점이고, 그 사이는 환승 간선으로 잇는다.
 *   - 정류장: GTFS 의 승강장(폴) 하나.
 *   - 노선 패턴의 한 자리: "42번 버스의 7번째 정류장". 정류장에서 여기로 올라타고(대기 시간), 다음 자리로 타고 가고, 정류장으로 내린다.
 * 간선의 시간
 *   - 철도 승차: 거리 ÷ 종류별 평균 속도 + 정차 시간 (추정)
 *   - 버스 승차: GTFS 시각표의 구간 평균 소요 시간
 *   - 버스 대기: 그 요일의 운행 간격의 절반 (최대 15분)
 *   - 환승·도보: 직선거리 × 걷는 속도
 * 출발지·도착지에서 걸어갈 수 있는 역·정류장을 모두 후보로 두고, 걷는 시간까지 포함해 가장 빨리 도착하는 경로를 찾는다(다익스트라).
 * 개별 열차·버스의 출발 시각은 쓰지 않으므로 소요 시간은 추정값이다. 요금은 버스는 GTFS 요금표(있을 때), 철도는 거리 기준 요금표로 계산한다.
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
    /** 출발지·도착지에서 정류장까지 걸어가는 최대 직선거리(km) */
    public static final double STOP_ACCESS_KM = 1.0;
    /** 가까이에 정류장이 없을 때 한 번 더 넓혀 찾는 거리(km) */
    public static final double WIDE_STOP_ACCESS_KM = 2.0;
    /** 이 거리(km) 안의 다른 정류장으로는 걸어서 갈아탈 수 있다 (길 건너편 승강장, 터미널의 다른 승강장) */
    public static final double STOP_TRANSFER_KM = 0.3;
    /** 버스를 기다리는 시간의 상한(분). 간격이 길면 시간표를 보고 맞춰 나간다고 본다 */
    public static final double MAX_WAIT_MINUTES = 15;
    /**
     * 경로를 고를 때만 쓰는 가중치(분). 실제 소요 시간에는 더하지 않는다.
     *  - 신칸센은 요금이 훨씬 비싸므로 이만큼 이상 빨라질 때만 탄다 (오사카→교토 같은 짧은 구간은 재래선)
     *  - 환승은 번거로우므로 조금이라도 덜 갈아타는 쪽을 고른다
     *  - 운행 간격이 긴 노선은 놓치면 오래 기다려야 하므로 덜 고른다 (간격 ÷ 3, 최대 40분)
     */
    static final double SHINKANSEN_PENALTY = 25;
    static final double TRANSFER_PENALTY = 3;
    static final double MAX_FREQUENCY_PENALTY = 40;

    /**
     * GTFS 노선을 탐색하는 범위: 출발지까지의 거리 + 도착지까지의 거리가 "직선거리 × 3 + 20km" 를 넘는 정류장으로는 가지 않는다.
     * 그렇게 멀리 돌아가는 버스 경로는 쓸모가 없고, 이 범위가 없으면 가까운 구간을 찾을 때도 주변 도시의 노선을 모두 훑게 된다.
     */
    static final double DETOUR_FACTOR = 3.0;
    static final double DETOUR_SLACK_KM = 20.0;

    private static final double CELL = 0.01;       // 격자 한 칸 (위도 약 1.1km)
    private static final int MAX_ACCESS_STATIONS = 16;
    private static final int MAX_ACCESS_STOPS = 24;
    private static final int MAX_STOP_LINKS = 12;

    private static final byte RAIL_RIDE = 1;
    private static final byte RAIL_TRANSFER = 2;
    private static final byte LINK = 3;        // 역 ↔ 정류장, 정류장 ↔ 정류장을 걷는다
    private static final byte BOARD = 4;       // 정류장에서 노선에 올라탄다 (대기)
    private static final byte LINE_RIDE = 5;   // GTFS 노선을 타고 다음 정류장으로
    private static final byte ALIGHT = 6;

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
        /** WAIT: 처음 타기 전에 기다리는 시간 (철도는 개찰·승강장 이동 포함). 갈아탈 때의 대기는 TRANSFER 에 들어 있다 */
        public enum Kind { WALK, WAIT, RIDE, TRANSFER }

        public Kind kind;
        public RailCategory category;   // RIDE 일 때
        public String operator;         // RIDE 일 때
        public String line;             // RIDE 일 때
        public String from;             // 역·정류장 이름 원문 (WALK 의 한쪽 끝은 null: 출발지·도착지)
        public String to;
        public int minutes;
        public double km;
        /** RIDE: 타고 가는 정거장 수 */
        public int stops;

        /** from / to 가 GTFS 정류장인가 (철도역이 아니다) */
        public boolean fromIsStop;
        public boolean toIsStop;
        /** GTFS 정류장의 한국어 표기 (없으면 null) */
        public String fromKo;
        public String toKo;

        // ---- GTFS 노선을 탄 단계에만 채워진다 ----
        /** GTFS 에서 가져온 노선인가 (요금·횟수가 시각표 자료 기준) */
        public boolean scheduled;
        public String lineLongName;
        public String headsign;
        /** 이 구간의 요금. 요금표에 없으면 거리 기준 추정값이고 fareConfirmed = false */
        public int fareYen;
        public boolean fareConfirmed;
        /** 타는 정류장에서 내리는 정류장까지 바로 가는 편의 하루 운행 횟수 (같은 구간을 가는 다른 노선 포함) */
        public int tripsPerDay;
        /** 평균 운행 간격(분) */
        public int headwayMinutes;
        /** 타는 정류장 기준 첫차·막차 시각 (0시부터의 분) */
        public int firstDeparture;
        public int lastDeparture;
        /** 같은 구간을 바로 가는 다른 노선 이름 */
        public List<String> alternatives;
        /** 매일 다니지 않는 노선 */
        public boolean irregular;
        /** 유효 기간이 지난 시각표에서 나온 결과 */
        public boolean outdated;
        /** 출발 시각이 첫차 전이거나 막차 뒤 */
        public boolean outsideServiceHours;

        // ---- RIDE 단계의 위치와 요금 근거 ----
        /** 타는 곳·내리는 곳 좌표 (RIDE 단계에만. 요금표의 적용 범위와 패스의 이용 범위를 가릴 때 쓴다) */
        public boolean hasCoordinates;
        public double fromLat;
        public double fromLng;
        public double toLat;
        public double toLng;
        /** 이 승차가 속한 요금 묶음 (Route.fareBlocks 의 순번). RIDE 가 아니면 -1 */
        public int fareBlock = -1;
    }

    /**
     * 요금을 한 번 내는 단위.
     * 철도망 구간은 이어서 탄 같은 요금 단위(JR 전체, 또는 한 운영사)끼리 한 묶음이고, GTFS 노선은 탈 때마다 한 묶음이다.
     */
    public static final class FareBlock {
        /** 요금 단위 이름: JR 은 "JR", 그 외에는 운영사 이름 원문 */
        public String group;
        /** GTFS 노선을 탄 묶음인가 */
        public boolean scheduled;
        public final List<Step> rides = new ArrayList<>();
        /** 성인 요금 (신칸센 특급요금 포함) */
        public int fareYen;
        public int childFareYen;
        /** 그중 신칸센 자유석 특급요금 */
        public int surchargeYen;
        public FareBook.Basis basis;
        /** 쓴 요금표의 키 (없으면 null) */
        public String tableKey;
        /** 요금을 잰 거리(km) */
        public double fareKm;
    }

    public static final class Route {
        public int minutes;
        public int fareYen;
        public int childFareYen;
        public double railKm;
        /** 출발지→첫 역·정류장, 마지막 역·정류장→도착지를 걷는 직선거리 합(km) */
        public double walkKm;
        public int transfers;
        /** 요금표로 확인한 승차 수 / 거리로 추정한 승차 수 */
        public int confirmedFareRides;
        public int estimatedFareRides;
        /** 운영사 요금표에 계산한 거리를 넣어 구한 승차 수 (추정한 승차 수에 포함된다) */
        public int tableFareRides;
        public final List<Step> steps = new ArrayList<>();
        /** 요금을 낸 단위들. 요금의 합이 fareYen 이다 */
        public final List<FareBlock> fareBlocks = new ArrayList<>();
    }

    /** 등록된 요금 자료. 교통망과 따로 바뀔 수 있어 나중에 갈아 끼운다 (없으면 종류별 대표 요금표로만 추정) */
    private volatile FareBook fareBook = FareBook.EMPTY;

    private final Station[] stations;
    private final List<List<Edge>> edges = new ArrayList<>();
    private final Map<Long, List<Integer>> grid = new HashMap<>();
    private int rideLinks;
    private int transferLinks;
    /** GTFS 노선과 겹쳐서 뺀 철도망 노선 수 */
    private final int supersededLines;

    // ---- GTFS 노선 (정류장 번호 h 의 노드 번호는 stations.length + h, 자리 q 의 노드 번호는 stations.length + stops.length + q) ----
    private final GtfsFeed.Stop[] stops;
    private final GtfsFeed.Pattern[] patterns;
    private final int[] patternStart;          // 패턴 p 의 첫 자리 번호
    private final int[] positionPattern;       // 자리 q 가 속한 패턴
    private final int[][] stopBoards;          // 정류장에서 탈 수 있는 자리들
    private final int[][] stopLinkNodes;       // 정류장에서 걸어갈 수 있는 노드(정류장·역)
    private final float[][] stopLinkKm;
    private final int[][] stationStops;        // 역에서 걸어갈 수 있는 정류장
    private final float[][] stationStopKm;
    /** 자리 q 에서 같은 방향으로 가는 같은 이름 노선들의 운행 횟수 합 [q*3 + 요일 종류] */
    private final int[] groupTrips;
    /** 그 노선 묶음의 첫차~막차 사이 시간(분) */
    private final int[] groupSpan;
    /** 자리 q 에서 다음 자리까지의 직선거리(km) */
    private final float[] hopKm;
    /** 서로 이어진 노드 묶음 번호 (역·정류장). 출발지와 도착지 쪽 묶음이 하나도 겹치지 않으면 경로가 없다 */
    private final int[] component;
    private final Map<Long, List<Integer>> stopGrid = new HashMap<>();
    private final int nodeCount;

    /** 탐색용 배열 묶음을 돌려 쓴다. 동시에 도는 탐색 수만큼만 만들어지고, 그 이상은 늘지 않는다 */
    private final java.util.concurrent.ConcurrentLinkedQueue<Scratch> scratchPool = new java.util.concurrent.ConcurrentLinkedQueue<>();

    /** 탐색 한 번에 쓰는 배열. 노드가 수십만 개일 수 있어 매번 새로 만들지 않고, 방문한 곳만 표시해 다시 쓴다. */
    private static final class Scratch {
        final double[] dist;
        final int[] previous;
        final byte[] kind;
        final double[] minutes;
        final double[] km;
        /** 이 노드까지 오는 경로가 출발지에서 처음 걸어간 거리(km) */
        final double[] accessKm;
        final int[] stamp;
        int version;

        Scratch(int n) {
            dist = new double[n];
            previous = new int[n];
            kind = new byte[n];
            minutes = new double[n];
            km = new double[n];
            accessKm = new double[n];
            stamp = new int[n];
        }

        double dist(int node) {
            return stamp[node] == version ? dist[node] : Double.MAX_VALUE;
        }
    }

    /**
     * @param links {역 번호 a, 역 번호 b, km} — 같은 노선의 이웃 역
     */
    public RailGraph(List<Station> stationList, List<double[]> links) {
        this(stationList, links, new ArrayList<>(), new ArrayList<>());
    }

    /**
     * @param links      {역 번호 a, 역 번호 b, km} — 같은 노선의 이웃 역
     * @param stopList   GTFS 정류장 (index 는 0부터 순서대로)
     * @param patternList GTFS 노선 패턴. stops 는 stopList 의 번호
     */
    public RailGraph(List<Station> stationList, List<double[]> links, List<GtfsFeed.Stop> stopList, List<GtfsFeed.Pattern> patternList) {
        for (int i = 0; i < stationList.size(); i++) {
            if (stationList.get(i).index != i) throw new IllegalArgumentException("역 번호는 0부터 순서대로여야 합니다.");
        }
        // GTFS 로 가져온 노면전차·케이블카 등이 철도망의 같은 노선과 겹치면 철도망 쪽을 뺀다 (실제 시각표·요금이 있는 쪽을 쓴다)
        Set<String> covered = linesCoveredByFeeds(stationList, stopList, patternList);
        supersededLines = covered.size();
        if (!covered.isEmpty()) {
            int[] newIndex = new int[stationList.size()];
            List<Station> kept = new ArrayList<>();
            for (Station st : stationList) {
                if (covered.contains(st.lineKey())) {
                    newIndex[st.index] = -1;
                } else {
                    newIndex[st.index] = kept.size();
                    kept.add(new Station(kept.size(), st.name, st.operator, st.line, st.groupCode, st.category, st.latitude, st.longitude));
                }
            }
            List<double[]> keptLinks = new ArrayList<>();
            for (double[] link : links) {
                int a = (int) link[0];
                int b = (int) link[1];
                if (a < 0 || b < 0 || a >= newIndex.length || b >= newIndex.length || newIndex[a] < 0 || newIndex[b] < 0) continue;
                keptLinks.add(new double[]{newIndex[a], newIndex[b], link[2]});
            }
            stationList = kept;
            links = keptLinks;
        }
        stations = stationList.toArray(new Station[0]);
        for (int i = 0; i < stations.length; i++) {
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
            for (int j : near(grid, a.latitude, a.longitude, 0.8)) {
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

        // ---------------- GTFS 노선 ----------------
        stops = stopList.toArray(new GtfsFeed.Stop[0]);
        patterns = patternList.toArray(new GtfsFeed.Pattern[0]);
        for (int h = 0; h < stops.length; h++) {
            if (stops[h].index != h) throw new IllegalArgumentException("정류장 번호는 0부터 순서대로여야 합니다.");
            stopGrid.computeIfAbsent(cell(stops[h].latitude, stops[h].longitude), k -> new ArrayList<>()).add(h);
        }
        patternStart = new int[patterns.length + 1];
        for (int p = 0; p < patterns.length; p++) {
            for (int stop : patterns[p].stops) {
                if (stop < 0 || stop >= stops.length) throw new IllegalArgumentException("노선 패턴이 없는 정류장을 가리킵니다.");
            }
            patternStart[p + 1] = patternStart[p] + patterns[p].stops.length;
        }
        int positions = patternStart[patterns.length];
        positionPattern = new int[positions];
        List<List<Integer>> boards = new ArrayList<>();
        for (int h = 0; h < stops.length; h++) boards.add(new ArrayList<>());
        for (int p = 0; p < patterns.length; p++) {
            GtfsFeed.Pattern pattern = patterns[p];
            for (int i = 0; i < pattern.stops.length; i++) {
                int q = patternStart[p] + i;
                positionPattern[q] = p;
                boolean canBoard = i < pattern.stops.length - 1 && (pattern.flags == null || (pattern.flags[i] & 1) == 0);
                if (canBoard) boards.get(pattern.stops[i]).add(q);
            }
        }
        hopKm = new float[positions];
        for (int p = 0; p < patterns.length; p++) {
            GtfsFeed.Pattern pattern = patterns[p];
            for (int i = 0; i + 1 < pattern.stops.length; i++) {
                hopKm[patternStart[p] + i] = (float) stopKm(pattern.stops[i], pattern.stops[i + 1]);
            }
        }
        stopBoards = new int[stops.length][];
        groupTrips = new int[positions * 3];
        groupSpan = new int[positions];
        for (int h = 0; h < stops.length; h++) {
            stopBoards[h] = toArray(boards.get(h));
            // 같은 정류장에서 같은 다음 정류장으로 가는 같은 이름의 노선은 승객에게 한 노선이다: 운행 횟수를 합치고 첫차~막차도 함께 본다
            Map<String, int[]> groups = new HashMap<>();     // {평일, 토요일, 휴일 횟수, 첫차, 막차}
            for (int q : stopBoards[h]) {
                GtfsFeed.Pattern pattern = patterns[positionPattern[q]];
                int offset = Math.round(pattern.cumMinutes[q - patternStart[positionPattern[q]]]);
                int[] sum = groups.computeIfAbsent(groupKey(q), k -> new int[]{0, 0, 0, Integer.MAX_VALUE, Integer.MIN_VALUE});
                for (int d = 0; d < 3; d++) sum[d] += pattern.trips[d];
                sum[3] = Math.min(sum[3], pattern.firstDeparture + offset);
                sum[4] = Math.max(sum[4], pattern.lastDeparture + offset);
            }
            for (int q : stopBoards[h]) {
                int[] sum = groups.get(groupKey(q));
                for (int d = 0; d < 3; d++) groupTrips[q * 3 + d] = sum[d];
                groupSpan[q] = sum[4] - sum[3];
            }
        }

        // 걸어서 갈아타는 연결: 정류장 ↔ 가까운 정류장, 정류장 ↔ 가까운 역
        stopLinkNodes = new int[stops.length][];
        stopLinkKm = new float[stops.length][];
        List<List<float[]>> stationLinks = new ArrayList<>();
        for (int i = 0; i < stations.length; i++) stationLinks.add(null);
        for (int h = 0; h < stops.length; h++) {
            GtfsFeed.Stop stop = stops[h];
            List<float[]> found = new ArrayList<>();      // {km, 노드 번호}
            for (int other : near(stopGrid, stop.latitude, stop.longitude, STOP_TRANSFER_KM)) {
                if (other == h) continue;
                double km = DistanceUtil.calculateDistance(stop.latitude, stop.longitude, stops[other].latitude, stops[other].longitude);
                if (km <= STOP_TRANSFER_KM) found.add(new float[]{(float) km, stations.length + other});
            }
            found.sort(Comparator.comparingDouble(f -> f[0]));
            if (found.size() > MAX_STOP_LINKS) found = new ArrayList<>(found.subList(0, MAX_STOP_LINKS));
            for (int s : near(grid, stop.latitude, stop.longitude, WALK_TRANSFER_KM)) {
                double km = DistanceUtil.calculateDistance(stop.latitude, stop.longitude, stations[s].latitude, stations[s].longitude);
                if (km > WALK_TRANSFER_KM) continue;
                found.add(new float[]{(float) km, s});
                if (stationLinks.get(s) == null) stationLinks.set(s, new ArrayList<>());
                stationLinks.get(s).add(new float[]{(float) km, h});
            }
            stopLinkNodes[h] = new int[found.size()];
            stopLinkKm[h] = new float[found.size()];
            for (int k = 0; k < found.size(); k++) {
                stopLinkKm[h][k] = found.get(k)[0];
                stopLinkNodes[h][k] = (int) found.get(k)[1];
            }
        }
        stationStops = new int[stations.length][];
        stationStopKm = new float[stations.length][];
        for (int s = 0; s < stations.length; s++) {
            List<float[]> list = stationLinks.get(s);
            if (list == null) continue;
            stationStops[s] = new int[list.size()];
            stationStopKm[s] = new float[list.size()];
            for (int k = 0; k < list.size(); k++) {
                stationStopKm[s][k] = list.get(k)[0];
                stationStops[s][k] = (int) list.get(k)[1];
            }
        }
        nodeCount = stations.length + stops.length + positions;
        component = components();
    }

    /** 역·정류장을 "어떻게든 이어진 것끼리" 묶는다 (방향·운행 요일은 따지지 않는다). */
    private int[] components() {
        int n = stations.length + stops.length;
        int[] parent = new int[n];
        for (int i = 0; i < n; i++) parent[i] = i;
        for (int i = 0; i < stations.length; i++) {
            for (Edge edge : edges.get(i)) union(parent, i, edge.to);
        }
        for (int h = 0; h < stops.length; h++) {
            for (int node : stopLinkNodes[h]) union(parent, stations.length + h, node);
        }
        for (GtfsFeed.Pattern pattern : patterns) {
            for (int i = 1; i < pattern.stops.length; i++) union(parent, stations.length + pattern.stops[0], stations.length + pattern.stops[i]);
        }
        for (int i = 0; i < n; i++) parent[i] = find(parent, i);
        return parent;
    }

    private static int find(int[] parent, int x) {
        while (parent[x] != x) {
            parent[x] = parent[parent[x]];
            x = parent[x];
        }
        return x;
    }

    private static void union(int[] parent, int a, int b) {
        int ra = find(parent, a);
        int rb = find(parent, b);
        if (ra != rb) parent[ra] = rb;
    }

    /**
     * 철도망의 노선(운영사|노선) 가운데, 역의 80% 이상이 GTFS 의 궤도 계열 정류장(노면전차·케이블카·지하철·사철 등)에서 250m 안에 있는 것.
     * JR·신칸센은 대상이 아니다. 역이 겨우 몇 개 겹치는 정도(환승역)로는 빠지지 않는다.
     */
    static Set<String> linesCoveredByFeeds(List<Station> stationList, List<GtfsFeed.Stop> stopList, List<GtfsFeed.Pattern> patternList) {
        Set<String> covered = new java.util.HashSet<>();
        Map<Long, List<Integer>> trackStops = new HashMap<>();
        boolean[] seen = new boolean[stopList.size()];
        for (GtfsFeed.Pattern pattern : patternList) {
            if (pattern.category == RailCategory.BUS || pattern.category == RailCategory.FERRY
                    || pattern.category == RailCategory.JR || pattern.category == RailCategory.SHINKANSEN) continue;
            for (int stop : pattern.stops) {
                if (stop < 0 || stop >= seen.length || seen[stop]) continue;
                seen[stop] = true;
                trackStops.computeIfAbsent(cell(stopList.get(stop).latitude, stopList.get(stop).longitude), k -> new ArrayList<>()).add(stop);
            }
        }
        if (trackStops.isEmpty()) return covered;

        Map<String, int[]> counts = new HashMap<>();      // 노선 → {역 수, 겹치는 역 수}
        for (Station station : stationList) {
            if (station.category == RailCategory.JR || station.category == RailCategory.SHINKANSEN) continue;
            int[] count = counts.computeIfAbsent(station.lineKey(), k -> new int[2]);
            count[0]++;
            for (int stop : near(trackStops, station.latitude, station.longitude, 0.25)) {
                GtfsFeed.Stop other = stopList.get(stop);
                if (DistanceUtil.calculateDistance(station.latitude, station.longitude, other.latitude, other.longitude) <= 0.25) {
                    count[1]++;
                    break;
                }
            }
        }
        for (Map.Entry<String, int[]> e : counts.entrySet()) {
            int total = e.getValue()[0];
            if (total >= 2 && e.getValue()[1] * 10 >= total * 8) covered.add(e.getKey());
        }
        return covered;
    }

    private String groupKey(int q) {
        int p = positionPattern[q];
        GtfsFeed.Pattern pattern = patterns[p];
        int next = pattern.stops[q - patternStart[p] + 1];
        return pattern.feedKey + "|" + pattern.routeName + "|" + next;
    }

    private static int[] toArray(List<Integer> list) {
        int[] array = new int[list.size()];
        for (int i = 0; i < array.length; i++) array[i] = list.get(i);
        return array;
    }

    /** 파일에서 계산한 결과로 바로 그래프를 만든다 (DB 를 거치지 않는 검증·테스트용) */
    public static RailGraph of(RailTopologyBuilder.Result topology) {
        return of(topology, null);
    }

    /** 철도망과 GTFS 노선을 합친 그래프. 어느 한쪽이 null 이어도 된다. */
    public static RailGraph of(RailTopologyBuilder.Result topology, GtfsNetwork network) {
        List<Station> list = new ArrayList<>();
        List<double[]> links = new ArrayList<>();
        if (topology != null) {
            for (RailTopologyBuilder.StationRecord r : topology.stations) {
                list.add(new Station(r.index, r.name, r.operator, r.line, r.groupCode, r.category, r.latitude, r.longitude));
            }
            for (RailTopologyBuilder.LinkRecord l : topology.links) links.add(new double[]{l.from, l.to, l.km});
        }
        if (network == null) return new RailGraph(list, links);
        return new RailGraph(list, links, network.stops, network.patterns);
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

    /** 요금 자료를 바꾼다. 이후에 찾는 경로부터 적용된다 */
    public void setFareBook(FareBook book) {
        this.fareBook = book == null ? FareBook.EMPTY : book;
    }

    public FareBook fareBook() { return fareBook; }

    public int stationCount() { return stations.length; }
    public int rideLinkCount() { return rideLinks; }
    public int transferLinkCount() { return transferLinks; }
    public Station station(int index) { return stations[index]; }
    public int stopCount() { return stops.length; }
    public int patternCount() { return patterns.length; }
    public boolean isEmpty() { return stations.length == 0 && stops.length == 0; }
    public int supersededLineCount() { return supersededLines; }

    /** 그 지점에서 withinKm 안에 GTFS 정류장이 있는가 (그곳에 버스 등의 노선 자료가 있는지 판단하는 데 쓴다) */
    public boolean hasStopNear(double lat, double lng, double withinKm) {
        for (int h : near(stopGrid, lat, lng, withinKm)) {
            if (DistanceUtil.calculateDistance(lat, lng, stops[h].latitude, stops[h].longitude) <= withinKm) return true;
        }
        return false;
    }

    /** 그 지점에서 가장 가까운 역과 거리(km). 역이 하나도 없으면 null */
    public Station nearestStation(double lat, double lng, double withinKm) {
        Station best = null;
        double bestKm = withinKm;
        for (int i : near(grid, lat, lng, withinKm)) {
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

    /** 날짜를 모를 때의 경로 (평일 운행 기준). */
    public Route route(double fromLat, double fromLng, double toLat, double toLng) {
        return route(fromLat, fromLng, toLat, toLng, null, null);
    }

    /**
     * 두 지점 사이의 가장 빠른 대중교통 경로. 걸어갈 수 있는 역·정류장이 한쪽이라도 없거나 이어지는 경로가 없으면 null.
     *
     * @param date         이동하는 날. 요일(평일·토요일·휴일)에 맞는 운행 횟수와 계절 노선의 운행 기간을 본다. 모르면 null (평일로 본다)
     * @param departMinute 출발 시각(0시부터의 분). 첫차·막차 밖인지 표시하는 데만 쓴다. 모르면 null
     */
    public Route route(double fromLat, double fromLng, double toLat, double toLng, LocalDate date, Integer departMinute) {
        return route(fromLat, fromLng, toLat, toLng, date, departMinute, Double.MAX_VALUE);
    }

    /**
     * @param maxWalkKm 출발지에서 타는 곳까지 + 내리는 곳에서 도착지까지 걷는 직선거리 합의 상한(km).
     *                  이보다 많이 걸어야 하는 경로는 후보에서 빼고, 덜 걷는 다른 경로(운행이 뜸한 버스 등)를 찾는다.
     */
    public Route route(double fromLat, double fromLng, double toLat, double toLng, LocalDate date, Integer departMinute, double maxWalkKm) {
        Map<Integer, Double> access = accessNodes(fromLat, fromLng);
        Map<Integer, Double> egress = accessNodes(toLat, toLng);
        access.values().removeIf(km -> km > maxWalkKm);
        egress.values().removeIf(km -> km > maxWalkKm);
        if (access.isEmpty() || egress.isEmpty()) return null;
        if (!connected(access, egress)) return null;
        int dayType = JapanHolidays.dayType(date);
        Scratch s = scratchPool.poll();
        if (s == null) s = new Scratch(nodeCount);
        try {
            return search(s, access, egress, fromLat, fromLng, toLat, toLng, date, dayType, departMinute, maxWalkKm);
        } finally {
            scratchPool.offer(s);
        }
    }

    private Route search(Scratch s, Map<Integer, Double> access, Map<Integer, Double> egress, double fromLat, double fromLng,
                         double toLat, double toLng, LocalDate date, int dayType, Integer departMinute, double maxWalkKm) {
        if (++s.version == Integer.MAX_VALUE) {
            java.util.Arrays.fill(s.stamp, 0);
            s.version = 1;
        }
        // 평면 근사 좌표 (km). GTFS 노선의 탐색 범위를 재는 데만 쓴다
        double kmPerLng = 111.32 * Math.cos(Math.toRadians((fromLat + toLat) / 2));
        double detourLimit = DETOUR_FACTOR * Math.hypot((toLat - fromLat) * 110.57, (toLng - fromLng) * kmPerLng) + DETOUR_SLACK_KM;

        PriorityQueue<double[]> queue = new PriorityQueue<>((a, b) -> Double.compare(a[0], b[0]));
        for (Map.Entry<Integer, Double> e : access.entrySet()) {
            int node = e.getKey();
            double start = e.getValue() * WALK_MIN_PER_KM;
            if (node < stations.length) {
                start += RailFareModel.boardingMinutes(stations[node].category) + boardingPenalty(stations[node].category);
            }
            if (relax(s, queue, -1, start, node, (byte) 0, 0, 0)) s.accessKm[node] = e.getValue();
        }

        double bestTotal = Double.MAX_VALUE;
        int bestEnd = -1;
        int firstStop = stations.length;
        int firstPosition = stations.length + stops.length;
        while (!queue.isEmpty()) {
            double[] top = queue.poll();
            int u = (int) top[1];
            double cost = top[0];
            if (cost > s.dist(u)) continue;
            if (cost >= bestTotal) break;                 // 더 빠른 도착은 나올 수 없다
            Double walkOut = egress.get(u);
            // 아무것도 타지 않고 "역·정류장까지 걸어갔다가 다시 걸어 나오는" 것은 경로가 아니다
            if (walkOut != null && s.previous[u] >= 0 && s.accessKm[u] + walkOut <= maxWalkKm && riddenBefore(s, u)) {
                double total = cost + walkOut * WALK_MIN_PER_KM;
                if (total < bestTotal) {
                    bestTotal = total;
                    bestEnd = u;
                }
            }

            if (u < firstStop) {
                // ---- 철도역 ----
                for (Edge edge : edges.get(u)) {
                    relax(s, queue, u, cost + edge.cost, edge.to, edge.transfer ? RAIL_TRANSFER : RAIL_RIDE, edge.minutes, edge.km);
                }
                int[] nearStops = stationStops[u];
                if (nearStops != null) {
                    for (int k = 0; k < nearStops.length; k++) {
                        double walk = stationStopKm[u][k] * WALK_MIN_PER_KM;
                        relax(s, queue, u, cost + walk + TRANSFER_PENALTY, firstStop + nearStops[k], LINK, walk, stationStopKm[u][k]);
                    }
                }
            } else if (u < firstPosition) {
                // ---- 정류장 ----
                int h = u - firstStop;
                if (tooFar(stops[h], fromLat, fromLng, toLat, toLng, kmPerLng, detourLimit)) continue;
                for (int q : stopBoards[h]) {
                    GtfsFeed.Pattern pattern = patterns[positionPattern[q]];
                    if (!runs(pattern, date, dayType)) continue;
                    double headway = headway(groupSpan[q], groupTrips[q * 3 + dayType]);
                    double wait = waitMinutes(headway);
                    relax(s, queue, u, cost + wait + Math.min(MAX_FREQUENCY_PENALTY, headway / 3.0), firstPosition + q, BOARD, wait, 0);
                }
                int[] linked = stopLinkNodes[h];
                for (int k = 0; k < linked.length; k++) {
                    int to = linked[k];
                    double walk = stopLinkKm[h][k] * WALK_MIN_PER_KM;
                    if (to < firstStop) {
                        double minutes = walk + (stations[to].category == RailCategory.SHINKANSEN
                                ? RailFareModel.boardingMinutes(RailCategory.SHINKANSEN) : TRANSFER_MINUTES);
                        relax(s, queue, u, cost + minutes + TRANSFER_PENALTY + boardingPenalty(stations[to].category), to, LINK, minutes, stopLinkKm[h][k]);
                    } else {
                        relax(s, queue, u, cost + walk + 1, to, LINK, walk, stopLinkKm[h][k]);
                    }
                }
            } else {
                // ---- 노선 패턴의 한 자리 ----
                int q = u - firstPosition;
                int p = positionPattern[q];
                GtfsFeed.Pattern pattern = patterns[p];
                int i = q - patternStart[p];
                if (i + 1 < pattern.stops.length && !tooFar(stops[pattern.stops[i]], fromLat, fromLng, toLat, toLng, kmPerLng, detourLimit)) {
                    double minutes = Math.max(0.2, pattern.cumMinutes[i + 1] - pattern.cumMinutes[i]);
                    relax(s, queue, u, cost + minutes, u + 1, LINE_RIDE, minutes, hopKm[q]);
                }
                boolean canAlight = s.kind[u] == LINE_RIDE && (pattern.flags == null || (pattern.flags[i] & 2) == 0);
                if (canAlight) relax(s, queue, u, cost, firstStop + pattern.stops[i], ALIGHT, 0, 0);
            }
        }
        if (bestEnd < 0) return null;

        // 경로 복원
        List<Integer> path = new ArrayList<>();
        for (int at = bestEnd; at >= 0; at = s.previous[at]) path.add(0, at);
        return describe(path, s, fromLat, fromLng, toLat, toLng, date, dayType, departMinute);
    }

    private boolean connected(Map<Integer, Double> access, Map<Integer, Double> egress) {
        java.util.Set<Integer> reachable = new java.util.HashSet<>();
        for (int node : access.keySet()) reachable.add(component[node]);
        for (int node : egress.keySet()) if (reachable.contains(component[node])) return true;
        return false;
    }

    private static boolean tooFar(GtfsFeed.Stop stop, double fromLat, double fromLng, double toLat, double toLng, double kmPerLng, double limit) {
        double a = Math.hypot((stop.latitude - fromLat) * 110.57, (stop.longitude - fromLng) * kmPerLng);
        double b = Math.hypot((stop.latitude - toLat) * 110.57, (stop.longitude - toLng) * kmPerLng);
        return a + b > limit;
    }

    private static boolean relax(Scratch s, PriorityQueue<double[]> queue, int from, double cost, int to, byte kind, double minutes, double km) {
        if (cost >= s.dist(to)) return false;
        s.stamp[to] = s.version;
        s.dist[to] = cost;
        s.previous[to] = from;
        s.kind[to] = kind;
        s.minutes[to] = minutes;
        s.km[to] = km;
        if (from >= 0) s.accessKm[to] = s.accessKm[from];
        queue.add(new double[]{cost, to});
        return true;
    }

    private static boolean riddenBefore(Scratch s, int node) {
        for (int at = node; s.previous[at] >= 0; at = s.previous[at]) {
            if (s.kind[at] == RAIL_RIDE || s.kind[at] == LINE_RIDE) return true;
        }
        return false;
    }

    /** 그 날 다니는 노선인가: 그 요일의 운행이 있고, 계절 노선이면 운행 기간 안이어야 한다. */
    private static boolean runs(GtfsFeed.Pattern pattern, LocalDate date, int dayType) {
        if (pattern.trips[dayType] <= 0) return false;
        if (date == null || pattern.feedExpired) return true;
        if (pattern.serviceFrom != null && date.isBefore(pattern.serviceFrom)) return false;
        return pattern.serviceTo == null || !date.isAfter(pattern.serviceTo);
    }

    /** 평균 운행 간격(분): 첫차~막차 사이를 운행 횟수로 나눈다. 하루 한 번이면 12시간으로 본다. */
    static double headway(int spanMinutes, int trips) {
        if (trips <= 1) return 720;
        return Math.max(2, Math.min(720, Math.max(60, spanMinutes) / (double) (trips - 1)));
    }

    static double waitMinutes(double headway) {
        return Math.max(2, Math.min(MAX_WAIT_MINUTES, headway / 2.0));
    }

    private double stopKm(int a, int b) {
        return DistanceUtil.calculateDistance(stops[a].latitude, stops[a].longitude, stops[b].latitude, stops[b].longitude);
    }

    /** 걸어갈 수 있는 역·정류장(노드 번호) → 직선거리(km) */
    private Map<Integer, Double> accessNodes(double lat, double lng) {
        Map<Integer, Double> result = new HashMap<>();

        // 역: 가까운 순으로, 같은 노선에서는 가장 가까운 두 역까지만 (양쪽 방향)
        List<double[]> found = within(grid, lat, lng, ACCESS_KM, true);
        if (found.isEmpty()) found = within(grid, lat, lng, WIDE_ACCESS_KM, true);
        Map<String, Integer> perLine = new HashMap<>();
        int taken = 0;
        for (double[] f : found) {
            Station station = stations[(int) f[1]];
            if (perLine.merge(station.lineKey(), 1, Integer::sum) > 2) continue;
            result.put(station.index, f[0]);
            if (++taken >= MAX_ACCESS_STATIONS) break;
        }

        // 정류장: 가까운 순으로
        List<double[]> nearStops = within(stopGrid, lat, lng, STOP_ACCESS_KM, false);
        if (nearStops.isEmpty()) nearStops = within(stopGrid, lat, lng, WIDE_STOP_ACCESS_KM, false);
        taken = 0;
        for (double[] f : nearStops) {
            result.put(stations.length + (int) f[1], f[0]);
            if (++taken >= MAX_ACCESS_STOPS) break;
        }
        return result;
    }

    private List<double[]> within(Map<Long, List<Integer>> index, double lat, double lng, double radiusKm, boolean rail) {
        List<double[]> found = new ArrayList<>();
        for (int i : near(index, lat, lng, radiusKm)) {
            double km = rail
                    ? DistanceUtil.calculateDistance(lat, lng, stations[i].latitude, stations[i].longitude)
                    : DistanceUtil.calculateDistance(lat, lng, stops[i].latitude, stops[i].longitude);
            if (km <= radiusKm) found.add(new double[]{km, i});
        }
        found.sort(Comparator.comparingDouble(a -> a[0]));
        return found;
    }

    // =====================================================================
    // 경로 → 단계 설명, 요금
    // =====================================================================

    private boolean isStop(int node) { return node >= stations.length; }

    private String nodeName(int node) {
        return node < stations.length ? stations[node].name : stops[node - stations.length].name;
    }

    private String nodeKo(int node) {
        return node < stations.length ? null : stops[node - stations.length].nameKo;
    }

    private double nodeLat(int node) {
        return node < stations.length ? stations[node].latitude : stops[node - stations.length].latitude;
    }

    private double nodeLng(int node) {
        return node < stations.length ? stations[node].longitude : stops[node - stations.length].longitude;
    }

    /** 노드의 나열을 "걷기 → 대기 → 승차 → 환승 → 승차 → 걷기" 단계로 정리하고 요금을 계산한다. */
    private Route describe(List<Integer> path, Scratch s, double fromLat, double fromLng, double toLat, double toLng,
                           LocalDate date, int dayType, Integer departMinute) {
        int firstPosition = stations.length + stops.length;
        // 앞뒤의 "타지 않고 걷기·환승만 한" 부분은 잘라낸다: 실제로는 처음 타는 곳까지 바로 걸어간 것이다
        int firstRide = -1;
        int lastRide = -1;
        for (int k = 1; k < path.size(); k++) {
            byte kind = s.kind[path.get(k)];
            if (kind == RAIL_RIDE || kind == LINE_RIDE) {
                if (firstRide < 0) firstRide = k;
                lastRide = k;
            }
        }
        // 철도는 타는 역이 ride 직전, GTFS 노선은 "정류장 → (BOARD) → 자리 → (LINE_RIDE)" 이므로 정류장이 두 칸 앞이다
        int boardIndex = s.kind[path.get(firstRide)] == RAIL_RIDE ? firstRide - 1 : firstRide - 2;
        int alightIndex = s.kind[path.get(lastRide)] == RAIL_RIDE ? lastRide : lastRide + 1;
        int boardNode = path.get(boardIndex);
        int alightNode = path.get(alightIndex);

        Route route = new Route();
        Step walkIn = new Step();
        walkIn.kind = Step.Kind.WALK;
        walkIn.to = nodeName(boardNode);
        walkIn.toKo = nodeKo(boardNode);
        walkIn.toIsStop = isStop(boardNode);
        walkIn.km = DistanceUtil.calculateDistance(fromLat, fromLng, nodeLat(boardNode), nodeLng(boardNode));
        // GTFS 노선을 처음 탈 때의 대기 시간은, 그 구간을 바로 가는 편이 하루 몇 번인지 알고 난 뒤(내릴 때)에 정한다
        double firstWait = boardNode < stations.length ? RailFareModel.boardingMinutes(stations[boardNode].category) : 0;
        walkIn.minutes = (int) Math.ceil(walkIn.km * WALK_MIN_PER_KM);
        route.steps.add(walkIn);
        Step wait = new Step();
        wait.kind = Step.Kind.WAIT;
        wait.from = walkIn.to;
        wait.fromKo = walkIn.toKo;
        wait.fromIsStop = walkIn.toIsStop;
        wait.to = walkIn.to;
        wait.toKo = walkIn.toKo;
        wait.toIsStop = walkIn.toIsStop;
        wait.minutes = (int) Math.ceil(firstWait);
        route.steps.add(wait);

        int clock = departMinute == null ? -1 : departMinute + walkIn.minutes + wait.minutes;
        Step ride = null;
        Step transfer = null;
        Step waitingStep = null;        // 지금 탄 GTFS 노선을 기다린 시간을 넣을 단계 (처음이면 WAIT, 갈아탄 것이면 TRANSFER)
        double exactMinutes = 0;
        int boardPosition = -1;
        for (int k = boardIndex + 1; k <= alightIndex; k++) {
            int node = path.get(k);
            int before = path.get(k - 1);
            byte kind = s.kind[node];
            switch (kind) {
                case RAIL_RIDE: {
                    Station from = stations[before];
                    Station to = stations[node];
                    if (ride == null) {
                        ride = new Step();
                        ride.kind = Step.Kind.RIDE;
                        ride.category = from.category;
                        ride.operator = from.operator;
                        ride.line = from.line;
                        ride.from = from.name;
                        ride.hasCoordinates = true;
                        ride.fromLat = from.latitude;
                        ride.fromLng = from.longitude;
                        exactMinutes = 0;
                        route.steps.add(ride);
                        transfer = null;
                    }
                    ride.to = to.name;
                    ride.toLat = to.latitude;
                    ride.toLng = to.longitude;
                    ride.km += s.km[node];
                    ride.stops++;
                    exactMinutes += s.minutes[node];
                    route.railKm += s.km[node];
                    break;
                }
                case BOARD: {
                    if (k > boardIndex + 1) {
                        // 갈아타며 기다리는 시간은 환승 단계에 넣는다 (같은 정류장에서 갈아타면 걷는 거리 0 인 환승)
                        if (transfer == null) transfer = addTransfer(route, before, before);
                        waitingStep = transfer;
                    } else {
                        waitingStep = wait;
                    }
                    int q = node - firstPosition;
                    GtfsFeed.Pattern pattern = patterns[positionPattern[q]];
                    ride = new Step();
                    ride.kind = Step.Kind.RIDE;
                    ride.scheduled = true;
                    ride.category = pattern.category;
                    ride.operator = pattern.agency;
                    ride.line = pattern.routeName;
                    ride.lineLongName = pattern.routeLongName;
                    ride.headsign = pattern.headsign;
                    ride.from = stops[before - stations.length].name;
                    ride.fromKo = stops[before - stations.length].nameKo;
                    ride.fromIsStop = true;
                    ride.toIsStop = true;
                    ride.hasCoordinates = true;
                    ride.fromLat = stops[before - stations.length].latitude;
                    ride.fromLng = stops[before - stations.length].longitude;
                    ride.irregular = pattern.irregular;
                    ride.outdated = pattern.feedExpired;
                    exactMinutes = 0;
                    boardPosition = q;
                    route.steps.add(ride);
                    transfer = null;
                    break;
                }
                case LINE_RIDE: {
                    ride.km += s.km[node];
                    ride.stops++;
                    exactMinutes += s.minutes[node];
                    break;
                }
                case ALIGHT: {
                    int q = before - firstPosition;
                    int p = positionPattern[q];
                    GtfsFeed.Pattern pattern = patterns[p];
                    int from = boardPosition - patternStart[p];
                    int to = q - patternStart[p];
                    GtfsFeed.Stop stop = stops[node - stations.length];
                    ride.to = stop.name;
                    ride.toKo = stop.nameKo;
                    ride.toLat = stop.latitude;
                    ride.toLng = stop.longitude;
                    ride.minutes = Math.max(1, (int) Math.round(exactMinutes));
                    int fare = pattern.fare(from, to);
                    ride.fareConfirmed = fare >= 0;
                    ride.fareYen = fare >= 0 ? fare : RailFareModel.unknownLocalFare(ride.km / 1.25);
                    describeService(ride, pattern, from, pattern.stops[from], pattern.stops[to], date, dayType);
                    // 기다리는 시간은 안내에 적는 운행 간격과 같은 기준(이 구간을 바로 가는 모든 편)으로 잡는다
                    int waited = (int) Math.ceil(waitMinutes(ride.headwayMinutes));
                    waitingStep.minutes += waited;
                    if (clock >= 0) {
                        clock += waited;
                        // 날짜가 넘어가는 심야 운행(24시 이후 표기)도 있으므로 하루를 더한 시각으로도 견줘 본다
                        boolean inside = within(clock, ride) || within(clock + 1440, ride);
                        ride.outsideServiceHours = !inside;
                        clock += ride.minutes;
                    }
                    ride = null;
                    break;
                }
                default: {   // RAIL_TRANSFER, LINK: 걸어서 갈아탄다
                    if (ride != null) {
                        ride.minutes = (int) Math.ceil(exactMinutes);
                        if (clock >= 0) clock += ride.minutes;
                        ride = null;
                    }
                    if (transfer == null) {
                        transfer = addTransfer(route, before, node);
                    } else {
                        transfer.to = nodeName(node);
                        transfer.toKo = nodeKo(node);
                        transfer.toIsStop = isStop(node);
                    }
                    transfer.km += s.km[node];
                    transfer.minutes += (int) Math.ceil(s.minutes[node]);
                    if (clock >= 0) clock += (int) Math.ceil(s.minutes[node]);
                    break;
                }
            }
        }
        if (ride != null) ride.minutes = (int) Math.ceil(exactMinutes);

        Step walkOut = new Step();
        walkOut.kind = Step.Kind.WALK;
        walkOut.from = nodeName(alightNode);
        walkOut.fromKo = nodeKo(alightNode);
        walkOut.fromIsStop = isStop(alightNode);
        walkOut.km = DistanceUtil.calculateDistance(nodeLat(alightNode), nodeLng(alightNode), toLat, toLng);
        walkOut.minutes = (int) Math.ceil(walkOut.km * WALK_MIN_PER_KM);
        route.steps.add(walkOut);

        int rides = 0;
        for (Step step : route.steps) {
            route.minutes += step.minutes;
            if (step.kind == Step.Kind.RIDE) rides++;
        }
        route.transfers = Math.max(0, rides - 1);
        route.walkKm = walkIn.km + walkOut.km;
        route.railKm = Math.round(route.railKm * 10.0) / 10.0;
        priceRoute(route);
        return route;
    }

    private static boolean within(int clock, Step ride) {
        return clock >= ride.firstDeparture - 30 && clock <= ride.lastDeparture + 10;
    }

    private Step addTransfer(Route route, int fromNode, int toNode) {
        Step transfer = new Step();
        transfer.kind = Step.Kind.TRANSFER;
        transfer.from = nodeName(fromNode);
        transfer.fromKo = nodeKo(fromNode);
        transfer.fromIsStop = isStop(fromNode);
        transfer.to = nodeName(toNode);
        transfer.toKo = nodeKo(toNode);
        transfer.toIsStop = isStop(toNode);
        route.steps.add(transfer);
        return transfer;
    }

    /**
     * 타는 정류장에서 내리는 정류장까지 바로 가는 편을 모두 모아 운행 횟수·간격·첫차·막차를 채운다.
     * 탐색이 고른 노선 하나만이 아니라, 같은 구간을 가는 다른 노선도 탈 수 있기 때문이다.
     */
    private void describeService(Step ride, GtfsFeed.Pattern chosen, int chosenFrom, int boardStop, int alightStop, LocalDate date, int dayType) {
        int trips = 0;
        int first = Integer.MAX_VALUE;
        int last = Integer.MIN_VALUE;
        Set<String> others = new LinkedHashSet<>();
        for (int q : stopBoards[boardStop]) {
            int p = positionPattern[q];
            GtfsFeed.Pattern pattern = patterns[p];
            if (!runs(pattern, date, dayType)) continue;
            int from = q - patternStart[p];
            boolean reaches = false;
            for (int j = from + 1; j < pattern.stops.length; j++) {
                if (pattern.stops[j] == alightStop && (pattern.flags == null || (pattern.flags[j] & 2) == 0)) {
                    reaches = true;
                    break;
                }
            }
            if (!reaches) continue;
            trips += pattern.trips[dayType];
            int offset = Math.round(pattern.cumMinutes[from]);
            first = Math.min(first, pattern.firstDeparture + offset);
            last = Math.max(last, pattern.lastDeparture + offset);
            if (!pattern.routeName.equals(chosen.routeName) && others.size() < 4) others.add(pattern.routeName);
        }
        if (trips == 0) {       // 있을 수 없지만, 고른 노선만으로 채운다
            trips = chosen.trips[dayType];
            int offset = Math.round(chosen.cumMinutes[chosenFrom]);
            first = chosen.firstDeparture + offset;
            last = chosen.lastDeparture + offset;
        }
        ride.tripsPerDay = trips;
        ride.firstDeparture = first;
        ride.lastDeparture = last;
        ride.headwayMinutes = (int) Math.round(headway(last - first, trips));
        ride.alternatives = new ArrayList<>(others);
    }

    /**
     * 요금.
     *  - GTFS 노선: 탈 때마다 그 구간 요금을 낸다 (요금표에 없으면 거리 기준 추정).
     *  - 철도망 구간: 이어서 탄 같은 요금 단위(JR 전체, 또는 한 운영사)끼리 거리를 합쳐 한 번만 낸다.
     *    중간에 다른 회사나 버스를 타면 요금이 끊긴다. 신칸센을 탄 구간에는 특급요금이 붙는다.
     *    요금은 등록된 요금 자료(역 쌍 요금 → 운영사 요금표)를 먼저 쓰고, 없으면 종류별 대표 요금표로 추정한다.
     */
    private void priceRoute(Route route) {
        priceRoute(route, fareBook);
    }

    static void priceRoute(Route route, FareBook book) {
        route.fareBlocks.clear();
        route.confirmedFareRides = 0;
        route.estimatedFareRides = 0;
        route.tableFareRides = 0;
        FareBlock open = null;      // 지금 이어지고 있는 철도망 묶음
        for (Step step : route.steps) {
            if (step.kind != Step.Kind.RIDE) continue;
            if (step.scheduled) {
                open = null;
                FareBlock block = new FareBlock();
                block.group = step.operator;
                block.scheduled = true;
                block.rides.add(step);
                block.fareYen = step.fareYen;
                block.childFareYen = RailFareModel.childFare(step.fareYen);
                block.basis = step.fareConfirmed ? FareBook.Basis.OFFICIAL : FareBook.Basis.ESTIMATE;
                block.fareKm = step.km;
                step.fareBlock = route.fareBlocks.size();
                route.fareBlocks.add(block);
                continue;
            }
            String group = RailFareModel.fareGroup(step.category, step.operator);
            if (open == null || !open.group.equals(group)) {
                open = new FareBlock();
                open.group = group;
                route.fareBlocks.add(open);
            }
            step.fareBlock = route.fareBlocks.size() - 1;
            open.rides.add(step);
        }

        int total = 0;
        int child = 0;
        for (FareBlock block : route.fareBlocks) {
            if (!block.scheduled) {
                FareBook.Quote quote = book.price(block.rides);
                block.fareYen = quote.fareYen;
                block.childFareYen = quote.childFareYen;
                block.surchargeYen = quote.surchargeYen;
                block.basis = quote.basis;
                block.tableKey = quote.tableKey;
                block.fareKm = quote.fareKm;
            }
            total += block.fareYen;
            child += block.childFareYen;
            if (block.basis == FareBook.Basis.OFFICIAL) {
                route.confirmedFareRides += block.rides.size();
            } else {
                route.estimatedFareRides += block.rides.size();
                if (block.basis == FareBook.Basis.TABLE) route.tableFareRides += block.rides.size();
            }
        }
        route.fareYen = total;
        route.childFareYen = child;
    }

    /** 철도망 구간의 요금 합 (GTFS 노선 구간은 세지 않고, 요금 묶음을 끊는 역할만 한다). 등록된 요금 자료 없이 추정한 값이다. */
    static int fareOf(List<Step> steps) {
        Route route = new Route();
        route.steps.addAll(steps);
        priceRoute(route, FareBook.EMPTY);
        int total = 0;
        for (FareBlock block : route.fareBlocks) {
            if (!block.scheduled) total += block.fareYen;
        }
        return total;
    }

    // =====================================================================
    // 격자 검색
    // =====================================================================

    private static long cell(double lat, double lng) {
        return (long) Math.floor(lat / CELL) * 100_000L + (long) Math.floor(lng / CELL);
    }

    /** 그 지점 주변 격자에 있는 번호들 (정확한 거리는 호출한 쪽에서 다시 잰다) */
    private static List<Integer> near(Map<Long, List<Integer>> index, double lat, double lng, double km) {
        int span = (int) Math.ceil(km / 0.9) + 1;        // 경도 0.01도 ≈ 0.9km (일본 위도 기준)
        List<Integer> result = new ArrayList<>();
        long baseLat = (long) Math.floor(lat / CELL);
        long baseLng = (long) Math.floor(lng / CELL);
        for (long dy = -span; dy <= span; dy++) {
            for (long dx = -span; dx <= span; dx++) {
                List<Integer> bucket = index.get((baseLat + dy) * 100_000L + (baseLng + dx));
                if (bucket != null) result.addAll(bucket);
            }
        }
        return result;
    }
}
