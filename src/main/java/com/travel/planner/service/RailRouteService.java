package com.travel.planner.service;

import com.travel.planner.dto.TransitLegDto;
import com.travel.planner.entity.TransitFeed;
import com.travel.planner.entity.TransitLink;
import com.travel.planner.entity.TransitPattern;
import com.travel.planner.entity.TransitStation;
import com.travel.planner.entity.TransitStop;
import com.travel.planner.repository.TransitFeedRepository;
import com.travel.planner.repository.TransitLinkRepository;
import com.travel.planner.repository.TransitPatternRepository;
import com.travel.planner.repository.TransitStationRepository;
import com.travel.planner.repository.TransitStopRepository;
import com.travel.planner.service.PlanService.SimulatedItinerary;
import com.travel.planner.transit.GtfsCodec;
import com.travel.planner.transit.GtfsFeed;
import com.travel.planner.transit.RailCategory;
import com.travel.planner.transit.RailGraph;
import com.travel.planner.transit.TransitLegPlanner;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 일정의 구간마다 "어떻게 가는지"(도보·철도·버스·노선 미확인)와 요금을 붙인다.
 *
 * 교통망은 DB 에서 한 번 읽어 메모리에 올려 두고 쓴다.
 *   - 전국 철도망: transit_station, transit_link (역 약 1만 개)
 *   - GTFS 로 가져온 버스·노면전차·배: transit_stop, transit_pattern
 * 아직 아무것도 가져오지 않았으면 경로 없이 거리 기준 추정만 한다.
 * 일정 엔진의 시간 계산은 건드리지 않는다: 여기서 구한 값은 안내·예산용이다.
 */
@Service
@RequiredArgsConstructor
public class RailRouteService {

    private final TransitStationRepository stationRepository;
    private final TransitLinkRepository linkRepository;
    private final TransitStopRepository stopRepository;
    private final TransitPatternRepository patternRepository;
    private final TransitFeedRepository feedRepository;
    private final TransitNameService names;

    private volatile RailGraph graph;
    private volatile boolean loaded;

    /** 경로 안내에 쓰는 이름 변환기 (번역 표가 바뀌면 바로 반영되도록 매번 서비스에 묻는다) */
    private final TransitLegPlanner.Names nameLookup = new TransitLegPlanner.Names() {
        @Override public String station(String ja) { return names.station(ja); }
        @Override public String line(String ja) { return names.line(ja); }
        @Override public String operator(String ja) { return names.operator(ja); }
    };

    /** 철도망을 다시 가져온 뒤 호출: 다음 요청 때 DB 에서 새로 읽는다 */
    public synchronized void invalidate() {
        loaded = false;
        graph = null;
    }

    /** 교통망이 준비되어 있는가 (철도망이나 GTFS 피드를 한 번이라도 가져왔는가) */
    public boolean isNetworkLoaded() {
        return graph() != null;
    }

    /** 전국 철도망을 가져온 상태인가 */
    public boolean isRailLoaded() {
        return stationCount() > 0;
    }

    public int stopCount() {
        RailGraph g = graph();
        return g == null ? 0 : g.stopCount();
    }

    public int patternCount() {
        RailGraph g = graph();
        return g == null ? 0 : g.patternCount();
    }

    public int stationCount() {
        RailGraph g = graph();
        return g == null ? 0 : g.stationCount();
    }

    public int linkCount() {
        RailGraph g = graph();
        return g == null ? 0 : g.rideLinkCount();
    }

