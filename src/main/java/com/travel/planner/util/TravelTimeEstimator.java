package com.travel.planner.util;

/**
 * 직선거리 기반 이동 시간 추정기.
 * 일정 엔진, 저장되는 Traffic, reroute 검증이 모두 이 한 곳의 계산을 쓴다.
 * (이전에는 단계마다 30분 고정 / 20km/h / 20km/h×1.2 로 서로 달라 일정이 일찍 끝나거나 마감을 넘겼다.)
 */
public final class TravelTimeEstimator {

    private TravelTimeEstimator() {}

    public static int minutes(double lat1, double lng1, double lat2, double lng2, String transportation) {
        double km = DistanceUtil.calculateDistance(lat1, lng1, lat2, lng2);
        return minutesForDistance(km, transportation);
    }

    /**
     * 이동 수단별 "가장 빠른 수단"의 하한선(lower envelope)을 취한다.
     *  - 도보        : 약 4.5km/h, 우회 계수 1.3
     *  - 시내 대중교통: 대기·도보 10분 + 3분/km
     *  - 광역 전철    : 30분 + 1분/km (오사카↔교토 43km ≈ 73분, 난바↔간사이공항 35km ≈ 65분)
     *  - 신칸센/특급  : 60분 + 0.4분/km (도쿄↔오사카 400km ≈ 220분)
     */
    public static int minutesForDistance(double km, String transportation) {
        if (km < 0.05) return 0;
        String mode = transportation == null ? "" : transportation;
        double walk = km * 17.3;
        double best;

        boolean car = mode.contains("렌트") || mode.contains("자동차") || mode.contains("택시") || mode.toLowerCase().contains("car");
        boolean transit = mode.contains("대중교통") || mode.contains("지하철") || mode.contains("버스") || mode.contains("전철") || mode.contains("기차");
        boolean walkOnly = mode.contains("도보") && !transit && !car;

        if (walkOnly && km <= 6.0) {
            best = walk;
        } else if (car) {
            best = Math.min(walk, Math.min(8 + km * 2.4, Math.min(20 + km * 1.2, 40 + km * 0.75)));
        } else {
            best = Math.min(walk, Math.min(10 + km * 3.0, Math.min(30 + km * 1.0, 60 + km * 0.4)));
        }
        return Math.max(5, TimeUtil.roundUpTo5(best));
    }
}
