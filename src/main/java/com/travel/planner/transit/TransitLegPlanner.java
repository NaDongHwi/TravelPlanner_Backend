package com.travel.planner.transit;

import com.travel.planner.dto.TransitLegDto;
import com.travel.planner.util.DistanceUtil;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 두 지점 사이를 "어떻게 가는지" 정한다: 도보 / 대중교통(철도망 + GTFS 노선 계산) / 노선 미확인(거리 기준) / 자동차.
 * DB·스프링과 무관한 순수 계산이다.
 */
public final class TransitLegPlanner {

    private TransitLegPlanner() {}

    /** 이 거리(km) 이하는 걸어간다 */
    static final double WALK_KM = 1.0;
    /** 타는 곳·내리는 곳까지 걷는 거리가 전체 직선거리에서 차지할 수 있는 최대 비율 */
    static final double MAX_WALK_SHARE = 0.5;
    /** 걷는 것보다 빠른 경로가 없을 때, 걷는 시간이 이 분 이하이면 걸어가라고 안내한다 (그보다 멀면 거리 기준 추정으로 남긴다) */
    static final int WALK_INSTEAD_MINUTES = 30;
    /** 이 거리(km) 안에 정류장이 있으면 그곳은 버스 등의 노선 자료가 있는 곳으로 본다 */
    static final double COVERED_KM = 0.6;
    /** 하루 운행이 이 횟수 이하이면 "시간표를 꼭 확인하라"고 알린다 */
    static final int FEW_TRIPS = 12;

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

    /** 날짜·출발 시각을 모를 때 (평일 운행 기준으로 계산한다) */
    public static TransitLegDto plan(RailGraph graph, Names names, double fromLat, double fromLng, String fromName,
                                     double toLat, double toLng, String toName, String transportation, int scheduleMinutes) {
        return plan(graph, names, fromLat, fromLng, fromName, toLat, toLng, toName, transportation, scheduleMinutes, null, null);
    }

