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
        for (int day = 1; day <= totalDays; day++) {
            totalMinutes += getDailyPhysicalMinutes(request, day, totalDays);
        }
        return totalMinutes;
    }

    public int getDailyPhysicalMinutes(PlanRequest request, int day, int totalDays) {
        LocalTime inTime = parseInOutTime(request.getInTime(), true);
        LocalTime outTime = parseInOutTime(request.getOutTime(), false);

        if (day == 1) { // 입국일
            LocalTime start = inTime.plusHours(2);
            if (start.isBefore(LocalTime.of(9, 0))) start = LocalTime.of(9, 0);
            if (start.isAfter(LocalTime.of(22, 0))) return 0;
            return (int) Duration.between(start, LocalTime.of(22, 0)).toMinutes();
        } else if (day == totalDays) { // 출국일
            LocalTime end = outTime.minusHours(3);
            if (end.isAfter(LocalTime.of(22, 0))) end = LocalTime.of(22, 0);
            if (end.isBefore(LocalTime.of(9, 0))) return 0;
            return (int) Duration.between(LocalTime.of(9, 0), end).toMinutes();
        } else { // 중간일
            return 13 * 60; // 09:00 ~ 22:00
        }
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

    public int calculateBufferTime(PlanRequest request) {
        boolean isTight = request.getThemes() != null && (request.getThemes().contains("액티비티") || request.getThemes().contains("쇼핑"));
        boolean isRelaxed = request.getThemes() != null && request.getThemes().contains("힐링");
        boolean isFamily = "가족".equals(request.getCompanion()) || "부모님".equals(request.getCompanion());

        if (isRelaxed || isFamily) return 30;
        if (isTight) return 10;
        return 20;
    }

    public List<Place> filterClosedPlaces(List<Place> places, LocalDate travelStartDate) {
        int dayOfWeek = travelStartDate.getDayOfWeek().getValue();
        String[] dayNames = {"월요일", "화요일", "수요일", "목요일", "금요일", "토요일", "일요일"};
        String targetDay = dayNames[dayOfWeek - 1];

        return places.stream().filter(p -> {
            String hours = p.getOpeningHours();
            return hours == null || !hours.contains(targetDay + ": 휴무");
        }).collect(Collectors.toList());
    }

    public List<Place> applyWeightedScoring(List<Place> places, String weather, PlanRequest request) {
        boolean isBadWeather = weather != null && (weather.contains("비") || weather.contains("눈"));
        List<String> themes = request.getThemes() != null ? request.getThemes() : new ArrayList<>();
        String inCity = request.getInCity() != null ? request.getInCity() : "";

        Map<Place, Integer> scoreMap = new HashMap<>();

        double baseLat = places.isEmpty() ? 0 : places.get(0).getLatitude();
        double baseLng = places.isEmpty() ? 0 : places.get(0).getLongitude();

        if (request.getAccommodations() != null && !request.getAccommodations().isEmpty() && request.getAccommodations().get(0).getCheckIn() != null) {
            // 프론트에서 넘어온 숙소 객체가 있다면 처리 (생략)
        } else {
            for (Place p : places) {
                if (p.getName().contains("역") || p.getName().contains("Station")) {
                    baseLat = p.getLatitude();
                    baseLng = p.getLongitude();
                    break;
                }
            }
        }

        for (Place p : places) {
            int score = 50;

            if (p.getName().contains("공항")) {
                boolean isExceptionalAirport =
                        (inCity.contains("도쿄") && (p.getName().contains("나리타") || p.getName().contains("하네다"))) ||
                                (inCity.contains("오사카") && p.getName().contains("간사이")) ||
                                (inCity.contains("삿포로") && p.getName().contains("신치토세")) ||
                                p.getName().contains(inCity);

                if (isExceptionalAirport) score += 5000;
                else score -= 1000;
            }

            if (p.getTheme() != null) {
                for (String t : themes) {
                    if (p.getTheme().contains(t)) score += 60;
                }
            }

            if (isBadWeather) {
                if ("실내".equals(p.getPlaceType())) score += 50;
                else if ("실외".equals(p.getPlaceType())) score -= 50;
            }
            if ("가족".equals(request.getCompanion())) {
                if ("관광지".equals(p.getCategory())) score += 40;
                if (p.getTheme() != null && p.getTheme().contains("액티비티")) score -= 50;
            }

            if (baseLat != 0 && baseLng != 0 && !p.getName().contains("공항")) {
                double distKm = DistanceUtil.calculateDistance(baseLat, baseLng, p.getLatitude(), p.getLongitude());
                if (distKm > 10.0) {
                    score -= (int)((distKm - 10) * 5);
                }
            }

            scoreMap.put(p, score);
        }

        return places.stream()
                .filter(p -> scoreMap.get(p) > 0)
                .sorted((p1, p2) -> scoreMap.get(p2).compareTo(scoreMap.get(p1)))
                .collect(Collectors.toList());
    }

    public List<Place> selectCandidates(List<Place> scoredPlaces, PlanRequest request, int totalDays) {
        int totalAvailableMinutes = calculateTotalPhysicalMinutes(request, totalDays);
        int accumulatedTime = 0;
        int maxBudget = Integer.MAX_VALUE;
        int accumulatedCost = 0;

        List<Place> selected = new ArrayList<>();
        int foodCount = 0;
        boolean isFoodLover = request.getThemes() != null && request.getThemes().stream().anyMatch(t -> t.contains("맛집") || t.contains("식도락"));
        int maxFoodLimit = isFoodLover ? totalDays * 3 : totalDays * 2;

        for (Place p : scoredPlaces) {
            if (p.getName().contains("공항")) {
                selected.add(p);
            }
        }

        for (Place p : scoredPlaces) {
            if (p.getName().contains("공항")) continue;
            if ("식음".equals(p.getCategory()) && foodCount >= maxFoodLimit) continue;

            int estimatedDwellTime = calculateDwellTime(p, request);
            int bufferTime = calculateBufferTime(request);
            int estimatedCost = "테마파크".equals(p.getCategory()) ? 8000 : ("식음".equals(p.getCategory()) ? 3000 : 0);

            if (accumulatedTime + estimatedDwellTime + bufferTime <= totalAvailableMinutes && accumulatedCost + estimatedCost <= maxBudget) {
                selected.add(p);
                accumulatedTime += (estimatedDwellTime + bufferTime);
                accumulatedCost += estimatedCost;

                if ("식음".equals(p.getCategory())) foodCount++;
            }
        }
        return selected;
    }

    public List<List<Place>> calculateTspWithTimeWindows(List<Place> selectedCandidates, int totalDays, List<PlanRequest.AccommodationInput> accs, boolean forceDummyNode, PlanRequest request) {
        Place airport = selectedCandidates.stream()
                .filter(p -> p.getName().contains("공항"))
                .findFirst().orElse(null);
        if (airport != null) {
            selectedCandidates.remove(airport);
        }

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

            if (airport != null) {
                if (i == 0) {
                    route.add(0, airport);
                } else if (i == totalDays - 1) {
                    route.add(airport);
                }
            }

            dailyRoutes.add(route);
        }
        return dailyRoutes;
    }

    public SimulationResult runScheduleSimulation(List<Place> draftRoute, PlanRequest request, int dayNumber, int totalDays, boolean insertDummyNode) {
        SimulationResult result = new SimulationResult();
        List<SimulatedItinerary> validRoute = new ArrayList<>();

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
        int dailyFoodCount = 0;

        for (Place p : draftRoute) {
            if (p.getName().contains("공항")) {
                if (dayNumber == 1) {
                    validRoute.add(new SimulatedItinerary(p, currentTime.toString()));
                    prevPlace = p;
                    continue;
                } else if (dayNumber == totalDays) {
                    int transitMinutes = 0;
                    if (prevPlace != null) {
                        double distKm = DistanceUtil.calculateDistance(
                                prevPlace.getLatitude(), prevPlace.getLongitude(),
                                p.getLatitude(), p.getLongitude()
                        );
                        transitMinutes = (int) Math.round((distKm / 20.0) * 60.0);
                        transitMinutes = (int) (Math.max(transitMinutes, 10) * 1.2);
                    }
                    currentTime = currentTime.plusMinutes(transitMinutes);
                    validRoute.add(new SimulatedItinerary(p, currentTime.toString()));
                    continue;
                }
            }

            int transitMinutes = 0;
            if (prevPlace != null) {
                double distKm = DistanceUtil.calculateDistance(
                        prevPlace.getLatitude(), prevPlace.getLongitude(),
                        p.getLatitude(), p.getLongitude()
                );
                transitMinutes = (int) Math.round((distKm / 20.0) * 60.0);
                transitMinutes = (int) (Math.max(transitMinutes, 10) * 1.2);
            }
            LocalTime arrivalTime = currentTime.plusMinutes(transitMinutes);

            if (arrivalTime.isBefore(currentTime) || arrivalTime.isAfter(dayEndTime)) {
                continue;
            }
            currentTime = arrivalTime;

            if (request.getFixedSchedules() != null) {
                boolean hasConflict = false;
                for (PlanRequest.FixedScheduleInput fixed : request.getFixedSchedules()) {
                    if (currentTime.isAfter(fixed.getStartTime().minusMinutes(30)) && currentTime.isBefore(fixed.getEndTime())) {
                        hasConflict = true;
                        break;
                    }
                }
                if (hasConflict) continue;
            }

            if ("식음".equals(p.getCategory())) {
                if (dailyFoodCount >= 2) continue;
                if (prevPlace != null && "식음".equals(prevPlace.getCategory())) continue;
            }

            boolean isNightSpot = p.getName().contains("오뎅") || p.getName().contains("이자카야") || p.getName().contains("술") || (p.getTheme() != null && p.getTheme().contains("야경"));
            if (isNightSpot && currentTime.isBefore(LocalTime.of(17, 0))) {
                continue;
            }

            // 문자열에서 오픈 시간과 마감 시간을 모두 추출
            LocalTime openTime = parseOpenTime(p.getOpeningHours());
            LocalTime closeTime = parseCloseTime(p.getOpeningHours());

            int dwellTime = calculateDwellTime(p, request);
            int bufferTime = calculateBufferTime(request);
            int estimatedCost = "테마파크".equals(p.getCategory()) ? 8000 : ("식음".equals(p.getCategory()) ? 3000 : 0);
            LocalTime finishTime = currentTime.plusMinutes(dwellTime).plusMinutes(bufferTime);

            // 도착 시간이 오픈 전이거나, 일정을 끝내고 나올 시간이 마감 시간 이후면 스킵
            if (currentTime.isBefore(openTime) || finishTime.isAfter(closeTime) || finishTime.isBefore(currentTime) || finishTime.isAfter(dayEndTime)) {
                continue;
            }

            currentBudgetUsed += estimatedCost;
            if (currentBudgetUsed > maxBudget) {
                continue;
            }

            if ("식음".equals(p.getCategory())) dailyFoodCount++;

            validRoute.add(new SimulatedItinerary(p, currentTime.toString()));
            currentTime = finishTime;
            prevPlace = p;
        }

        if (insertDummyNode && currentTime.isBefore(dayEndTime.minusHours(2))) {
            Place dummyNode = new Place();
            dummyNode.setName("[자유 시간 및 로컬 탐방]");
            dummyNode.setCategory("자유시간");
            dummyNode.setTheme("힐링,산책");
            dummyNode.setLatitude(prevPlace != null ? prevPlace.getLatitude() : 0.0);
            dummyNode.setLongitude(prevPlace != null ? prevPlace.getLongitude() : 0.0);

            validRoute.add(new SimulatedItinerary(dummyNode, currentTime.toString()));
        }

        result.setSuccess(true);
        result.setValidRoute(validRoute);
        return result;
    }

    // ============================================================================
    // 문자열 분해 및 오픈/마감 시간 추출 유틸리티
    // ============================================================================

    private LocalTime parseOpenTime(String hours) {
        if (hours == null || hours.contains("없음") || hours.contains("24시간")) return LocalTime.of(0, 0);
        try {
            // 예: "월요일: 오전 11:00 ~ 오후 5:00 | 화요일: ..." -> 첫 번째 요일 블록 추출
            String firstDay = hours.split("\\|")[0];
            String timeRange = firstDay.substring(firstDay.indexOf(":") + 1).trim();
            String openStr = timeRange.split("~|-")[0].trim(); // "오전 11:00" 분리

            return extractTime(openStr);
        } catch (Exception e) {}
        return LocalTime.of(0, 0); // 파싱 실패 시 상시 오픈으로 간주
    }

    private LocalTime parseCloseTime(String hours) {
        if (hours == null || hours.contains("없음") || hours.contains("24시간")) return LocalTime.of(23, 59);
        try {
            String firstDay = hours.split("\\|")[0];
            String timeRange = firstDay.substring(firstDay.indexOf(":") + 1).trim();
            String closeStr = timeRange.split("~|-")[1].trim(); // "오후 5:00" 분리

            return extractTime(closeStr);
        } catch (Exception e) {}
        return LocalTime.of(23, 59); // 파싱 실패 시 밤 23:59 마감으로 간주
    }

    private LocalTime extractTime(String timeStr) {
        boolean isPM = timeStr.contains("오후") || timeStr.toUpperCase().contains("PM");
        String timePart = timeStr.replaceAll("[^0-9:]", "").trim();
        if (timePart.isEmpty()) return LocalTime.of(0, 0);

        String[] t = timePart.split(":");
        int hour = Integer.parseInt(t[0]);
        int minute = t.length > 1 ? Integer.parseInt(t[1]) : 0;

        if (isPM && hour < 12) hour += 12;
        if (!isPM && hour == 12) hour = 0;

        return LocalTime.of(hour, minute);
    }

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
            int placesPerDay = (int) Math.ceil((double) sortedPlaces.size() / kDays);
            for (int i = 0; i < sortedPlaces.size(); i++) {
                int dayIndex = Math.min(i / placesPerDay, kDays - 1);
                clusters.get(dayIndex).add(sortedPlaces.get(i));
            }
        } else {
            int currentDay = 0;
            int currentDayTime = 0;
            int dailyMaxMinutes = getDailyPhysicalMinutes(request, currentDay + 1, kDays);

            for (Place p : sortedPlaces) {
                int costTime = calculateDwellTime(p, request) + calculateBufferTime(request) + 30;

                if (currentDayTime + costTime > dailyMaxMinutes && currentDay < kDays - 1) {
                    currentDay++;
                    currentDayTime = 0;
                    dailyMaxMinutes = getDailyPhysicalMinutes(request, currentDay + 1, kDays);
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

        if (p.getRecommendedDuration() != null && p.getRecommendedDuration() > 0) {
            time = p.getRecommendedDuration();
        } else {
            if ("쇼핑".equals(p.getCategory())) time = 120;
            else if ("테마파크".equals(p.getCategory()) || p.getName().contains("유니버셜") || p.getName().contains("디즈니")) time = 480;
            else if ("식음".equals(p.getCategory())) time = 60;
            else if ("자유시간".equals(p.getCategory())) return 120;
        }

        if ("가족".equals(request.getCompanion()) || (request.getThemes() != null && request.getThemes().contains("힐링"))) {
            time = (int)(time * 1.2);
        }
        return time;
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