    private RailGraph graph() {
        if (loaded) return graph;
        synchronized (this) {
            if (loaded) return graph;
            try {
                long started = System.currentTimeMillis();
                List<RailGraph.Station> nodes = new ArrayList<>();
                List<double[]> links = new ArrayList<>();
                loadRail(nodes, links);
                List<GtfsFeed.Stop> stops = new ArrayList<>();
                List<GtfsFeed.Pattern> patterns = new ArrayList<>();
                loadGtfs(stops, patterns);
                if (nodes.isEmpty() && stops.isEmpty()) {
                    graph = null;
                } else {
                    graph = new RailGraph(nodes, links, stops, patterns);
                    System.out.println("[교통] 교통망을 메모리에 올렸습니다: 역 " + graph.stationCount() + "개, 역간 연결 " + graph.rideLinkCount()
                            + "개, 환승 " + graph.transferLinkCount() + "개 / 정류장 " + graph.stopCount() + "개, 노선 패턴 " + graph.patternCount()
                            + "개" + (graph.supersededLineCount() > 0 ? " (GTFS 노선과 겹치는 철도망 노선 " + graph.supersededLineCount() + "개는 뺌)" : "")
                            + " (" + (System.currentTimeMillis() - started) + "ms)");
                }
            } catch (Exception e) {
                // 테이블이 아직 없거나 DB 오류: 교통 정보 없이도 일정 생성은 계속되어야 한다
                System.out.println("[교통] 교통망을 읽지 못해 거리 기준 추정만 사용합니다: " + e.getMessage());
                graph = null;
            }
            loaded = true;
            return graph;
        }
    }

    private void loadRail(List<RailGraph.Station> nodes, List<double[]> links) {
        List<TransitStation> stations = new ArrayList<>(stationRepository.findAll());
        if (stations.isEmpty()) return;
        stations.sort(Comparator.comparing(TransitStation::getId));
        Map<Long, Integer> indexById = new HashMap<>();
        for (TransitStation s : stations) {
            int index = nodes.size();
            indexById.put(s.getId(), index);
            nodes.add(new RailGraph.Station(index, s.getName(), s.getOperator(), s.getLine(), s.getGroupCode(),
                    category(s.getCategory()), s.getLatitude(), s.getLongitude()));
        }
        for (TransitLink l : linkRepository.findAll()) {
            Integer a = indexById.get(l.getFromStationId());
            Integer b = indexById.get(l.getToStationId());
            if (a != null && b != null && l.getDistanceKm() != null) links.add(new double[]{a, b, l.getDistanceKm()});
        }
    }

    /** GTFS 피드는 철도망과 따로 읽는다: 한쪽 테이블에 문제가 있어도 다른 쪽은 쓸 수 있게 한다. */
    private void loadGtfs(List<GtfsFeed.Stop> stops, List<GtfsFeed.Pattern> patterns) {
        try {
            List<TransitStop> rows = new ArrayList<>(stopRepository.findAll());
            if (rows.isEmpty()) return;
            rows.sort(Comparator.comparing(TransitStop::getId));
            Map<Long, Integer> indexById = new HashMap<>();
            for (TransitStop row : rows) {
                if (row.getLatitude() == null || row.getLongitude() == null) continue;
                indexById.put(row.getId(), stops.size());
                stops.add(GtfsCodec.toStop(row, stops.size()));
            }
            // 유효 기간이 지난 피드의 노선은 날짜별 운행 기간을 따지지 않고, 안내에 "기간 지남"을 붙인다
            LocalDate today = LocalDate.now(ZoneId.of("Asia/Tokyo"));
            Map<String, Boolean> expired = new HashMap<>();
            for (TransitFeed feed : feedRepository.findAll()) {
                boolean past = TransitFeed.EXPIRED.equals(feed.getStatus()) || (feed.getValidTo() != null && feed.getValidTo().isBefore(today));
                expired.put(feed.getFeedKey(), past);
            }
            int broken = 0;
            for (TransitPattern row : patternRepository.findAll()) {
                GtfsFeed.Pattern pattern = GtfsCodec.toPattern(row, indexById, Boolean.TRUE.equals(expired.get(row.getFeedKey())));
                if (pattern == null) broken++;
                else patterns.add(pattern);
            }
            if (broken > 0) System.out.println("[교통] 읽을 수 없는 노선 패턴 " + broken + "개를 건너뛰었습니다. 해당 피드를 다시 가져와 주세요.");
        } catch (Exception e) {
            System.out.println("[교통] GTFS 노선을 읽지 못해 철도망만 사용합니다: " + e.getMessage());
            stops.clear();
            patterns.clear();
        }
    }

