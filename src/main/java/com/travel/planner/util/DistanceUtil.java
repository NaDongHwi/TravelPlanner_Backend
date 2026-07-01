package com.travel.planner.util;

public class DistanceUtil {

    private static final double EARTH_RADIUS = 6371.0; // 지구의 평균 반경 (단위: km)

    /**
     * 하버사인 공식을 이용해 두 지점(위도, 경도) 사이의 직선 거리를 계산합니다.
     * @return 두 지점 사이의 거리 (단위: km)
     */
    public static double calculateDistance(double lat1, double lon1, double lat2, double lon2) {
        // 1. 위도와 경도의 차이를 구하고 라디안(Radian) 단위로 변환합니다.
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);

        // 2. 구면 삼각법(하버사인 공식) 적용
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2) +
                Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) *
                        Math.sin(dLon / 2) * Math.sin(dLon / 2);

        double c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));

        // 3. 지구 반경을 곱해 최종 거리를 반환합니다.
        return EARTH_RADIUS * c;
    }
}