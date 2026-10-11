package com.travel.planner.transit;

import com.travel.planner.dto.TransitLegDto;
import com.travel.planner.util.DistanceUtil;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 두 지점 사이를 "어떻게 가는지" 정한다: 도보 / 철도(전국 철도망 계산) / 노선 미확인(거리 기준) / 자동차.
 * DB·스프링과 무관한 순수 계산이다.
 */
public final class TransitLegPlanner {

    private TransitLegPlanner() {}

    /** 이 거리(km) 이하는 걸어간다 */
    static final double WALK_KM = 1.0;

    /** 일본어 이름 → 한국어 표기 */
    public interface Names {
        String station(String ja);
        String line(String ja);
        String operator(String ja);
    }

    /** 번역 없이 원문 그대로 쓰는 기본 구현 */
    public static final Names ORIGINAL = new Names() {
        @Override public String station(String ja) { return ja; }
        @Override public String line(String ja) { return ja; }
        @Override public String operator(String ja) { return ja; }
    };

    /**
     * @param graph           전국 철도망. 아직 가져오지 않았으면 null (철도 경로 없이 거리 기준으로만 추정)
     * @param scheduleMinutes 일정 엔진이 이 구간에 잡아 둔 이동 시간(분)
     * @return 같은 자리(50m 이내)면 null
     */
    public static TransitLegDto plan(RailGraph graph, Names names, double fromLat, double fromLng, String fromName,
                                     double toLat, double toLng, String toName, String transportation, int scheduleMinutes) {
        double km = DistanceUtil.calculateDistance(fromLat, fromLng, toLat, toLng);
        if (km < 0.05) return null;

        String mode = transportation == null ? "" : transportation;
        boolean car = mode.contains("렌트") || mode.contains("자동차") || mode.contains("택시") || mode.toLowerCase().contains("car");
        boolean transit = mode.contains("대중교통") || mode.contains("지하철") || mode.contains("버스") || mode.contains("전철") || mode.contains("기차");
        boolean walkOnly = mode.contains("도보") && !transit && !car;
        int walkMinutes = Math.max(1, (int) Math.ceil(km * RailGraph.WALK_MIN_PER_KM));

        TransitLegDto leg = new TransitLegDto();
        leg.setEstimated(true);
        leg.setDistanceKm(Math.round(km * 10.0) / 10.0);

        if (car && km > 0.3) {
            leg.setMode("자동차");
            leg.setMinutes(scheduleMinutes);
            leg.setSource("distance");
            leg.setSummary("자동차로 약 " + scheduleMinutes + "분");
            leg.setNote("주차비·유류비·통행료는 포함하지 않았습니다.");
            return leg;
        }
        if (km <= WALK_KM || walkOnly) return walk(leg, fromName, toName, walkMinutes, km);

        RailGraph.Route route = graph == null ? null : graph.route(fromLat, fromLng, toLat, toLng);
        // 철도 경로를 쓰지 않는 경우:
        //  - 걷는 것보다 느리다
        //  - 역까지·역에서 걷는 거리가 전체 직선거리의 절반을 넘는다 (역이 멀어 사실상 걸어가는 경로. 실제로는 버스를 탄다)
        //  - 일정이 잡아 둔 시간보다 훨씬 길다
        boolean useRail = route != null && route.minutes < walkMinutes
                && route.walkKm <= km * 0.5
                && route.minutes <= scheduleMinutes * 1.6 + 20;
        if (useRail) return rail(leg, route, names, fromName, toName, scheduleMinutes);

        leg.setMode("버스 등");
        leg.setMinutes(scheduleMinutes);
        int fare = RailFareModel.unknownLocalFare(km);
        leg.setFareYen(fare);
        leg.setChildFareYen(RailFareModel.childFare(fare));
        leg.setSource("distance");
        leg.setSummary("버스 등 대중교통으로 약 " + scheduleMinutes + "분 (노선 미확인)");
        leg.setNote("이 구간은 맞는 철도 경로가 없어 버스 등으로 이동한다고 보고 거리 기준으로 추정했습니다. 실제 노선과 요금은 확인되지 않았습니다.");
        return leg;
    }

    private static TransitLegDto walk(TransitLegDto leg, String fromName, String toName, int minutes, double km) {
        leg.setMode("도보");
        leg.setMinutes(minutes);
        leg.setFareYen(0);
        leg.setChildFareYen(0);
        leg.setSource("walk");
        leg.setSummary("도보 약 " + minutes + "분");
        TransitLegDto.Step step = new TransitLegDto.Step();
        step.setType("도보");
        step.setFrom(fromName);
        step.setTo(toName);
        step.setMinutes(minutes);
        step.setKm(Math.round(km * 10.0) / 10.0);
        leg.getSteps().add(step);
        return leg;
    }

    private static TransitLegDto rail(TransitLegDto leg, RailGraph.Route route, Names names, String fromName, String toName,
                                      int scheduleMinutes) {
        Set<String> categories = new LinkedHashSet<>();
        List<String> rides = new ArrayList<>();
        for (RailGraph.Step s : route.steps) {
            TransitLegDto.Step step = new TransitLegDto.Step();
            step.setMinutes(s.minutes);
            step.setKm(Math.round(s.km * 10.0) / 10.0);
            switch (s.kind) {
                case WALK:
                    step.setType("도보");
                    step.setFrom(s.from == null ? fromName : stationName(names, s.from));
                    step.setTo(s.to == null ? toName : stationName(names, s.to));
                    break;
                case TRANSFER:
                    step.setType("환승");
                    step.setFrom(stationName(names, s.from));
                    step.setTo(stationName(names, s.to));
                    break;
                default:
                    step.setType("승차");
                    step.setCategory(s.category.label);
                    step.setOperator(names.operator(s.operator));
                    step.setLine(names.line(s.line));
                    step.setFrom(stationName(names, s.from));
                    step.setTo(stationName(names, s.to));
                    step.setStops(s.stops);
                    categories.add(s.category.label);
                    rides.add(lineLabel(step) + " " + step.getFrom() + " → " + step.getTo() + " (" + s.stops + "정거장)");
                    break;
            }
            leg.getSteps().add(step);
        }
        leg.setMode(String.join("·", categories));
        leg.setMinutes(route.minutes);
        leg.setFareYen(route.fareYen);
        leg.setChildFareYen(route.childFareYen);
        leg.setTransfers(route.transfers);
        leg.setSource("rail-network");
        leg.setSummary(String.join(" / 환승 / ", rides));
        String note = "전국 철도망 기준 추정입니다. 열차 시각·급행 운행·운영사별 실제 요금표는 반영하지 않았습니다.";
        if (route.minutes > scheduleMinutes + 10) {
            note += " 이 경로의 추정 시간(" + route.minutes + "분)이 일정의 이동 시간(" + scheduleMinutes + "분)보다 깁니다.";
        }
        leg.setNote(note);
        return leg;
    }

    /** "JR 동일본 야마노테선"처럼 운영 회사와 노선을 붙인다. 노선명에 회사 이름이 이미 들어 있으면 한 번만 쓴다. */
    private static String lineLabel(TransitLegDto.Step step) {
        String operator = step.getOperator() == null ? "" : step.getOperator();
        String line = step.getLine() == null ? "" : step.getLine();
        if (operator.isEmpty() || line.contains(operator)) return line;
        return operator + " " + line;
    }

    static String stationName(Names names, String ja) {
        String name = names.station(ja);
        if (name == null || name.isBlank()) name = ja;
        return name.endsWith("역") || name.endsWith("駅") || name.endsWith("정류장") ? name : name + "역";
    }
}