    /**
     * @param graph           교통망. 아직 가져온 것이 없으면 null (경로 없이 거리 기준으로만 추정)
     * @param scheduleMinutes 일정 엔진이 이 구간에 잡아 둔 이동 시간(분)
     * @param date            이동하는 날 (요일별 운행 횟수·계절 노선 판단). 모르면 null
     * @param departMinute    출발 시각(0시부터의 분). 첫차·막차 밖인지 알리는 데 쓴다. 모르면 null
     * @return 같은 자리(50m 이내)면 null
     */
    public static TransitLegDto plan(RailGraph graph, Names names, double fromLat, double fromLng, String fromName,
                                     double toLat, double toLng, String toName, String transportation, int scheduleMinutes,
                                     LocalDate date, Integer departMinute) {
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

        // 타는 곳·내리는 곳까지 걷는 거리가 전체 직선거리의 절반을 넘는 경로는 찾지 않는다 (타는 곳이 멀어 사실상 걸어가는 경로).
        // 그런 경로를 빼면, 역은 멀지만 바로 앞에 서는 버스 같은 다른 경로가 나온다.
        double maxWalkKm = km * MAX_WALK_SHARE;
        RailGraph.Route route = graph == null ? null : graph.route(fromLat, fromLng, toLat, toLng, date, departMinute, maxWalkKm);
        // 찾은 경로를 쓰지 않는 경우:
        //  - 걷는 것보다 느리다
        //  - 걷는 거리가 위 상한을 넘는다 (걸어서 갈아탄 끝에 실제로 타고 내리는 곳이 더 먼 경우)
        //  - 일정이 잡아 둔 시간보다 훨씬 길다
        boolean useRoute = route != null && route.minutes < walkMinutes
                && route.walkKm <= maxWalkKm
                && route.minutes <= scheduleMinutes * 1.6 + 20;
        if (useRoute) return transit(leg, route, names, fromName, toName, scheduleMinutes);

        // 양쪽 모두 버스 등의 노선 자료가 있는 곳인데 걷는 것보다 빨리 가는 경로가 없다면(배차 간격이 길거나 노선이 맞지 않는다),
        // 걸을 만한 거리에서는 걸어가라고 안내한다. 노선 자료가 없는 곳에서는 알 수 없으므로 아래의 거리 기준 추정으로 남긴다.
        if (graph != null && walkMinutes <= WALK_INSTEAD_MINUTES
                && graph.hasStopNear(fromLat, fromLng, COVERED_KM) && graph.hasStopNear(toLat, toLng, COVERED_KM)) {
            walk(leg, fromName, toName, walkMinutes, km);
            String note = "가져온 노선 자료에는 이 구간을 걷는 것보다 빨리 가는 대중교통이 없어 도보로 안내합니다.";
            if (walkMinutes > scheduleMinutes + 10) {
                note += " 걷는 시간(" + walkMinutes + "분)이 일정의 이동 시간(" + scheduleMinutes + "분)보다 깁니다.";
            }
            leg.setNote(note);
            return leg;
        }

        leg.setMode("버스 등");
        leg.setMinutes(scheduleMinutes);
        int fare = RailFareModel.unknownLocalFare(km);
        leg.setFareYen(fare);
        leg.setChildFareYen(RailFareModel.childFare(fare));
        leg.setSource("distance");
        leg.setFareSource("estimate");
        leg.setSummary("버스 등 대중교통으로 약 " + scheduleMinutes + "분 (노선 미확인)");
        leg.setNote("이 구간은 가져온 노선 자료에서 맞는 경로를 찾지 못해 버스 등으로 이동한다고 보고 거리 기준으로 추정했습니다. 실제 노선과 요금은 확인되지 않았습니다.");
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

    private static TransitLegDto transit(TransitLegDto leg, RailGraph.Route route, Names names, String fromName, String toName,
                                         int scheduleMinutes) {
        Set<String> categories = new LinkedHashSet<>();
        List<String> rides = new ArrayList<>();
        List<String> cautions = new ArrayList<>();
        int scheduledRides = 0;
        int networkRides = 0;
        for (RailGraph.Step s : route.steps) {
            TransitLegDto.Step step = new TransitLegDto.Step();
            step.setMinutes(s.minutes);
            step.setKm(Math.round(s.km * 10.0) / 10.0);
            String from = s.from == null ? fromName : placeName(names, s.from, s.fromKo, s.fromIsStop);
            String to = s.to == null ? toName : placeName(names, s.to, s.toKo, s.toIsStop);
            step.setFrom(from);
            step.setTo(to);
            switch (s.kind) {
                case WALK:
                    step.setType("도보");
                    break;
                case WAIT:
                    step.setType("대기");
                    step.setKm(null);
                    break;
                case TRANSFER:
                    step.setType("환승");
                    break;
                default:
                    step.setType("승차");
                    step.setCategory(s.category.label);
                    step.setOperator(names.operator(s.operator));
                    step.setLine(names.line(s.line));
                    step.setStops(s.stops);
                    categories.add(s.category.label);
                    if (s.scheduled) {
                        scheduledRides++;
                        fillScheduled(step, s);
                        rides.add(scheduledLabel(step) + " " + from + " → " + to + " (" + s.stops + "정거장, "
                                + (s.fareConfirmed ? "" : "약 ") + String.format("%,d엔", s.fareYen) + ")");
                        addCautions(cautions, step, s);
                    } else {
                        networkRides++;
                        rides.add(lineLabel(step) + " " + from + " → " + to + " (" + s.stops + "정거장)");
                        fillNetworkFare(step, s, route, names);
                    }
                    break;
            }
            leg.getSteps().add(step);
        }
        leg.setFareRides(PassAdvisor.ridesOf(route));
        leg.setMode(String.join("·", categories));
        leg.setMinutes(route.minutes);
        leg.setFareYen(route.fareYen);
        leg.setChildFareYen(route.childFareYen);
        leg.setTransfers(route.transfers);
        leg.setSource(scheduledRides == 0 ? "rail-network" : networkRides == 0 ? "gtfs" : "rail-network+gtfs");
        leg.setFareSource(fareSource(route));
        leg.setSummary(String.join(" / 환승 / ", rides));

        StringBuilder note = new StringBuilder();
        if (networkRides > 0) note.append(networkNote(route));
        if (scheduledRides > 0) {
            if (note.length() > 0) note.append(' ');
            note.append("버스 등은 운영사가 공개한 시각표 자료(GTFS)의 평균 소요 시간과 운행 간격으로 계산했습니다. 실제 출발 시각은 현지 시간표로 확인해 주세요.");
            int listed = 0;      // 요금표에 요금이 있는 버스 등의 승차 수
            int unlisted = 0;
            for (RailGraph.Step s : route.steps) {
                if (s.kind != RailGraph.Step.Kind.RIDE || !s.scheduled) continue;
                if (s.fareConfirmed) listed++;
                else unlisted++;
            }
            if (listed > 0) {
                note.append(unlisted == 0
                        ? " 버스 요금은 운영사 요금표(현금 성인) 기준입니다."
                        : " 요금표에 없는 구간의 요금은 거리 기준 추정입니다.");
            } else {
                note.append(" 이 노선은 요금표가 공개되어 있지 않아 요금은 거리 기준 추정입니다.");
            }
        }
        for (String caution : cautions) note.append(' ').append(caution);
        if (route.minutes > scheduleMinutes + 10) {
            note.append(" 이 경로의 추정 시간(").append(route.minutes).append("분)이 일정의 이동 시간(").append(scheduleMinutes).append("분)보다 깁니다.");
        }
        leg.setNote(note.toString());
        return leg;
    }

    /**
     * 구간 전체 요금의 근거.
     *  official: 모든 요금이 운영사가 정한 값 / table: 추정 없이 운영사 요금표(+계산 거리)로 구함
     *  estimate: 전부 추정 / mixed: 추정이 일부 섞임
     */
    static String fareSource(RailGraph.Route route) {
        int official = 0;
        int table = 0;
        int estimate = 0;
        for (RailGraph.FareBlock block : route.fareBlocks) {
            if (block.basis == FareBook.Basis.OFFICIAL) official++;
            else if (block.basis == FareBook.Basis.TABLE) table++;
            else estimate++;
        }
        if (estimate == 0) return table == 0 ? "official" : "table";
        return official + table == 0 ? "estimate" : "mixed";
    }

    /** 철도망 구간의 요금을 어떻게 구했는지 설명하는 문장 */
    private static String networkNote(RailGraph.Route route) {
        boolean table = false;
        boolean official = false;
        boolean estimate = false;
        boolean shinkansen = false;
        for (RailGraph.FareBlock block : route.fareBlocks) {
            if (block.scheduled) continue;
            if (block.surchargeYen > 0) shinkansen = true;
            if (block.tableKey != null) table = true;
            else if (block.basis == FareBook.Basis.OFFICIAL) official = true;
            else estimate = true;
        }
        if (!table && !official) {
            // 등록된 요금 자료가 없는 운영사만 탔다
            return "철도 구간은 전국 철도망 기준 추정입니다. 열차 시각·급행 운행·운영사별 실제 요금표는 반영하지 않았습니다.";
        }
        StringBuilder note = new StringBuilder("철도 구간의 시간은 전국 철도망 기준 추정이며 열차 시각·급행 운행은 반영하지 않았습니다.");
        if (table) note.append(" 철도 요금은 운영사 요금표에 철도망에서 잰 거리를 넣어 계산했습니다(현금·승차권 기준, 요금 구간의 경계 근처에서는 한 단계 다를 수 있습니다).");
        if (official) note.append(" 등록해 둔 역 사이 확정 요금을 쓴 구간이 있습니다.");
        if (estimate) note.append(" 요금표가 등록되지 않은 운영사의 요금은 종류별 대표 요금표로 추정했습니다.");
        if (shinkansen) note.append(" 신칸센은 자유석 기준이며, 특급요금은 확정 요금이 등록된 구간이 아니면 거리 구간별 추정입니다.");
        return note.toString();
    }

    /** 철도망 승차 단계에 요금을 적는다. 같은 회사를 이어 타면 표 한 장이므로 묶음의 첫 승차에만 요금이 들어간다. */
    private static void fillNetworkFare(TransitLegDto.Step step, RailGraph.Step s, RailGraph.Route route, Names names) {
        if (s.fareBlock < 0 || s.fareBlock >= route.fareBlocks.size()) return;
        RailGraph.FareBlock block = route.fareBlocks.get(s.fareBlock);
        if (block.rides.isEmpty() || block.rides.get(0) != s) return;
        step.setFareYen(block.fareYen);
        step.setFareBasis(block.basis.code);
        step.setFareConfirmed(block.basis == FareBook.Basis.OFFICIAL);
        if (block.surchargeYen > 0) step.setSurchargeYen(block.surchargeYen);
        if (block.rides.size() > 1) {
            RailGraph.Step last = block.rides.get(block.rides.size() - 1);
            if (last.to != null) step.setFareThroughTo(stationName(names, last.to));
        }
    }

    private static void fillScheduled(TransitLegDto.Step step, RailGraph.Step s) {
        step.setFromOriginal(s.from);
        step.setToOriginal(s.to);
        step.setHeadsign(s.headsign);
        step.setFareYen(s.fareYen);
        step.setFareConfirmed(s.fareConfirmed);
        step.setFareBasis(s.fareConfirmed ? FareBook.Basis.OFFICIAL.code : FareBook.Basis.ESTIMATE.code);
        step.setTripsPerDay(s.tripsPerDay);
        step.setHeadwayMinutes(s.headwayMinutes);
        step.setFirstDeparture(clock(s.firstDeparture));
        step.setLastDeparture(clock(s.lastDeparture));
        if (s.alternatives != null && !s.alternatives.isEmpty()) step.setAlternatives(new ArrayList<>(s.alternatives));
    }

    private static void addCautions(List<String> cautions, TransitLegDto.Step step, RailGraph.Step s) {
        String label = scheduledLabel(step);
        String hours = "첫차 " + step.getFirstDeparture() + " · 막차 " + step.getLastDeparture();
        if (s.outsideServiceHours) {
            cautions.add(label + "는 이 시간에 운행하지 않을 수 있습니다(" + hours + ").");
        } else if (s.tripsPerDay <= FEW_TRIPS) {
            cautions.add(label + "는 하루 " + s.tripsPerDay + "회(약 " + s.headwayMinutes + "분 간격)만 다닙니다. " + hours + " — 시간표를 꼭 확인해 주세요.");
        }
        if (s.irregular) cautions.add(label + "는 매일 다니지 않는 노선입니다(운행일 확인 필요).");
        if (s.outdated) cautions.add(label + "의 시각표 자료는 유효 기간이 지났습니다. 지금은 달라졌을 수 있습니다.");
    }

    /** "버스 42" 처럼 종류와 노선을 붙인다. 노선명에 종류가 이미 들어 있으면 한 번만 쓴다. */
    private static String scheduledLabel(TransitLegDto.Step step) {
        String category = step.getCategory() == null ? "" : step.getCategory();
        String line = step.getLine() == null ? "" : step.getLine();
        if (line.isEmpty()) return category;
        return line.contains(category) || line.contains("バス") && "버스".equals(category) ? line : category + " " + line;
    }

    /** "JR 동일본 야마노테선"처럼 운영 회사와 노선을 붙인다. 노선명에 회사 이름이 이미 들어 있으면 한 번만 쓴다. */
    private static String lineLabel(TransitLegDto.Step step) {
        String operator = step.getOperator() == null ? "" : step.getOperator();
        String line = step.getLine() == null ? "" : step.getLine();
        if (operator.isEmpty() || line.contains(operator)) return line;
        return operator + " " + line;
    }

    /** 역은 "신주쿠역", 정류장은 이름 그대로 */
    private static String placeName(Names names, String ja, String korean, boolean stop) {
        if (!stop) return stationName(names, ja);
        if (korean != null && !korean.isBlank()) return korean;
        String name = names.station(ja);
        return name == null || name.isBlank() ? ja : name;
    }

    static String stationName(Names names, String ja) {
        String name = names.station(ja);
        if (name == null || name.isBlank()) name = ja;
        return name.endsWith("역") || name.endsWith("駅") || name.endsWith("정류장") ? name : name + "역";
    }

    /** 0시부터의 분 → "06:35". 24시를 넘는 심야 운행은 "24:10" 처럼 그대로 적는다 */
    static String clock(int minutes) {
        if (minutes < 0) minutes = 0;
        return String.format("%02d:%02d", minutes / 60, minutes % 60);
    }
}
