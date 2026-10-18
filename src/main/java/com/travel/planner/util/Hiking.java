package com.travel.planner.util;

import com.travel.planner.entity.Place;

import java.util.Set;

/**
 * 걸어서 올라가야 하는 곳(산 정상·등산로 등)인가.
 *
 * 등산은 그 장소의 정상적인 방문 방법이므로 막지 않는다. 다만 아이·어르신과 함께하는 여행에서는 힘들 수 있어
 * 일정 엔진이 점수를 낮춘다({@code PlanService}). 판단은 AI 가 붙인 세부 유형과 한 줄 소개, 일본어 이름으로 한다.
 *
 * 등산 입구는 세 단계로 정한다 ({@code TransitLegPlanner}):
 *  1) 정상 1.5km 안의 역·버스 정류장 → 대중교통으로 가서 걸어 오른다 (다카오산형)
 *  2) 없으면 정상 근처 주차장(구글, {@code HikeAccessService}) → 택시 등으로 주차장까지 가서 걸어 오른다 (하마이시다케형)
 *  3) 주차장도 모르면 4km 안의 가장 가까운 역·정류장에서 걸어 오른다
 * 후지산은 따로: 5합목까지 가서 둘러보는 곳으로 다룬다.
 */
public final class Hiking {

    private Hiking() {}

    /** 세부 유형이 이것이면 등산하는 곳 */
    private static final Set<String> SUB_TYPES = Set.of(
            "산", "산 정상", "산정상", "봉우리", "등산로", "등산", "등산 코스", "하이킹", "하이킹 코스", "트레킹", "트레킹 코스",
            "산길", "고개", "능선", "화산", "산악");

    /** 한 줄 소개에 이 말이 있으면 등산하는 곳 */
    private static final String[] SUMMARY_WORDS = {"등산", "산 정상", "정상에서", "정상까지", "트레킹", "산행"};

    /** 정상에서 이 거리(km) 안에 역·버스 정류장이 있으면 대중교통으로 가서 걸어 오르는 곳으로 본다 (다카오산처럼) */
    public static final double TRANSIT_GATE_KM = 1.5;

    // ------------------------------------------------------------------ 후지산
    private static final double FUJI_LAT = 35.3606;
    private static final double FUJI_LNG = 138.7274;
    private static final Set<String> FUJI_NAMES = Set.of("富士山", "후지산", "mount fuji", "mt. fuji", "mt fuji", "fujisan", "fuji-san");

    /** 후지산 5합목(등산로 입구): 이름, 위도, 경도 */
    public static final Object[][] FUJI_FIFTH_STATIONS = {
            {"후지산 요시다 루트 5합목(후지 스바루 라인)", 35.3957, 138.7331},
            {"후지산 후지노미야 5합목", 35.3363, 138.7337},
            {"후지산 스바시리 5합목", 35.3660, 138.7783},
            {"후지산 고텐바 신5합목", 35.3369, 138.7747},
    };

    /**
     * 후지산 자체인가 (이름이 "후지산"이거나 정상 2.5km 안의 관광지). "후지산 전망대", "후지산 세계유산센터" 같은 곳은 아니다.
     * 후지산 정상 등반은 7~9월 개방 기간에 산장 1박이 필요해 하루 일정에 넣을 수 없다. 그래서 5합목까지 가서 둘러보는 곳으로 다룬다.
     */
    public static boolean isFuji(Place p) {
        if (p == null) return false;
        for (String n : new String[]{p.getName(), p.getNameKo()}) {
            if (n != null && FUJI_NAMES.contains(n.trim().toLowerCase())) return true;
        }
        if (p.getLatitude() == null || p.getLongitude() == null) return false;
        boolean nearSummit = com.travel.planner.util.DistanceUtil.calculateDistance(p.getLatitude(), p.getLongitude(), FUJI_LAT, FUJI_LNG) <= 2.5;
        return nearSummit && !"숙소".equals(p.getCategory()) && !"교통".equals(p.getCategory());
    }

    /** 출발지에서 가장 가까운 후지산 5합목 {이름, 위도, 경도} */
    public static Object[] nearestFifthStation(double lat, double lng) {
        Object[] best = FUJI_FIFTH_STATIONS[0];
        double bestKm = Double.MAX_VALUE;
        for (Object[] s : FUJI_FIFTH_STATIONS) {
            double km = com.travel.planner.util.DistanceUtil.calculateDistance(lat, lng, (double) s[1], (double) s[2]);
            if (km < bestKm) {
                bestKm = km;
                best = s;
            }
        }
        return best;
    }

    /** 걸어서 오르는 곳인가. 후지산은 5합목 관광으로 다루므로 여기에 넣지 않는다. */
    public static boolean isHike(Place p) {
        if (p == null) return false;
        if (isFuji(p)) return false;
        String sub = p.getSubType() == null ? "" : p.getSubType().trim();
        if (SUB_TYPES.contains(sub)) return true;
        String summary = p.getSummary() == null ? "" : p.getSummary();
        for (String word : SUMMARY_WORDS) {
            if (summary.contains(word)) return true;
        }
        // 일본어 이름이 "…岳"(산봉우리), "…山頂", "…峠"(고개)로 끝나고 세부 유형이 없거나 자연 쪽일 때
        String name = p.getName() == null ? "" : p.getName().trim();
        boolean mountainName = name.endsWith("岳") || name.endsWith("山頂") || name.endsWith("峠") || name.endsWith("ヶ岳");
        return mountainName && (sub.isEmpty() || sub.contains("산") || sub.contains("자연"));
    }
}
