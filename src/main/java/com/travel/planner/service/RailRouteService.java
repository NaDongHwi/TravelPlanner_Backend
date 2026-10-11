package com.travel.planner.service;

import com.travel.planner.dto.TransitLegDto;
import com.travel.planner.entity.TransitLink;
import com.travel.planner.entity.TransitStation;
import com.travel.planner.repository.TransitLinkRepository;
import com.travel.planner.repository.TransitStationRepository;
import com.travel.planner.service.PlanService.SimulatedItinerary;
import com.travel.planner.transit.RailCategory;
import com.travel.planner.transit.RailGraph;
import com.travel.planner.transit.TransitLegPlanner;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 일정의 구간마다 "어떻게 가는지"(도보·철도·노선 미확인)와 요금을 붙인다.
 *
 * 전국 철도망은 DB(transit_station, transit_link)에서 한 번 읽어 메모리에 올려 두고 쓴다(역 약 1만 개).
 * 아직 철도망을 가져오지 않았으면 철도 경로 없이 거리 기준 추정만 한다.
 * 일정 엔진의 시간 계산은 건드리지 않는다: 여기서 구한 값은 안내·예산용이다.
 */
@Service
@RequiredArgsConstructor
public class RailRouteService {

    private final TransitStationRepository stationRepository;
    private final TransitLinkRepository linkRepository;
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

    /** 철도망이 준비되어 있는가 (가져오기를 한 번이라도 했는가) */
    public boolean isNetworkLoaded() {
        return graph() != null;
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
                List<TransitStation> stations = new ArrayList<>(stationRepository.findAll());
                if (stations.isEmpty()) {
                    graph = null;
                } else {
                    stations.sort(Comparator.comparing(TransitStation::getId));
                    Map<Long, Integer> indexById = new HashMap<>();
                    List<RailGraph.Station> nodes = new ArrayList<>();
                    for (TransitStation s : stations) {
                        int index = nodes.size();
                        indexById.put(s.getId(), index);
                        nodes.add(new RailGraph.Station(index, s.getName(), s.getOperator(), s.getLine(), s.getGroupCode(),
                                category(s.getCategory()), s.getLatitude(), s.getLongitude()));
                    }
                    List<double[]> links = new ArrayList<>();
                    for (TransitLink l : linkRepository.findAll()) {
                        Integer a = indexById.get(l.getFromStationId());
                        Integer b = indexById.get(l.getToStationId());
                        if (a != null && b != null && l.getDistanceKm() != null) links.add(new double[]{a, b, l.getDistanceKm()});
                    }
                    graph = new RailGraph(nodes, links);
                    System.out.println("[교통] 전국 철도망을 메모리에 올렸습니다: 역 " + graph.stationCount() + "개, 역간 연결 " + graph.rideLinkCount()
                            + "개, 환승 " + graph.transferLinkCount() + "개 (" + (System.currentTimeMillis() - started) + "ms)");
                }
            } catch (Exception e) {
                // 테이블이 아직 없거나 DB 오류: 교통 정보 없이도 일정 생성은 계속되어야 한다
                System.out.println("[교통] 철도망을 읽지 못해 거리 기준 추정만 사용합니다: " + e.getMessage());
                graph = null;
            }
            loaded = true;
            return graph;
        }
    }

    private static RailCategory category(String name) {
        try {
            return RailCategory.valueOf(name);
        } catch (Exception e) {
            return RailCategory.PRIVATE;
        }
    }

    /** 두 지점 사이의 이동 방법. 같은 자리면 null. */
    public TransitLegDto leg(double fromLat, double fromLng, String fromName, double toLat, double toLng, String toName,
                             String transportation, int scheduleMinutes) {
        try {
            return TransitLegPlanner.plan(graph(), nameLookup, fromLat, fromLng, fromName, toLat, toLng, toName,
                    transportation == null ? "대중교통" : transportation, scheduleMinutes);
        } catch (Exception e) {
            System.out.println("[교통] 구간 계산 실패(" + fromName + " → " + toName + "): " + e.getMessage());
            return null;
        }
    }

    /**
     * 하루 일정의 줄마다 "직전 장소에서 여기까지"의 이동 방법을 구한다. 결과는 items 와 같은 길이이며, 이동이 없는 줄은 null.
     * 자유 시간·자유 식사처럼 위치가 없는 줄은 건너뛰고, 그다음 장소는 그 전의 실제 위치에서 출발한 것으로 본다.
     */
    public List<TransitLegDto> legsFor(List<SimulatedItinerary> items, String transportation) {
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
                    leg = leg(lat, lng, name, item.getLatitude(), item.getLongitude(), item.getDisplayName(), transportation,
                            item.getTravelMinutes());
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
