package com.travel.planner.transit;

import com.travel.planner.dto.TransitLegDto;
import com.travel.planner.util.DistanceUtil;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 두 지점 사이를 "어떻게 가는지" 정한다: 도보 / 대중교통(철도망 + GTFS 노선 계산) / 버스(전국 버스 정류장 자료) / 택시(잇는 대중교통이 없을 때) /
 * 노선 미확인(노선 자료를 아직 하나도 가져오지 않았을 때, 거리 기준) / 자동차.
 * 등산하는 곳이 한쪽 끝이면 교통편은 등산 입구(가장 가까운 역·정류장)까지만 찾고 등산로 도보를 붙인다.
 * DB·스프링과 무관한 순수 계산이다.
 */
public final class TransitLegPlanner {

    private TransitLegPlanner() {}

    /** 이 거리(km) 이하는 걸어간다 */
    static final double WALK_KM = 1.0;
    /** 타는 곳·내리는 곳까지 걷는 거리가 전체 직선거리에서 차지할 수 있는 최대 비율 */
    static final double MAX_WALK_SHARE = 0.5;
    /**
     * 걷는 것보다 빠른 경로가 없을 때, 걷는 시간이 이 분 이하(직선 약 2km)이면 걸어가라고 안내한다.
     * 노선 자료가 없는 곳도 마찬가지다: 버스가 있을지 모르는 "노선 미확인"보다 확실히 갈 수 있는 도보가 낫다.
     * 그보다 멀면 거리 기준 추정(노선 미확인)으로 남긴다.
     */
    static final int WALK_INSTEAD_MINUTES = 35;
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
        return plan(graph, null, names, fromLat, fromLng, fromName, toLat, toLng, toName, transportation, scheduleMinutes, date, departMinute);
    }

    /** 등산하는 곳(산 정상 등)에서 등산로 입구로 보는 역·정류장을 찾는 거리(km) */
    static final double HIKE_ACCESS_KM = 4.0;
    /** 이 거리(km) 안의 역·정류장이면 "대중교통으로 가서 걸어 오르는 곳"으로 본다 (다카오산형) */
    static final double HIKE_TRANSIT_GATE_KM = 1.5;
    /** 등산로를 걷는 시간: 직선 1km 에 이 분 (길이 굽고 오르막이라 평지의 2.5배쯤) */
    static final int HIKE_MIN_PER_KM = 45;

    /** 구간 한쪽 끝이 등산하는 곳이나 후지산일 때의 정보 */
    public static final class Summit {
        final Double parkingLat;
        final Double parkingLng;
        final String parkingName;
        final boolean fuji;

        private Summit(Double parkingLat, Double parkingLng, String parkingName, boolean fuji) {
            this.parkingLat = parkingLat;
            this.parkingLng = parkingLng;
            this.parkingName = parkingName;
            this.fuji = fuji;
        }

        /** 등산하는 곳. 정상 근처 주차장을 알면 그 좌표·이름 (모르면 null) */
        public static Summit hike(Double parkingLat, Double parkingLng, String parkingName) {
            return new Summit(parkingLat, parkingLng, parkingName, false);
        }

        /** 후지산: 이 5합목까지 가서 둘러본다 */
        public static Summit fuji(double stationLat, double stationLng, String stationName) {
            return new Summit(stationLat, stationLng, stationName, true);
        }
    }

    /** 등산 입구 {위도, 경도, 정상까지 거리(km)} 와 이름, 종류("transit"/"parking"/"far") */
    private static final class Gate {
        double lat, lng, km;
        String name;
        String kind;
    }

    /** 이전 방식(true 면 주차장 정보 없는 등산하는 곳)과 같은 호출 */
    public static TransitLegDto plan(RailGraph graph, BusStopIndex buses, Names names, double fromLat, double fromLng, String fromName,
                                     double toLat, double toLng, String toName, String transportation, int scheduleMinutes,
                                     LocalDate date, Integer departMinute, boolean fromHike, boolean toHike) {
        return plan(graph, buses, names, fromLat, fromLng, fromName, toLat, toLng, toName, transportation, scheduleMinutes, date, departMinute,
                fromHike ? Summit.hike(null, null, null) : null, toHike ? Summit.hike(null, null, null) : null);
    }

    /**
     * 등산하는 곳이 한쪽 끝인 구간. 그곳은 걸어서 오르는 곳이므로, 교통편은 등산 입구까지만 찾고 그다음은 "등산로 도보" 단계로 붙인다.
     * 등산 입구: 1) 정상 1.5km 안의 역·정류장  2) 정상 근처 주차장(차·택시로 가는 곳)  3) 4km 안의 가장 가까운 역·정류장.
     * 등산 시간은 방문 시간에 들어 있으므로 이동 시간에 더하지 않는다.
     * 후지산은 5합목까지의 교통편을 안내한다 (정상 등반은 하루 일정에 넣지 않는다).
     */
    public static TransitLegDto plan(RailGraph graph, BusStopIndex buses, Names names, double fromLat, double fromLng, String fromName,
                                     double toLat, double toLng, String toName, String transportation, int scheduleMinutes,
                                     LocalDate date, Integer departMinute, Summit fromHike, Summit toHike) {
        TransitLegDto sea = sea(graph, buses, names, fromLat, fromLng, fromName, toLat, toLng, toName, transportation, scheduleMinutes,
                date, departMinute, fromHike, toHike);
        if (sea != null) return sea;
        String mode = transportation == null ? "" : transportation;
        boolean car = mode.contains("렌트") || mode.contains("자동차") || mode.toLowerCase().contains("car");
        if (fromHike == null && toHike == null) {
            return plan(graph, buses, names, fromLat, fromLng, fromName, toLat, toLng, toName, transportation, scheduleMinutes, date, departMinute);
        }
        // 렌터카: 등산하는 곳은 그대로 차로(주차장이 있으면 주차장까지). 후지산은 5합목까지.
        Gate fromGate = fromHike == null ? null : gate(graph, buses, names, fromLat, fromLng, fromHike, car);
        Gate toGate = toHike == null ? null : gate(graph, buses, names, toLat, toLng, toHike, car);
        if (car && fromGate == null && toGate == null) {
            return plan(graph, buses, names, fromLat, fromLng, fromName, toLat, toLng, toName, transportation, scheduleMinutes, date, departMinute);
        }

        double aLat = fromGate != null ? fromGate.lat : fromLat;
        double aLng = fromGate != null ? fromGate.lng : fromLng;
        double bLat = toGate != null ? toGate.lat : toLat;
        double bLng = toGate != null ? toGate.lng : toLng;
        TransitLegDto inner = plan(graph, buses, names, aLat, aLng, fromGate != null ? fromGate.name : fromName,
                bLat, bLng, toGate != null ? toGate.name : toName, transportation, scheduleMinutes, date, departMinute);
        TransitLegDto leg = inner;
        if (leg == null) {
            leg = new TransitLegDto();
            leg.setEstimated(true);
            leg.setMode("도보");
            leg.setMinutes(0);
            leg.setFareYen(0);
            leg.setChildFareYen(0);
            leg.setSource("walk");
            leg.setSummary("");
        }
        leg.setDistanceKm(Math.round(DistanceUtil.calculateDistance(fromLat, fromLng, toLat, toLng) * 10.0) / 10.0);

        List<String> parts = new ArrayList<>();
        StringBuilder note = new StringBuilder(leg.getNote() == null ? "" : leg.getNote());
        boolean hiking = false;
        boolean fuji = false;
        boolean far = false;
        if (fromGate != null) {
            if (fromHike.fuji) {
                fuji = true;
                parts.add(fromGate.name + "에서 출발");
            } else {
                hiking = true;
                far |= "far".equals(fromGate.kind);
                int down = hikeMinutes(fromGate.km);
                leg.getSteps().add(0, hikeStep(fromName, fromGate.name, down, fromGate.km, "하산"));
                parts.add("걸어서 내려와 " + fromGate.name + "까지 약 " + down + "분(방문 시간에 포함)");
            }
        }
        if (inner != null && inner.getSummary() != null && !inner.getSummary().isBlank()) parts.add(inner.getSummary());
        if (toGate != null) {
            if (toHike.fuji) {
                fuji = true;
                parts.add(toGate.name + " 도착");
            } else {
                hiking = true;
                far |= "far".equals(toGate.kind);
                int up = hikeMinutes(toGate.km);
                leg.getSteps().add(hikeStep(toGate.name, toName, up, toGate.km, "등산"));
                parts.add(toGate.name + "부터 등산로를 걸어 약 " + up + "분(방문 시간에 포함)");
            }
        }
        leg.setSummary(String.join(" → ", parts));
        if (note.length() > 0) note.append(' ');
        if (hiking) {
            leg.setMode(leg.getMode() + "·등산");
            boolean noGate = (fromHike != null && !fromHike.fuji && fromGate == null) || (toHike != null && !toHike.fuji && toGate == null);
            if (noGate) {
                note.append("등산하는 곳이라 걸어서 오르내립니다. 근처에 역·정류장·주차장을 찾지 못해 산 가까이까지의 이동만 안내했습니다.");
            } else if (far) {
                note.append("등산하는 곳이라 가장 가까운 역·정류장에서 걸어서 오르내리는 것으로 안내했습니다. 차로 가면 더 가까이까지 갈 수 있을 수 있습니다.");
            } else {
                note.append("등산하는 곳이라 등산 입구(역·정류장 또는 주차장)까지 가서 걸어서 오르내리는 것으로 안내했습니다.");
            }
            note.append(" 실제 등산로 입구는 다를 수 있으니 등산 지도를 확인해 주세요. 등산 시간은 방문 시간에 포함되어 있습니다.");
        }
        if (fuji) {
            note.append("후지산은 5합목(등산로 입구)까지 가서 둘러보는 일정으로 안내합니다. 정상 등반은 7월 초~9월 초 개방 기간에 산장 1박이 필요해 하루 일정에 넣지 않았습니다. ")
                    .append("겨울에는 5합목까지 가는 도로·버스가 닫힐 수 있습니다.");
        }
        leg.setNote(note.toString().trim());
        return leg;
    }

    /** 등산 입구. 후지산은 정해 둔 5합목. 렌터카는 주차장이 있을 때만(없으면 null: 정상 좌표로 차 안내) */
    private static Gate gate(RailGraph graph, BusStopIndex buses, Names names, double lat, double lng, Summit summit, boolean car) {
        if (summit.fuji) {
            Gate g = new Gate();
            g.lat = summit.parkingLat;
            g.lng = summit.parkingLng;
            g.km = DistanceUtil.calculateDistance(lat, lng, g.lat, g.lng);
            g.name = summit.parkingName;
            g.kind = "fuji";
            return g;
        }
        Gate parking = null;
        if (summit.parkingLat != null && summit.parkingLng != null) {
            parking = new Gate();
            parking.lat = summit.parkingLat;
            parking.lng = summit.parkingLng;
            parking.km = DistanceUtil.calculateDistance(lat, lng, parking.lat, parking.lng);
            parking.name = summit.parkingName == null || summit.parkingName.isBlank() ? "주차장" : summit.parkingName;
            parking.kind = "parking";
        }
        if (car) return parking;
        Gate near = nearestTransit(graph, buses, names, lat, lng, HIKE_ACCESS_KM);
        if (near != null && near.km <= HIKE_TRANSIT_GATE_KM) {
            near.kind = "transit";
            return near;
        }
        if (parking != null) return parking;
        if (near != null) near.kind = "far";
        return near;
    }

    /** withinKm 안의 가장 가까운 역이나 버스 정류장 */
    private static Gate nearestTransit(RailGraph graph, BusStopIndex buses, Names names, double lat, double lng, double withinKm) {
        Gate best = null;
        if (graph != null) {
            RailGraph.Station s = graph.nearestStation(lat, lng, withinKm);
            if (s != null) {
                best = new Gate();
                best.lat = s.latitude;
                best.lng = s.longitude;
                best.km = DistanceUtil.calculateDistance(lat, lng, s.latitude, s.longitude);
                best.name = stationName(names, s.name);
            }
        }
        if (buses != null) {
            Object[] stop = buses.nearestStop(lat, lng, withinKm);
            if (stop != null && (best == null || (double) stop[2] < best.km)) {
                best = new Gate();
                best.lat = (double) stop[0];
                best.lng = (double) stop[1];
                best.km = (double) stop[2];
                best.name = placeName(names, (String) stop[3], null, true) + " 정류장";
            }
        }
        return best;
    }

    static int hikeMinutes(double straightKm) {
        return Math.max(5, (int) Math.round(straightKm * HIKE_MIN_PER_KM / 5.0) * 5);
    }

    private static TransitLegDto.Step hikeStep(String from, String to, int minutes, double km, String kind) {
        TransitLegDto.Step step = new TransitLegDto.Step();
        step.setType("도보");
        step.setCategory(kind);
        step.setFrom(from);
        step.setTo(to);
        step.setMinutes(minutes);
        step.setKm(Math.round(km * 10.0) / 10.0);
        return step;
    }

    /**
     * @param buses 전국 버스 정류장 색인 (국토수치정보). 철도망·GTFS 로 경로를 못 찾은 구간에서 버스 노선을 찾는 데 쓴다. 없으면 null
     */
    public static TransitLegDto plan(RailGraph graph, BusStopIndex buses, Names names, double fromLat, double fromLng, String fromName,
                                     double toLat, double toLng, String toName, String transportation, int scheduleMinutes,
                                     LocalDate date, Integer departMinute) {
        double km = DistanceUtil.calculateDistance(fromLat, fromLng, toLat, toLng);
        if (km < 0.05) return null;
        TransitLegDto sea = sea(graph, buses, names, fromLat, fromLng, fromName, toLat, toLng, toName, transportation, scheduleMinutes,
                date, departMinute, null, null);
        if (sea != null) return sea;

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

        // 시각표 자료(GTFS)가 없는 곳: 전국 버스 정류장 자료에서 두 곳을 잇는 노선을 찾는다
        if (buses != null) {
            BusStopIndex.Trip trip = buses.find(fromLat, fromLng, toLat, toLng, Math.max(0.15, maxWalkKm));
            if (trip != null && trip.minutes < walkMinutes && trip.minutes <= scheduleMinutes * 1.6 + 20) {
                return bus(leg, trip, names, fromName, toName, scheduleMinutes);
            }
        }

        // 양쪽 모두 버스 등의 노선 자료가 있는 곳인데 걷는 것보다 빨리 가는 경로가 없다면(배차 간격이 길거나 노선이 맞지 않는다),
        // 걸을 만한 거리에서는 걸어가라고 안내한다. 노선 자료가 없는 곳에서는 알 수 없으므로 아래의 거리 기준 추정으로 남긴다.
        boolean covered = graph != null && graph.hasStopNear(fromLat, fromLng, COVERED_KM) && graph.hasStopNear(toLat, toLng, COVERED_KM);
        // 전국 버스 정류장 자료로 보아도 양쪽에 정류장이 있는데 걷는 것보다 빠른 노선이 없으면 마찬가지로 걸어가라고 안내한다
        boolean busCovered = buses != null && buses.hasStopNear(fromLat, fromLng, COVERED_KM) && buses.hasStopNear(toLat, toLng, COVERED_KM);
        if (walkMinutes <= WALK_INSTEAD_MINUTES) {
            walk(leg, fromName, toName, walkMinutes, km);
            String note = covered || busCovered
                    ? "가져온 노선 자료에는 이 구간을 걷는 것보다 빨리 가는 대중교통이 없어 도보로 안내합니다."
                    : "이 구간은 가져온 노선 자료에서 대중교통 경로를 찾지 못했고 걸어갈 만한 거리라 도보로 안내합니다. 택시를 타면 더 빨리 갈 수 있습니다.";
            if (walkMinutes > scheduleMinutes + 10) {
                note += " 걷는 시간(" + walkMinutes + "분)이 일정의 이동 시간(" + scheduleMinutes + "분)보다 깁니다.";
            }
            leg.setNote(note);
            return leg;
        }

        // 노선 자료(철도망·GTFS·버스 정류장)가 있는데도 잇는 대중교통이 없고 걷기에도 멀다: 택시로 안내한다
        if (graph != null || buses != null) return taxi(leg, km, fromName, toName, scheduleMinutes);

        // 노선 자료를 하나도 가져오지 않은 상태: 대중교통이 있는지 알 수 없으므로 예전처럼 거리 기준 추정
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

    /** 전국 버스 정류장 자료로 찾은 버스 경로 (시각표 없음: 시간·요금은 거리 기준 추정) */
    private static TransitLegDto bus(TransitLegDto leg, BusStopIndex.Trip trip, Names names, String fromName, String toName,
                                     int scheduleMinutes) {
        List<String> rides = new ArrayList<>();
        int fare = 0;
        int childFare = 0;
        boolean community = false;
        String previous = fromName;
        for (int i = 0; i < trip.rides.size(); i++) {
            BusStopIndex.Ride r = trip.rides.get(i);
            String board = placeName(names, r.from, null, true);
            String alight = placeName(names, r.to, null, true);
            double walkKm = i == 0 ? trip.walkInKm : trip.transferKm;
            int walkMin = i == 0 ? trip.walkInMinutes() : trip.transferMinutes();
            if (walkKm > 0.05) {
                TransitLegDto.Step walk = new TransitLegDto.Step();
                walk.setType(i == 0 ? "도보" : "환승");
                walk.setFrom(previous);
                walk.setTo(board);
                walk.setMinutes(walkMin);
                walk.setKm(Math.round(walkKm * 10.0) / 10.0);
                leg.getSteps().add(walk);
            }
            TransitLegDto.Step wait = new TransitLegDto.Step();
            wait.setType("대기");
            wait.setFrom(board);
            wait.setTo(board);
            wait.setMinutes(BusStopIndex.WAIT_MINUTES);
            leg.getSteps().add(wait);

            int rideFare = RailFareModel.unknownLocalFare(r.km / BusStopIndex.ROAD_FACTOR);
            TransitLegDto.Step ride = new TransitLegDto.Step();
            ride.setType("승차");
            ride.setCategory("버스");
            String operator = names.operator(BusStopIndex.cleanOperator(r.operator));
            ride.setOperator(operator);
            ride.setLine(names.line(r.line));
            ride.setFrom(board);
            ride.setTo(alight);
            ride.setFromOriginal(r.from);
            ride.setToOriginal(r.to);
            ride.setMinutes(r.minutes);
            ride.setKm(r.km);
            ride.setFareYen(rideFare);
            ride.setFareConfirmed(false);
            ride.setFareBasis(FareBook.Basis.ESTIMATE.code);
            leg.getSteps().add(ride);
            fare += rideFare;
            childFare += RailFareModel.childFare(rideFare);
            if (r.busType == BusStopIndex.TYPE_COMMUNITY) community = true;
            rides.add(operator + " " + ride.getLine() + " " + board + " → " + alight);
            previous = alight;
        }
        if (trip.walkOutKm > 0.05) {
            TransitLegDto.Step walk = new TransitLegDto.Step();
            walk.setType("도보");
            walk.setFrom(previous);
            walk.setTo(toName);
            walk.setMinutes(trip.walkOutMinutes());
            walk.setKm(Math.round(trip.walkOutKm * 10.0) / 10.0);
            leg.getSteps().add(walk);
        }
        leg.setMode("버스");
        leg.setMinutes(trip.minutes);
        leg.setFareYen(fare);
        leg.setChildFareYen(childFare);
        leg.setTransfers(trip.rides.size() - 1);
        leg.setSource("bus-stops");
        leg.setFareSource("estimate");
        leg.setSummary(String.join(" / 환승 / ", rides) + " (버스, 시각 미확인)");
        StringBuilder note = new StringBuilder("버스 노선은 국토수치정보 버스 정류장 자료(2022년)에서 두 정류장에 함께 서는 노선을 찾은 것입니다. ")
                .append("시각표가 없어 기다림 ").append(BusStopIndex.WAIT_MINUTES).append("분과 도로 거리로 시간을 추정했고, 요금도 거리 기준 추정입니다. ")
                .append("노선·방향·운행 횟수가 바뀌었을 수 있으니 현지 시간표로 확인해 주세요.");
        if (community) note.append(" 커뮤니티 버스는 하루 운행 횟수가 적을 수 있습니다.");
        if (trip.minutes > scheduleMinutes + 10) {
            note.append(" 이 경로의 추정 시간(").append(trip.minutes).append("분)이 일정의 이동 시간(").append(scheduleMinutes).append("분)보다 깁니다.");
        }
        leg.setNote(note.toString());
        return leg;
    }

    /**
     * 바다를 건너는 구간(섬·오키나와 등)이면 배·비행기를 넣은 안내, 아니면 null ({@link SeaRoutes}).
     * 항구·공항까지와 내린 뒤의 이동은 땅 위 구간으로 다시 계산해(철도·버스·도보·택시) 앞뒤에 붙인다.
     */
    static TransitLegDto sea(RailGraph graph, BusStopIndex buses, Names names, double fromLat, double fromLng, String fromName,
                             double toLat, double toLng, String toName, String transportation, int scheduleMinutes,
                             LocalDate date, Integer departMinute, Summit fromHike, Summit toHike) {
        SeaRoutes.Crossing c = SeaRoutes.crossing(fromLat, fromLng, toLat, toLng, transportation);
        if (c == null) return null;
        String ground = SeaRoutes.untag(transportation);
        double km = DistanceUtil.calculateDistance(fromLat, fromLng, toLat, toLng);
        TransitLegDto leg = new TransitLegDto();
        leg.setEstimated(true);
        leg.setDistanceKm(Math.round(km * 10.0) / 10.0);
        leg.setSource("sea");
        leg.setFareSource("estimate");

        if (c.unknown) {
            leg.setMode("배 등");
            leg.setMinutes(c.minutes);
            leg.setSummary(SeaRoutes.landName(c.fromLand) + " → " + SeaRoutes.landName(c.toLand) + ": 배나 비행기가 필요한 구간 (운항 정보 없음)");
            leg.setNote("바다를 건너는 구간인데 등록된 배·비행기 노선이 없어 경로를 안내하지 못했습니다. 시간은 배를 탄다고 보고 거리로 어림한 값이며, "
                    + "요금은 넣지 않았습니다. 운항사에서 배편·항공편을 확인해 주세요.");
            return leg;
        }

        int minutes = 0;
        int fare = 0;
        int childFare = 0;
        boolean unknownShipFare = false;
        int transfers = c.routes.size() - 1;
        double curLat = fromLat, curLng = fromLng;
        String curName = fromName;
        Summit curHike = fromHike;
        List<String> parts = new ArrayList<>();
        StringBuilder note = new StringBuilder();
        for (int i = 0; i < c.routes.size(); i++) {
            SeaRoutes.Route route = c.routes.get(i);
            SeaRoutes.Hub start = c.hops.get(i)[0];
            SeaRoutes.Hub end = c.hops.get(i)[1];
            Integer depart = departMinute == null ? null : departMinute + minutes;
            TransitLegDto sub = groundLeg(graph, buses, names, curLat, curLng, curName, start, ground, date, depart, curHike, null);
            if (sub != null) {
                minutes += sub.getMinutes();
                fare += sub.getFareYen() == null ? 0 : sub.getFareYen();
                childFare += sub.getChildFareYen() == null ? 0 : sub.getChildFareYen();
                transfers += sub.getTransfers();
                addSteps(leg, sub, curName, start.name);
            }
            TransitLegDto.Step wait = new TransitLegDto.Step();
            wait.setType("대기");
            wait.setCategory(route.modeName());
            wait.setFrom(start.name);
            wait.setTo(start.name);
            wait.setMinutes(route.boardMinutes);
            leg.getSteps().add(wait);

            TransitLegDto.Step ride = new TransitLegDto.Step();
            ride.setType("승차");
            ride.setCategory(route.modeName());
            ride.setOperator(route.operator);
            ride.setFrom(start.name);
            ride.setTo(end.name);
            ride.setMinutes(route.rideMinutes);
            ride.setKm(Math.round(DistanceUtil.calculateDistance(start.lat, start.lng, end.lat, end.lng) * 10.0) / 10.0);
            ride.setFareYen(route.fareYen);
            ride.setFareConfirmed(false);
            ride.setFareBasis(FareBook.Basis.ESTIMATE.code);
            leg.getSteps().add(ride);
            if (route.fareYen != null) {
                fare += route.fareYen;
                childFare += (int) (Math.ceil(route.fareYen / 2.0 / 10.0) * 10);   // 배의 아동 요금은 대개 절반
            } else if (!route.flight) {
                unknownShipFare = true;
            }

            TransitLegDto.Step after = new TransitLegDto.Step();
            after.setType("도보");
            after.setCategory(route.flight ? "공항 나오기" : "배에서 내리기");
            after.setFrom(end.name);
            after.setTo(end.name);
            after.setMinutes(route.afterMinutes);
            leg.getSteps().add(after);
            minutes += route.totalMinutes();

            parts.add(route.modeName() + " " + start.name + " → " + end.name + " (약 " + route.rideMinutes + "분)");
            if (route.note != null) note.append(route.note).append(' ');
            curLat = end.lat;
            curLng = end.lng;
            curName = end.name;
            curHike = null;
        }
        Integer depart = departMinute == null ? null : departMinute + minutes;
        TransitLegDto last = groundLeg(graph, buses, names, curLat, curLng, curName, toLat, toLng, toName, ground, date, depart, null, toHike);
        if (last != null) {
            minutes += last.getMinutes();
            fare += last.getFareYen() == null ? 0 : last.getFareYen();
            childFare += last.getChildFareYen() == null ? 0 : last.getChildFareYen();
            transfers += last.getTransfers();
            addSteps(leg, last, curName, toName);
        }

        leg.setMode(c.anyFlight() && c.anyShip() ? "비행기·배" : c.anyFlight() ? "비행기" : "배");
        leg.setMinutes(minutes);
        leg.setFareYen(fare);
        leg.setChildFareYen(childFare);
        leg.setTransfers(transfers);
        leg.setSummary(String.join(" · ", parts) + ", 전체 약 " + minutes + "분");
        if (c.prefNote != null) note.insert(0, c.prefNote + " ");
        if (c.anyFlight()) note.append("항공권 요금은 예약 시점에 따라 크게 달라 요금 합계에 넣지 않았습니다. 비행시간은 거리로 어림했고, 공항에는 1시간 전에 도착한다고 보았습니다. ");
        if (unknownShipFare) note.append("요금을 확인하지 못한 배편이 있어 그 요금은 합계에서 뺐습니다. ");
        if (ground != null && (ground.contains("렌트") || ground.contains("자동차")) && c.anyFlight()) {
            note.append("렌터카는 도착한 곳에서 다시 빌린다고 보았습니다. ");
        }
        note.append("배·비행기의 시간과 요금은 운항사 공개 정보를 옮긴 대략값(2026년 10월 기준)입니다. 출발 시각은 시각표를 확인해 주세요.");
        if (minutes > scheduleMinutes + 10) {
            note.append(" 이 경로의 추정 시간(").append(minutes).append("분)이 일정의 이동 시간(").append(scheduleMinutes).append("분)보다 깁니다.");
        }
        leg.setNote(note.toString().trim());
        return leg;
    }

    /** 땅 위 구간의 단계를 붙인다. 단계가 없는 추정 구간(노선 자료 없음)은 한 단계로 만들어 붙인다 (단계 시간의 합 = 전체 시간) */
    private static void addSteps(TransitLegDto leg, TransitLegDto sub, String from, String to) {
        if (sub.getSteps().isEmpty()) {
            TransitLegDto.Step step = new TransitLegDto.Step();
            step.setType("승차");
            step.setCategory(sub.getMode());
            step.setFrom(from);
            step.setTo(to);
            step.setMinutes(sub.getMinutes());
            step.setFareYen(sub.getFareYen());
            step.setFareConfirmed(false);
            step.setFareBasis(FareBook.Basis.ESTIMATE.code);
            leg.getSteps().add(step);
        } else {
            leg.getSteps().addAll(sub.getSteps());
        }
        leg.getFareRides().addAll(sub.getFareRides());
    }

    private static TransitLegDto groundLeg(RailGraph graph, BusStopIndex buses, Names names, double fromLat, double fromLng, String fromName,
                                           SeaRoutes.Hub hub, String ground, LocalDate date, Integer departMinute, Summit fromHike, Summit toHike) {
        return groundLeg(graph, buses, names, fromLat, fromLng, fromName, hub.lat, hub.lng, hub.name, ground, date, departMinute, fromHike, toHike);
    }

    /** 항구·공항까지(또는 내린 뒤) 땅 위 구간. 걸어서만 다니는 여행이라도 2km 넘으면 대중교통으로 찾는다 */
    private static TransitLegDto groundLeg(RailGraph graph, BusStopIndex buses, Names names, double fromLat, double fromLng, String fromName,
                                           double toLat, double toLng, String toName, String ground, LocalDate date, Integer departMinute,
                                           Summit fromHike, Summit toHike) {
        double km = DistanceUtil.calculateDistance(fromLat, fromLng, toLat, toLng);
        if (km < 0.05) return null;
        String mode = ground == null || ground.isBlank() ? "대중교통" : ground;
        if (mode.contains("도보") && !mode.contains("대중교통") && km > 2.0) mode = "대중교통";
        int schedule = com.travel.planner.util.TravelTimeEstimator.minutesForDistance(km, mode);
        if (fromHike != null || toHike != null) {
            return plan(graph, buses, names, fromLat, fromLng, fromName, toLat, toLng, toName, mode, schedule, date, departMinute, fromHike, toHike);
        }
        return plan(graph, buses, names, fromLat, fromLng, fromName, toLat, toLng, toName, mode, schedule, date, departMinute);
    }

    /** 택시 요금: 첫 1.096km 500엔, 이후 255m 마다 100엔 (도쿄 23구 등 2022년 개정 요금. 지역·심야에 따라 다르다) */
    static int taxiFare(double straightKm) {
        double road = straightKm * 1.3;
        if (road <= 1.096) return 500;
        return 500 + (int) Math.ceil((road - 1.096) / 0.255) * 100;
    }

    /** 택시 시간: 부르고 타는 데 5분 + 도로 거리(직선×1.3)를 시내 시속 25km(10km 넘는 부분은 40km)로 */
    static int taxiMinutes(double straightKm) {
        double road = straightKm * 1.3;
        double minutes = 5 + Math.min(road, 10) / 25.0 * 60 + Math.max(0, road - 10) / 40.0 * 60;
        return Math.max(5, (int) Math.ceil(minutes));
    }

    private static TransitLegDto taxi(TransitLegDto leg, double km, String fromName, String toName, int scheduleMinutes) {
        int minutes = taxiMinutes(km);
        int fare = taxiFare(km);
        leg.setMode("택시");
        leg.setMinutes(minutes);
        leg.setFareYen(fare);
        leg.setChildFareYen(fare);       // 택시는 차 한 대 요금이다
        leg.setSource("taxi");
        leg.setFareSource("estimate");
        leg.setSummary("택시 약 " + minutes + "분 (대중교통 경로 없음)");
        TransitLegDto.Step step = new TransitLegDto.Step();
        step.setType("승차");
        step.setCategory("택시");
        step.setFrom(fromName);
        step.setTo(toName);
        step.setMinutes(minutes);
        step.setKm(Math.round(km * 1.3 * 10.0) / 10.0);
        step.setFareYen(fare);
        step.setFareConfirmed(false);
        step.setFareBasis(FareBook.Basis.ESTIMATE.code);
        leg.getSteps().add(step);
        StringBuilder note = new StringBuilder("가져온 노선 자료(철도·버스)에서 이 구간을 잇는 대중교통을 찾지 못했고 걷기에는 멀어 택시로 안내합니다. ")
                .append("요금은 일반 택시 요금(첫 약 1.1km 500엔, 이후 약 255m마다 100엔)과 도로 거리로 추정한 차 한 대 요금이며, 지역·시간대에 따라 다릅니다.");
        if (minutes > scheduleMinutes + 10) {
            note.append(" 이 경로의 추정 시간(").append(minutes).append("분)이 일정의 이동 시간(").append(scheduleMinutes).append("분)보다 깁니다.");
        }
        leg.setNote(note.toString());
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