    private static RailCategory category(String name) {
        try {
            return RailCategory.valueOf(name);
        } catch (Exception e) {
            return RailCategory.PRIVATE;
        }
    }

    /** 두 지점 사이의 이동 방법 (날짜·출발 시각을 모를 때: 평일 운행 기준). 같은 자리면 null. */
    public TransitLegDto leg(double fromLat, double fromLng, String fromName, double toLat, double toLng, String toName,
                             String transportation, int scheduleMinutes) {
        return leg(fromLat, fromLng, fromName, toLat, toLng, toName, transportation, scheduleMinutes, null, null);
    }

    /**
     * 두 지점 사이의 이동 방법. 같은 자리면 null.
     * @param date         이동하는 날 (요일별 운행 횟수·계절 노선 판단). 모르면 null
     * @param departMinute 출발 시각(0시부터의 분). 모르면 null
     */
    public TransitLegDto leg(double fromLat, double fromLng, String fromName, double toLat, double toLng, String toName,
                             String transportation, int scheduleMinutes, LocalDate date, Integer departMinute) {
        try {
            return TransitLegPlanner.plan(graph(), nameLookup, fromLat, fromLng, fromName, toLat, toLng, toName,
                    transportation == null ? "대중교통" : transportation, scheduleMinutes, date, departMinute);
        } catch (Exception e) {
            System.out.println("[교통] 구간 계산 실패(" + fromName + " → " + toName + "): " + e.getMessage());
            return null;
        }
    }

    /** 날짜를 모를 때의 하루 구간 계산 (평일 운행 기준) */
    public List<TransitLegDto> legsFor(List<SimulatedItinerary> items, String transportation) {
        return legsFor(items, transportation, null);
    }

    /**
     * 하루 일정의 줄마다 "직전 장소에서 여기까지"의 이동 방법을 구한다. 결과는 items 와 같은 길이이며, 이동이 없는 줄은 null.
     * 자유 시간·자유 식사처럼 위치가 없는 줄은 건너뛰고, 그다음 장소는 그 전의 실제 위치에서 출발한 것으로 본다.
     * @param date 그 날의 날짜 (요일에 맞는 버스 운행 횟수를 쓴다). 모르면 null
     */
    public List<TransitLegDto> legsFor(List<SimulatedItinerary> items, String transportation, LocalDate date) {
        List<TransitLegDto> legs = new ArrayList<>(items.size());
        Double lat = null;
        Double lng = null;
        String name = null;
        for (SimulatedItinerary item : items) {
            TransitLegDto leg = null;
            boolean fixedWithPlace = item.getType() == SimulatedItinerary.Type.FIXED && item.getTravelMinutes() > 0;
            boolean located = (item.getPlace() != null || fixedWithPlace) && valid(item.getLatitude(), item.getLongitude());
            if (located) {
                if (lat != null) {
                    // 이 장소에 도착하는 시각에서 이동 시간을 뺀 때가 출발 시각이다
                    int depart = Math.max(0, item.getStartMin() - item.getTravelMinutes());
                    leg = leg(lat, lng, name, item.getLatitude(), item.getLongitude(), item.getDisplayName(), transportation,
                            item.getTravelMinutes(), date, depart);
                }
                lat = item.getLatitude();
                lng = item.getLongitude();
                name = item.getDisplayName();
            }
            legs.add(leg);
        }
        return legs;
    }

    private static boolean valid(Double lat, Double lng) {
        return lat != null && lng != null && !(lat == 0.0 && lng == 0.0);
    }
}
