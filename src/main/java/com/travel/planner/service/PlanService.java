package com.travel.planner.service;

import com.travel.planner.dto.PlanRequest;
import com.travel.planner.entity.Place;
import com.travel.planner.util.DistanceUtil;
import lombok.Getter;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.Duration;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class PlanService {

    // ============================================================================
    // 시간 총량 기반 (거시적) 물리적 가용 시간 계산 로직
    // ============================================================================
    public int calculateTotalPhysicalMinutes(PlanRequest request, int totalDays) {
        int totalMinutes = 0;
        LocalTime inTime = parseInOutTime(request.getInTime(), true);
        LocalTime outTime = parseInOutTime(request.getOutTime(), false);

        for (int day = 1; day <= totalDays; day++) {
            if (day == 1) { // 입국일: 입국 시간 + 수속 버퍼(2시간)
                LocalTime start = inTime.plusHours(2);
                if (start.isBefore(LocalTime.of(9, 0))) start = LocalTime.of(9, 0);
                if (start.isBefore(LocalTime.of(22, 0))) {
                    totalMinutes += (int) Duration.between(start, LocalTime.of(22, 0)).toMinutes();
                }
            } else if (day == totalDays) { // 출국일: 출국 시간 - 수속 버퍼(3시간)
                LocalTime end = outTime.minusHours(3);
                if (end.isAfter(LocalTime.of(22, 0))) end = LocalTime.of(22, 0);
                if (end.isAfter(LocalTime.of(9, 0))) {
                    totalMinutes += (int) Duration.between(LocalTime.of(9, 0), end).toMinutes();
                }
            } else { // 중간일: 13시간 (09:00 ~ 22:00)
                totalMinutes += 13 * 60;
            }
        }
        return totalMinutes;
    }

    private LocalTime parseInOutTime(String timeStr, boolean isArrival) {
        if (timeStr == null || timeStr.contains("미정")) {
            return isArrival ? LocalTime.of(9, 0) : LocalTime.of(22, 0);
        }
        if (timeStr.contains("오전")) return LocalTime.of(10, 0);
        if (timeStr.contains("오후")) return LocalTime.of(14, 0);
        if (timeStr.contains("저녁") || timeStr.contains("밤")) return LocalTime.of(19, 0);
        return isArrival ? LocalTime.of(9, 0) : LocalTime.of(22, 0);
    }

    // ============================================================================
    // 사용자 취향 기반 동적 여유 시간(Buffer Time) 로직
    // ============================================================================
    public int calculateBufferTime(PlanRequest request) {
        boolean isTight = request.getThemes() != null && (request.getThemes().contains("액티비티") || request.getThemes().contains("쇼핑"));
        boolean isRelaxed = request.getThemes() != null && request.getThemes().contains("힐링");
        boolean isFamily = "가족".equals(request.getCompanion()) || "부모님".equals(request.getCompanion());

        if (isRelaxed || isFamily) return 30; // Type C: 여유로운 일정
        if (isTight) return 10;               // Type A: 빡빡한 일정
        return 20;                            // Type B: 보통 일정 (기본값)
    }

    // ============================================================================
    // Step 3. 장소 후보 수집 (PlaceCandidateService) - 하드 제약 휴무일 가지치기
    // ============================================================================
    public List<Place> filterClosedPlaces(List<Place> places, LocalDate travelStartDate) {
        int dayOfWeek = travelStartDate.getDayOfWeek().getValue();
        String[] dayNames = {"월요일", "화요일", "수요일", "목요일", "금요일", "토요일", "일요일"};
        String targetDay = dayNames[dayOfWeek - 1];

        return places.stream().filter(p -> {
            String hours = p.getOpeningHours();
            return hours == null || !hours.contains(targetDay + ": 휴무");
        }).collect(Collectors.toList());
    }

    // ============================================================================
    // Step 4. 장소 적합도 계산 (Weighted Scoring Algorithm)
    // ============================================================================
    public List<Place> applyWeightedScoring(List<Place> places, String weather, PlanRequest request) {
        boolean isBadWeather = weather != null && (weather.contains("비") || weather.contains("눈"));
        List<String> themes = request.getThemes() != null ? request.getThemes() : new ArrayList<>();

        Map<Place, Integer> scoreMap = new HashMap<>();

        for (Place p : places) {
            int score = 50; // Base Score

            if (p.getTheme() != null) {
                for (String t : themes) {
                    if (p.getTheme().contains(t)) score += 40;
                }
            }
            if (isBadWeather) {
                if ("실내".equals(p.getPlaceType())) score += 30;
                else if ("실외".equals(p.getPlaceType())) score -= 30;
            }
            if ("가족".equals(request.getCompanion())) {
                if ("관광지".equals(p.getCategory())) score += 20;
                if (p.getTheme() != null && p.getTheme().contains("액티비티")) score -= 50;
            }
            scoreMap.put(p, score);
        }

        return places.stream()
                .sorted((p1, p2) -> scoreMap.get(p2).compareTo(scoreMap.get(p1)))
                .collect(Collectors.toList());
    }

    // ============================================================================
    // Step 5. 방문 장소 선정 (Candidate Selection - 배낭 문제/그리디 기반)
    // ============================================================================
    public List<Place> selectCandidates(List<Place> scoredPlaces, PlanRequest request, int totalDays) {
        int totalAvailableMinutes = calculateTotalPhysicalMinutes(request, totalDays);
        int accumulatedTime = 0;
        int maxBudget = Integer.MAX_VALUE;
        int accumulatedCost = 0;

        List<Place> selected = new ArrayList<>();
        int foodCount = 0, tourCount = 0, shoppingCount = 0;

        boolean isFoodLover = request.getThemes() != null && request.getThemes().stream().anyMatch(t -> t.contains("맛집") || t.contains("식도락"));
        int maxFoodLimit = isFoodLover ? totalDays * 4 : totalDays * 2;

        for (Place p : scoredPlaces) {
            if ("식음".equals(p.getCategory()) && foodCount >= maxFoodLimit) continue;

            int estimatedDwellTime = calculateDwellTime(p, request);
            int bufferTime = calculateBufferTime(request); // 미시적 계산: 버퍼 차감
            int estimatedCost = "테마파크".equals(p.getCategory()) ? 8000 : ("식음".equals(p.getCategory()) ? 3000 : 0);

            if (accumulatedTime + estimatedDwellTime + bufferTime <= totalAvailableMinutes && accumulatedCost + estimatedCost <= maxBudget) {
                selected.add(p);
                accumulatedTime += (estimatedDwellTime + bufferTime);
                accumulatedCost += estimatedCost;

                if ("식음".equals(p.getCategory())) foodCount++;
                else if ("쇼핑".equals(p.getCategory())) shoppingCount++;
                else if ("관광지".equals(p.getCategory())) tourCount++;
            }
        }
        return selected;
    }

    // ============================================================================
    // Step 6. 이동 경로 계산 (TSP with Time Windows + 2-opt) & 지리적 군집 재분배
    // ============================================================================
    public List<List<Place>> calculateTspWithTimeWindows(List<Place> selectedCandidates, int totalDays, List<PlanRequest.AccommodationInput> accs, boolean forceDummyNode, PlanRequest request) {

        // 지리적 군집성을 완벽하게 유지하는 분배 알고리즘 적용
        Map<Integer, List<Place>> clusters = clusterPlacesGeographically(selectedCandidates, totalDays, forceDummyNode, request);

        List<List<Place>> dailyRoutes = new ArrayList<>();

        for (int i = 0; i < totalDays; i++) {
            List<Place> dayPlaces = clusters.get(i);
            if (dayPlaces == null || dayPlaces.isEmpty()) {
                dailyRoutes.add(new ArrayList<>());
                continue;
            }

            List<Place> route = new ArrayList<>();
            List<Place> unvisited = new ArrayList<>(dayPlaces);
            route.add(unvisited.remove(0));

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
                            double tempTotalDist = 0;
                            boolean isTimeWindowViolated = false;
                            List<Place> tempRoute = new ArrayList<>(route);
                            reverseSubList(tempRoute, m, k);

                            for(int idx = 0; idx < tempRoute.size() - 1; idx++) {
                                tempTotalDist += DistanceUtil.calculateDistance(
                                        tempRoute.get(idx).getLatitude(), tempRoute.get(idx).getLongitude(),
                                        tempRoute.get(idx + 1).getLatitude(), tempRoute.get(idx + 1).getLongitude()
                                );
                                if(tempTotalDist > 15.0) {
                                    isTimeWindowViolated = true;
                                    break;
                                }
                            }

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
    public SimulationResult runScheduleSimulation(List<Place> draftRoute, PlanRequest request, int dayNumber, int totalDays, boolean insertDummyNode) {
        SimulationResult result = new SimulationResult();
        List<SimulatedItinerary> validRoute = new ArrayList<>();

        // 입국/출국일 시간에 맞춘 시작/종료 타임라인 동적 계산
        LocalTime currentTime = LocalTime.of(9, 0);
        if (dayNumber == 1) {
            LocalTime inTime = parseInOutTime(request.getInTime(), true).plusHours(2);
            currentTime = inTime.isAfter(currentTime) ? inTime : currentTime;
        }
        LocalTime dayEndTime = LocalTime.of(22, 0);
        if (dayNumber == totalDays) {
            LocalTime outTime = parseInOutTime(request.getOutTime(), false).minusHours(3);
            dayEndTime = outTime.isBefore(dayEndTime) ? outTime : dayEndTime;
        }

        Place prevPlace = null;
        int currentBudgetUsed = 0;
        int maxBudget = Integer.MAX_VALUE;

        for (Place p : draftRoute) {
            int transitMinutes = 0;
            if (prevPlace != null) {
                double distKm = DistanceUtil.calculateDistance(
                        prevPlace.getLatitude(), prevPlace.getLongitude(),
                        p.getLatitude(), p.getLongitude()
                );
                transitMinutes = (int) Math.round((distKm / 20.0) * 60.0);
                transitMinutes = (int) (Math.max(transitMinutes, 10) * 1.2); // 이동 시간 20% 마진
            }
            currentTime = currentTime.plusMinutes(transitMinutes);

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

            LocalTime closeTime = parseCloseTime(p.getOpeningHours());
            if (currentTime.isAfter(closeTime)) {
                result.setSuccess(false);
                result.setReason("장소 운영시간(Time-Window) 충돌: " + p.getName() + " 도착 시 영업 마감");
                result.setProblemPlace(p);
                return result;
            }

            int dwellTime = calculateDwellTime(p, request);
            int bufferTime = calculateBufferTime(request); // 동적 여유 시간
            int estimatedCost = "테마파크".equals(p.getCategory()) ? 8000 : ("식음".equals(p.getCategory()) ? 3000 : 0);

            currentBudgetUsed += estimatedCost;
            if (currentBudgetUsed > maxBudget) {
                result.setSuccess(false);
                result.setReason(p.getName() + " 방문 시 유저가 설정한 예산을 초과합니다.");
                result.setProblemPlace(p);
                return result;
            }

            validRoute.add(new SimulatedItinerary(p, currentTime.toString()));
            currentTime = currentTime.plusMinutes(dwellTime).plusMinutes(bufferTime);

            if (currentTime.isAfter(dayEndTime)) {
                result.setSuccess(false);
                result.setReason("여행 한계 시간 초과");
                result.setProblemPlace(p);
                return result;
            }
            prevPlace = p;
        }

        // 데이터 부족 시 가상 블록(Dummy Node) 삽입 로직
        if (insertDummyNode && currentTime.isBefore(dayEndTime.minusHours(2))) {
            Place dummyNode = new Place();
            dummyNode.setName("[자유 시간 및 로컬 탐방]");
            dummyNode.setCategory("자유시간");
            dummyNode.setTheme("힐링,산책");
            // 거리 뻥튀기를 막기 위해 이전 장소의 좌표를 물려받음
            dummyNode.setLatitude(prevPlace != null ? prevPlace.getLatitude() : 0.0);
            dummyNode.setLongitude(prevPlace != null ? prevPlace.getLongitude() : 0.0);

            validRoute.add(new SimulatedItinerary(dummyNode, currentTime.toString()));
        }

        result.setSuccess(true);
        result.setValidRoute(validRoute);
        return result;
    }


    // ---------------- 내부 알고리즘 유틸리티 메서드 ----------------

    // 지리적 군집 유지 및 시간 기반 배낭 분배 알고리즘
    private Map<Integer, List<Place>> clusterPlacesGeographically(List<Place> places, int kDays, boolean forceDummyNode, PlanRequest request) {
        Map<Integer, List<Place>> clusters = new HashMap<>();
        for (int i = 0; i < kDays; i++) clusters.put(i, new ArrayList<>());
        if (places.isEmpty()) return clusters;

        double minLat = places.stream().mapToDouble(Place::getLatitude).min().orElse(0);
        double maxLat = places.stream().mapToDouble(Place::getLatitude).max().orElse(0);
        double minLng = places.stream().mapToDouble(Place::getLongitude).min().orElse(0);
        double maxLng = places.stream().mapToDouble(Place::getLongitude).max().orElse(0);

        List<Place> sortedPlaces = new ArrayList<>(places);

        if ((maxLat - minLat) > (maxLng - minLng)) {
            sortedPlaces.sort(Comparator.comparingDouble(Place::getLatitude));
        } else {
            sortedPlaces.sort(Comparator.comparingDouble(Place::getLongitude));
        }

        if (forceDummyNode) {
            // [데이터 기근 상황 - 기획 5-나] 지리적 정렬 순서를 유지한 채 1/N로 균등하게 깍둑썰기(Chunking)
            int placesPerDay = (int) Math.ceil((double) sortedPlaces.size() / kDays);
            for (int i = 0; i < sortedPlaces.size(); i++) {
                int dayIndex = Math.min(i / placesPerDay, kDays - 1);
                clusters.get(dayIndex).add(sortedPlaces.get(i));
            }
        } else {
            // [일반 상황 - 기획 1-나] 개수(4~5개)가 아닌 '시간 총량' 기준으로 일차별 배낭을 꽉꽉 채워 배정
            int currentDay = 0;
            int currentDayTime = 0;
            int dailyMaxMinutes = calculateTotalPhysicalMinutes(request, kDays) / kDays; // 하루 평균 물리적 가용 시간

            for (Place p : sortedPlaces) {
                // 해당 장소 소요 시간 = 체류 시간 + 사용자 취향별 여유 시간 + 기본 이동 시간 추정치(약 30분)
                int costTime = calculateDwellTime(p, request) + calculateBufferTime(request) + 30;

                // 배낭 용량(일일 시간)이 넘치면 다음 날로 넘김 (마지막 날 제외)
                if (currentDayTime + costTime > dailyMaxMinutes && currentDay < kDays - 1) {
                    currentDay++;
                    currentDayTime = 0;
                }
                clusters.get(currentDay).add(p);
                currentDayTime += costTime;
            }
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

    public int calculateDwellTime(Place p, PlanRequest request) {
        int time = 90;
        if ("쇼핑".equals(p.getCategory())) time = 120;
        else if ("테마파크".equals(p.getCategory()) || p.getName().contains("유니버셜") || p.getName().contains("디즈니")) time = 480;
        else if ("식음".equals(p.getCategory())) time = 60;
        else if ("자유시간".equals(p.getCategory())) return 120; // 가상 블록 기본 시간

        if ("가족".equals(request.getCompanion()) || (request.getThemes() != null && request.getThemes().contains("힐링"))) {
            time = (int)(time * 1.2);
        }
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