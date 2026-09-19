package com.travel.planner.service;

import com.travel.planner.dto.PlanRequest;
import com.travel.planner.entity.Place;
import com.travel.planner.util.DistanceUtil;
import lombok.Getter;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class PlanService {

    // ============================================================================
    // Step 3. 장소 후보 수집 (PlaceCandidateService) - 하드 제약 휴무일 가지치기
    // ============================================================================
    public List<Place> filterClosedPlaces(List<Place> places, LocalDate travelStartDate) {
        int dayOfWeek = travelStartDate.getDayOfWeek().getValue();
        String[] dayNames = {"월요일", "화요일", "수요일", "목요일", "금요일", "토요일", "일요일"};
        String targetDay = dayNames[dayOfWeek - 1];

        return places.stream().filter(p -> {
            String hours = p.getOpeningHours();
            // 영업시간 문자열에 당일 휴무가 명시되어 있다면 1차 제외 (Pruning)
            return hours == null || !hours.contains(targetDay + ": 휴무");
        }).collect(Collectors.toList());
    }

    // ============================================================================
    // Step 4. 장소 적합도 계산 (Weighted Scoring Algorithm)
    // 수식: Score = w1(테마) + w2(날씨) + w3(예산성) + w4(동행자 평점 가중치)
    // ============================================================================
    public List<Place> applyWeightedScoring(List<Place> places, String weather, PlanRequest request) {
        boolean isBadWeather = weather != null && (weather.contains("비") || weather.contains("눈"));
        List<String> themes = request.getThemes() != null ? request.getThemes() : new ArrayList<>();

        Map<Place, Integer> scoreMap = new HashMap<>();

        for (Place p : places) {
            int score = 50; // Base Score

            // w1. 테마 가중치 (+40)
            if (p.getTheme() != null) {
                for (String t : themes) {
                    if (p.getTheme().contains(t)) score += 40;
                }
            }

            // w2. 날씨 제약 가중치 (실내 +30, 실외 -30)
            if (isBadWeather) {
                if ("실내".equals(p.getPlaceType())) score += 30;
                else if ("실외".equals(p.getPlaceType())) score -= 30;
            }

            // w4. 동행자 안전(평점) 가중치
            if ("가족".equals(request.getCompanion())) {
                if ("관광지".equals(p.getCategory())) score += 20;
                if (p.getTheme() != null && p.getTheme().contains("액티비티")) score -= 50; // 가족 여행 시 무리한 일정 배제
            }

            scoreMap.put(p, score);
        }

        // 점수 내림차순 정렬하여 반환
        return places.stream()
                .sorted((p1, p2) -> scoreMap.get(p2).compareTo(scoreMap.get(p1)))
                .collect(Collectors.toList());
    }

    // ============================================================================
    // Step 5. 방문 장소 선정 (Candidate Selection - 배낭 문제/그리디 기반)
    // ============================================================================
    public List<Place> selectCandidates(List<Place> scoredPlaces, PlanRequest request, int totalDays) {
        // 기획: 배낭 문제(Knapsack) 그리디 접근법. 하루 12시간 기준 총 가용 시간 계산
        int totalAvailableMinutes = totalDays * 12 * 60;
        int accumulatedTime = 0;

        // 예산 한도 세팅 (현재 DTO에는 Budget이 없으므로 추후 필드가 추가되면 연동. 현재는 무한대 처리)
        // int maxBudget = (request.getBudget() != null) ? request.getBudget() : Integer.MAX_VALUE;
        int maxBudget = Integer.MAX_VALUE;
        int accumulatedCost = 0;

        List<Place> selected = new ArrayList<>();

        for (Place p : scoredPlaces) {
            int estimatedDwellTime = calculateDwellTime(p, request.getCompanion());
            // 임시 가상 비용 로직 (테마파크 8천엔, 식당 3천엔)
            int estimatedCost = "테마파크".equals(p.getCategory()) ? 8000 : ("식음".equals(p.getCategory()) ? 3000 : 0);

            // 예산 한도 내에 들어오고 남은 시간이 허락할 때만 최적 조합(Knapsack)에 넣음
            if (accumulatedTime + estimatedDwellTime <= totalAvailableMinutes && accumulatedCost + estimatedCost <= maxBudget) {
                selected.add(p);
                accumulatedTime += estimatedDwellTime;
                accumulatedCost += estimatedCost;
            }
        }
        return selected;
    }

    // ============================================================================
    // Step 6. 이동 경로 계산 (TSP with Time Windows + 2-opt)
    // ============================================================================
    public List<List<Place>> calculateTspWithTimeWindows(List<Place> selectedCandidates, int totalDays, List<PlanRequest.AccommodationInput> accs) {
        // 1. K-Means 공간 분할 (숙소 앵커링 지원)
        List<double[]> centroids = new ArrayList<>();
        if (accs != null && !accs.isEmpty()) {
            centroids.add(new double[]{selectedCandidates.get(0).getLatitude(), selectedCandidates.get(0).getLongitude()}); // 임시 앵커 (추후 Geocoding 연동 요망)
        }

        Map<Integer, List<Place>> clusters = clusterPlacesSimple(selectedCandidates, totalDays);
        List<List<Place>> dailyRoutes = new ArrayList<>();

        for (int i = 0; i < totalDays; i++) {
            List<Place> dayPlaces = clusters.get(i);
            if (dayPlaces == null || dayPlaces.isEmpty()) {
                dailyRoutes.add(new ArrayList<>());
                continue;
            }

            // 2. TSP 초기 경로 구성 (Nearest Neighbor)
            List<Place> route = new ArrayList<>();
            List<Place> unvisited = new ArrayList<>(dayPlaces);
            route.add(unvisited.remove(0)); // Start Node

            while (!unvisited.isEmpty()) {
                Place nearest = unvisited.get(0);
                double minCost = Double.MAX_VALUE;

                for (Place candidate : unvisited) {
                    double dist = DistanceUtil.calculateDistance(
                            route.get(route.size()-1).getLatitude(), route.get(route.size()-1).getLongitude(),
                            candidate.getLatitude(), candidate.getLongitude()
                    );
                    if (dist < minCost) {
                        minCost = dist;
                        nearest = candidate;
                    }
                }
                route.add(nearest);
                unvisited.remove(nearest);
            }

            // 3. 2-opt 경로 개선 (Time-Window 하드 제약 결합)
            boolean improved = true;
            while (improved) {
                improved = false;
                for (int m = 1; m < route.size() - 2; m++) {
                    for (int k = m + 1; k < route.size() - 1; k++) {
                        double distBefore = DistanceUtil.calculateDistance(route.get(m - 1).getLatitude(), route.get(m - 1).getLongitude(), route.get(m).getLatitude(), route.get(m).getLongitude())
                                + DistanceUtil.calculateDistance(route.get(k).getLatitude(), route.get(k).getLongitude(), route.get(k + 1).getLatitude(), route.get(k + 1).getLongitude());

                        double distAfter = DistanceUtil.calculateDistance(route.get(m - 1).getLatitude(), route.get(m - 1).getLongitude(), route.get(k).getLatitude(), route.get(k).getLongitude())
                                + DistanceUtil.calculateDistance(route.get(m).getLatitude(), route.get(m).getLongitude(), route.get(k + 1).getLatitude(), route.get(k + 1).getLongitude());

                        if (distBefore - distAfter > 0.001) {
                            // 순서를 바꿨을 때 예상되는 누적 거리가 (가상 시속 20km 기준) 영업 종료 시간을 넘기는지 검사
                            double tempTotalDist = 0;
                            boolean isTimeWindowViolated = false;

                            // 가상으로 순서를 뒤집어 봅니다
                            List<Place> tempRoute = new ArrayList<>(route);
                            reverseSubList(tempRoute, m, k);

                            for(int idx = 0; idx < tempRoute.size() - 1; idx++) {
                                tempTotalDist += DistanceUtil.calculateDistance(
                                        tempRoute.get(idx).getLatitude(), tempRoute.get(idx).getLongitude(),
                                        tempRoute.get(idx + 1).getLatitude(), tempRoute.get(idx + 1).getLongitude()
                                );
                                // 누적 거리가 15km를 넘어가면(이동에만 45분 이상 소요) Time Window 위반 확률이 매우 높다고 간주하여 페널티 부여
                                if(tempTotalDist > 15.0) {
                                    isTimeWindowViolated = true;
                                    break;
                                }
                            }

                            // Time Window 위반이 예상되지 않을 때만 교환(Swap) 승인
                            if (!isTimeWindowViolated) {
                                reverseSubList(route, m, k);
                                improved = true;
                            }
                        }
                    }
                }
            }
            dailyRoutes.add(route);
        }
        return dailyRoutes;
    }

    // ============================================================================
    // Step 7~9. 일정 배정 & 시뮬레이션 & 검증 (Constraint-based Scheduling & Simulator)
    // ============================================================================
    public SimulationResult runScheduleSimulation(List<Place> draftRoute, PlanRequest request, int dayNumber) {
        SimulationResult result = new SimulationResult();
        List<SimulatedItinerary> validRoute = new ArrayList<>();

        LocalTime currentTime = LocalTime.of(9, 0); // 시작 시간 세팅 (Hard Constraint)
        Place prevPlace = null;
        int currentBudgetUsed = 0; // 예산 제약 변수 (Step 9 검사용)
        // int maxBudget = request.getBudget() != null ? request.getBudget() : Integer.MAX_VALUE;
        int maxBudget = Integer.MAX_VALUE;

        for (Place p : draftRoute) {
            // [Scheduling] 이전 장소 출발 시간 + 이동 시간 = 다음 장소 도착 시간
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

            // [Validator - 고정 일정 충돌 검증] (Step 7)
            if (request.getFixedSchedules() != null) {
                for (PlanRequest.FixedScheduleInput fixed : request.getFixedSchedules()) {
                    if (currentTime.isAfter(fixed.getStartTime().minusMinutes(30)) && currentTime.isBefore(fixed.getEndTime())) {
                        result.setSuccess(false);
                        result.setReason("고정 일정(" + fixed.getName() + ")과 시간이 충돌하여 장소 배치를 취소합니다.");
                        result.setProblemPlace(p);
                        return result;
                    }
                }
            }

            // [Validator - 운영시간 하드 제약 검사] (Step 9)
            LocalTime closeTime = parseCloseTime(p.getOpeningHours());
            if (currentTime.isAfter(closeTime)) {
                result.setSuccess(false);
                result.setReason("장소 운영시간(Time-Window) 충돌: " + p.getName() + " 도착 시 영업 마감");
                result.setProblemPlace(p);
                return result;
            }

            // [Scheduling] 체류 시간 유연성 (Soft Constraint) 배정
            int dwellTime = calculateDwellTime(p, request.getCompanion());

            // [Validator - 예산 하드 제약 검사] (Step 9)
            int estimatedCost = "테마파크".equals(p.getCategory()) ? 8000 : ("식음".equals(p.getCategory()) ? 3000 : 0);
            currentBudgetUsed += estimatedCost;
            if (currentBudgetUsed > maxBudget) {
                result.setSuccess(false);
                result.setReason(p.getName() + " 방문 시 유저가 설정한 예산을 초과합니다.");
                result.setProblemPlace(p);
                return result;
            }

            validRoute.add(new SimulatedItinerary(p, currentTime.toString()));
            currentTime = currentTime.plusMinutes(dwellTime);

            // [Validator - 여행 한계 시간 초과 검사] (Step 9)
            if (currentTime.isAfter(LocalTime.of(22, 0))) {
                result.setSuccess(false);
                result.setReason("여행시간 초과: 22:00 이후의 일정은 물리적 한계를 벗어납니다.");
                result.setProblemPlace(p);
                return result;
            }
            prevPlace = p;
        }

        result.setSuccess(true);
        result.setValidRoute(validRoute);
        return result;
    }


    // ---------------- 내부 알고리즘 유틸리티 메서드 ----------------

    private Map<Integer, List<Place>> clusterPlacesSimple(List<Place> places, int kDays) {
        Map<Integer, List<Place>> clusters = new HashMap<>();
        for (int i = 0; i < kDays; i++) clusters.put(i, new ArrayList<>());
        for (int i = 0; i < places.size(); i++) {
            clusters.get(i % kDays).add(places.get(i)); // 간이 클러스터링 로직 (고도화 가능)
        }
        return clusters;
    }

    private void reverseSubList(List<Place> route, int i, int k) {
        while (i < k) {
            Place temp = route.get(i);
            route.set(i, route.get(k));
            route.set(k, temp);
            i++; k--;
        }
    }

    private int calculateDwellTime(Place p, String companion) {
        int time = 90;
        if ("쇼핑".equals(p.getCategory())) time = 120;
        else if ("테마파크".equals(p.getCategory())) time = 240;
        if ("가족".equals(companion)) time = (int)(time * 1.3);
        return time;
    }

    private LocalTime parseCloseTime(String hours) {
        if (hours == null || hours.contains("없음")) return LocalTime.of(22, 0);
        try {
            String[] parts = hours.split("-");
            if (parts.length == 2) {
                String[] t = parts[1].trim().split(":");
                return LocalTime.of(Integer.parseInt(t[0]), Integer.parseInt(t[1]));
            }
        } catch (Exception e) {}
        return LocalTime.of(22, 0);
    }

    @Getter
    public static class SimulationResult {
        private boolean success;
        private String reason;
        private Place problemPlace;
        private List<SimulatedItinerary> validRoute;

        public void setSuccess(boolean s) { this.success = s; }
        public void setReason(String r) { this.reason = r; }
        public void setProblemPlace(Place p) { this.problemPlace = p; }
        public void setValidRoute(List<SimulatedItinerary> v) { this.validRoute = v; }
    }

    @Getter
    public static class SimulatedItinerary {
        private Place place;
        private String time;
        public SimulatedItinerary(Place p, String t) { this.place = p; this.time = t; }
    }
}