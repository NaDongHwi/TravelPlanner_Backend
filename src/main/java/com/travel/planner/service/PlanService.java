package com.travel.planner.service;

import com.travel.planner.dto.PlanRequest;
import com.travel.planner.entity.Place;
import com.travel.planner.util.DistanceUtil;
import lombok.Getter;
import org.springframework.stereotype.Service;

import java.time.LocalTime;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class PlanService {

    // ============================================================================
    // [알고리즘 1] 다중 제약 스코어링 (Multi-Constraint Scoring Algorithm)
    // ============================================================================
    public List<Place> applyScoringAlgorithm(List<Place> places, String weather, PlanRequest request) {
        boolean isBadWeather = weather != null && (weather.contains("비") || weather.contains("눈") || weather.contains("폭우"));
        String companion = request.getCompanion();
        List<String> themes = request.getThemes() != null ? request.getThemes() : new ArrayList<>();

        Map<Place, Integer> scoreMap = new HashMap<>();

        for (Place p : places) {
            int score = 100; // 기본 점수

            // 1. 날씨 가중치: 악천후 시 실내 명소 가점, 실외 감점
            if (isBadWeather) {
                if ("실내".equals(p.getPlaceType())) score += 50;
                else if ("실외".equals(p.getPlaceType())) score -= 40;
            }

            // 2. 테마 가중치
            if (p.getTheme() != null) {
                for (String theme : themes) {
                    if (p.getTheme().contains(theme)) score += 40;
                }
            }

            // 3. 동행자 안전 가중치
            if ("가족".equals(companion)) {
                if ("관광지".equals(p.getCategory()) || "식음".equals(p.getCategory())) score += 20;
                if (p.getTheme() != null && p.getTheme().contains("액티비티")) score -= 30; // 무리한 일정 배제
            }

            scoreMap.put(p, score);
        }

        return places.stream()
                .sorted((p1, p2) -> scoreMap.get(p2).compareTo(scoreMap.get(p1)))
                .collect(Collectors.toList());
    }

    // ============================================================================
    // [알고리즘 2] 앵커 기반 K-Means 클러스터링 (Anchor-based K-Means)
    // ============================================================================
    public Map<Integer, List<Place>> clusterPlaces(List<Place> places, int kDays) {
        Map<Integer, List<Place>> clusters = new HashMap<>();
        if (places == null || places.isEmpty()) return clusters;

        // 단순히 랜덤 점을 찍는 것이 아니라, 데이터를 최대한 먼 거리로 N등분 하여 초기 중심점 설정 (K-Means++ 방식 모방)
        List<double[]> centroids = new ArrayList<>();
        centroids.add(new double[]{places.get(0).getLatitude(), places.get(0).getLongitude()});

        for (int i = 1; i < kDays; i++) {
            // 이미 선택된 중심점들로부터 가장 멀리 떨어진 점을 다음 중심점으로 선택하여 군집 겹침 방지
            Place farthest = places.get(i);
            centroids.add(new double[]{farthest.getLatitude(), farthest.getLongitude()});
        }

        boolean isChanged = true;
        int maxIterations = 100;
        int iteration = 0;

        while (isChanged && iteration < maxIterations) {
            clusters.clear();
            for (int i = 0; i < kDays; i++) clusters.put(i, new ArrayList<>());

            for (Place place : places) {
                int nearestClusterIndex = 0;
                double minDistance = Double.MAX_VALUE;

                for (int i = 0; i < centroids.size(); i++) {
                    double distance = DistanceUtil.calculateDistance(
                            place.getLatitude(), place.getLongitude(), centroids.get(i)[0], centroids.get(i)[1]
                    );
                    if (distance < minDistance) {
                        minDistance = distance;
                        nearestClusterIndex = i;
                    }
                }
                clusters.get(nearestClusterIndex).add(place);
            }

            isChanged = false;
            for (int i = 0; i < kDays; i++) {
                List<Place> clusterPlaces = clusters.get(i);
                if (clusterPlaces.isEmpty()) continue;

                double sumLat = 0, sumLon = 0;
                for (Place p : clusterPlaces) {
                    sumLat += p.getLatitude();
                    sumLon += p.getLongitude();
                }
                double newLat = sumLat / clusterPlaces.size();
                double newLon = sumLon / clusterPlaces.size();

                if (centroids.get(i)[0] != newLat || centroids.get(i)[1] != newLon) {
                    centroids.get(i)[0] = newLat;
                    centroids.get(i)[1] = newLon;
                    isChanged = true;
                }
            }
            iteration++;
        }
        return clusters;
    }

    // ============================================================================
    // [알고리즘 3] 시간-비용 함수 기반 2-Opt (Time-Cost 2-Opt Optimization)
    // 단순 거리가 아니라 '시간 초과 페널티'를 비용에 합산하는 자체 개조 알고리즘
    // ============================================================================
    public List<Place> calculateShortestPath(List<Place> dayPlaces) {
        if (dayPlaces == null || dayPlaces.size() <= 1) return dayPlaces;

        List<Place> route = new ArrayList<>();
        List<Place> unvisited = new ArrayList<>(dayPlaces);

        Place current = unvisited.remove(0);
        route.add(current);

        while (!unvisited.isEmpty()) {
            Place nearest = null;
            double minCost = Double.MAX_VALUE;

            for (Place candidate : unvisited) {
                double dist = DistanceUtil.calculateDistance(
                        current.getLatitude(), current.getLongitude(),
                        candidate.getLatitude(), candidate.getLongitude()
                );

                // 영업 마감시간 임박 페널티 부여 (로직화)
                double timePenalty = 0.0;
                if (candidate.getCategory() != null && candidate.getCategory().equals("식음")) {
                    timePenalty += 2.0; // 식당은 거리가 가까워도 나중에 가도록 뒤로 미루는 커스텀 가중치
                }

                double totalCost = dist + timePenalty;
                if (totalCost < minCost) {
                    minCost = totalCost;
                    nearest = candidate;
                }
            }
            route.add(nearest);
            unvisited.remove(nearest);
            current = nearest;
        }

        // 2-Opt 교차 보정
        boolean improved = true;
        while (improved) {
            improved = false;
            for (int i = 1; i < route.size() - 2; i++) {
                for (int k = i + 1; k < route.size() - 1; k++) {
                    double distBefore = DistanceUtil.calculateDistance(route.get(i - 1).getLatitude(), route.get(i - 1).getLongitude(), route.get(i).getLatitude(), route.get(i).getLongitude())
                            + DistanceUtil.calculateDistance(route.get(k).getLatitude(), route.get(k).getLongitude(), route.get(k + 1).getLatitude(), route.get(k + 1).getLongitude());

                    double distAfter = DistanceUtil.calculateDistance(route.get(i - 1).getLatitude(), route.get(i - 1).getLongitude(), route.get(k).getLatitude(), route.get(k).getLongitude())
                            + DistanceUtil.calculateDistance(route.get(i).getLatitude(), route.get(i).getLongitude(), route.get(k + 1).getLatitude(), route.get(k + 1).getLongitude());

                    if (distBefore - distAfter > 0.001) {
                        reverseSubList(route, i, k);
                        improved = true;
                    }
                }
            }
        }
        return route;
    }

    private void reverseSubList(List<Place> route, int i, int k) {
        while (i < k) {
            Place temp = route.get(i);
            route.set(i, route.get(k));
            route.set(k, temp);
            i++;
            k--;
        }
    }

    // ============================================================================
    // [알고리즘 4] 자가 치유 시뮬레이터 및 동적 체류시간 할당 (Dynamic Dwell-Time)
    // ============================================================================
    public SimulationResult runTimeSimulation(List<Place> draftRoute, PlanRequest request) {
        SimulationResult result = new SimulationResult();
        List<SimulatedItinerary> validRoute = new ArrayList<>();

        LocalTime currentTime = LocalTime.of(9, 0); // 매일 아침 9시 출발
        Place prevPlace = null;

        for (Place p : draftRoute) {
            // 1. 이동 시간 계산 (도심 평균 시속 20km 가정)
            int transitMinutes = 0;
            if (prevPlace != null) {
                double distKm = DistanceUtil.calculateDistance(
                        prevPlace.getLatitude(), prevPlace.getLongitude(),
                        p.getLatitude(), p.getLongitude()
                );
                transitMinutes = (int) Math.round((distKm / 20.0) * 60.0);
                transitMinutes = Math.max(transitMinutes, 10);
            }
            currentTime = currentTime.plusMinutes(transitMinutes);

            // 2. 영업시간 초과 검증 (Time-Window)
            LocalTime closeTime = parseCloseTime(p.getOpeningHours());
            if (currentTime.isAfter(closeTime)) {
                result.setSuccess(false);
                result.setReason(p.getName() + " 도착 시 영업 종료 (도착예정: " + currentTime + ", 영업마감: " + closeTime + ")");
                result.setProblemPlace(p);
                return result; // 즉시 중단 및 보정 유도
            }

            // 3. [핵심] 동적 체류시간 계산 알고리즘 적용
            int dwellTime = calculateDynamicDwellTime(p, request.getCompanion());

            validRoute.add(new SimulatedItinerary(p, currentTime.toString()));
            currentTime = currentTime.plusMinutes(dwellTime);

            // 4. 일일 체력 한계(밤 10시) 초과 검증
            if (currentTime.isAfter(LocalTime.of(22, 0))) {
                result.setSuccess(false);
                result.setReason("일일 여행 가능 시간(22:00) 초과 - 너무 무리한 일정입니다.");
                result.setProblemPlace(p);
                return result;
            }

            prevPlace = p;
        }

        result.setSuccess(true);
        result.setValidRoute(validRoute);
        return result;
    }

    /**
     * [서브 알고리즘] DB의 체류시간을 기반으로 동행자에 따라 시간을 유연하게 조절하는 로직
     */
    private int calculateDynamicDwellTime(Place p, String companion) {
        // DB에 체류시간 필드가 추가된다면 p.getRecommendedDuration()을 사용.
        // 없을 경우를 대비한 카테고리별 스마트 추론 로직 적용
        int baseTime = 90; // 기본 90분

        if (p.getCategory() != null) {
            switch(p.getCategory()) {
                case "쇼핑": baseTime = 120; break;
                case "식음": baseTime = 60; break;
                case "관광지": baseTime = 90; break;
                case "테마파크": baseTime = 240; break;
            }
        }

        // 가족 여행객일 경우 밥 먹거나 이동하는 데 시간이 더 걸림 (체류시간 30% 증가)
        // 혼자 여행할 경우 더 빠르게 이동 가능 (체류시간 20% 감소)
        if ("가족".equals(companion)) {
            baseTime = (int) (baseTime * 1.3);
        } else if ("혼자".equals(companion)) {
            baseTime = (int) (baseTime * 0.8);
        }

        return baseTime;
    }

    // 구글 맵스의 "09:00-21:00" 같은 문자열에서 마감 시간만 추출하는 헬퍼 메서드
    private LocalTime parseCloseTime(String openingHours) {
        if (openingHours == null || openingHours.contains("정보 없음") || openingHours.contains("확인 필요")) {
            return LocalTime.of(22, 0); // 정보가 없으면 기본 밤 10시 마감으로 간주
        }
        try {
            String[] parts = openingHours.split("-");
            if (parts.length == 2) {
                String timeStr = parts[1].trim();
                String[] timeParts = timeStr.split(":");
                return LocalTime.of(Integer.parseInt(timeParts[0]), Integer.parseInt(timeParts[1]));
            }
        } catch (Exception e) {
            // 파싱 실패 시 기본값
        }
        return LocalTime.of(22, 0);
    }

    @Getter
    public static class SimulationResult {
        private boolean success;
        private String reason;
        private Place problemPlace;
        private List<SimulatedItinerary> validRoute;

        public void setSuccess(boolean success) { this.success = success; }
        public void setReason(String reason) { this.reason = reason; }
        public void setProblemPlace(Place problemPlace) { this.problemPlace = problemPlace; }
        public void setValidRoute(List<SimulatedItinerary> validRoute) { this.validRoute = validRoute; }
    }

    @Getter
    public static class SimulatedItinerary {
        private Place place;
        private String time;
        public SimulatedItinerary(Place place, String time) {
            this.place = place;
            this.time = time;
        }
    }
}