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

    private LocalTime getDailyStart(PlanRequest request) {
        return request.getPreferredStartTime() != null ? request.getPreferredStartTime() : LocalTime.of(9, 0);
    }

    private LocalTime getDailyEnd(PlanRequest request) {
        return request.getPreferredEndTime() != null ? request.getPreferredEndTime() : LocalTime.of(22, 0);
    }

    public int calculateTotalPhysicalMinutes(PlanRequest request, int totalDays) {
        int totalMinutes = 0;
        for (int day = 1; day <= totalDays; day++) {
            totalMinutes += getDailyPhysicalMinutes(request, day, totalDays);
        }
        return totalMinutes;
    }

    public int getDailyPhysicalMinutes(PlanRequest request, int day, int totalDays) {
        LocalTime inTime = parseInOutTime(request.getInTime(), true, request);
        LocalTime outTime = parseInOutTime(request.getOutTime(), false, request);

        LocalTime defaultStart = getDailyStart(request);
        LocalTime defaultEnd = getDailyEnd(request);

        if (day == 1) {
            LocalTime start = inTime.plusHours(2);
            if (start.isBefore(defaultStart)) start = defaultStart;
            if (start.isAfter(defaultEnd)) return 0;
            return (int) Duration.between(start, defaultEnd).toMinutes();
        } else if (day == totalDays) {
            LocalTime end = outTime.minusHours(3);
            if (end.isAfter(defaultEnd)) end = defaultEnd;
            if (end.isBefore(defaultStart)) return 0;
            return (int) Duration.between(defaultStart, end).toMinutes();
        } else {
            return (int) Duration.between(defaultStart, defaultEnd).toMinutes();
        }
    }

    private LocalTime parseInOutTime(String timeStr, boolean isArrival, PlanRequest request) {
        if (timeStr == null || timeStr.contains("미정")) {
            return isArrival ? getDailyStart(request) : getDailyEnd(request);
        }
        if (timeStr.contains("오전")) return LocalTime.of(10, 0);
        if (timeStr.contains("오후")) return LocalTime.of(14, 0);
        if (timeStr.contains("저녁") || timeStr.contains("밤")) return LocalTime.of(19, 0);
        return isArrival ? getDailyStart(request) : getDailyEnd(request);
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
        List<String> excludedThemes = request.getExcludedThemes() != null ? request.getExcludedThemes() : new ArrayList<>();
        String inCity = request.getInCity() != null ? request.getInCity() : "";

        Map<Place, Integer> scoreMap = new HashMap<>();

        double baseLat = places.isEmpty() ? 0 : places.get(0).getLatitude();
        double baseLng = places.isEmpty() ? 0 : places.get(0).getLongitude();

        if (request.getAccommodations() != null && !request.getAccommodations().isEmpty() && request.getAccommodations().get(0).getCheckIn() != null) {
            // 프론트에서 넘어온 숙소가 있으면 기준 좌표로 세팅 (생략)
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
            boolean isExcluded = false;
            if (p.getTheme() != null && !excludedThemes.isEmpty()) {
                for (String ex : excludedThemes) {
                    if (p.getTheme().contains(ex)) {
                        isExcluded = true;
                        break;
                    }
                }
            }
            if (isExcluded) continue;

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

        return scoreMap.keySet().stream()
                .filter(p -> scoreMap.get(p) > 0)
                .sorted((p1, p2) -> scoreMap.get(p2).compareTo(scoreMap.get(p1)))
                .collect(Collectors.toList());
    }

    public List<Place> selectCandidates(List<Place> scoredPlaces, PlanRequest request, int totalDays) {
        int totalAvailableMinutes = (int) (calculateTotalPhysicalMinutes(request, totalDays) * 3.0);
        int accumulatedTime = 0;
        int maxBudget = Integer.MAX_VALUE;
        int accumulatedCost = 0;

        List<Place> selected = new ArrayList<>();
        int foodCount = 0;
        boolean isFoodLover = request.getThemes() != null && request.getThemes().stream().anyMatch(t -> t.contains("맛집") || t.contains("식도락") || t.contains("카페"));
        int maxFoodLimit = isFoodLover ? totalDays * 4 : totalDays * 3;

        for (Place p : scoredPlaces) {
            if (p.getName().contains("공항")) {
                selected.add(p);
            }
        }

        for (Place p : scoredPlaces) {
            if (p.getName().contains("공항")) continue;
            if ("식음".equals(p.getCategory()) && foodCount >= maxFoodLimit) continue;

            // AI가 긁어온 수많은 호텔과 기차역들이 일반 관광지인 척하고 후보에 끼어드는 것을 원천 차단합니다.
            if ("숙소".equals(p.getCategory()) || "교통".equals(p.getCategory())) continue;

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

    // ============================================================================
    // 베이스캠프(숙소) 우선 확보 + 탐욕적 시간 채우기 (Greedy Time-Filler) 라우팅
    // ============================================================================
    public List<List<Place>> calculateTspWithTimeWindows(List<Place> selectedCandidates, int totalDays, Place baseCamp, boolean forceDummyNode, PlanRequest request) {
        List<List<Place>> dailyRoutes = new ArrayList<>();
        List<Place> unvisited = new ArrayList<>(selectedCandidates);

        // 1. 공항 분리
        Place startAirport = unvisited.stream()
                .filter(p -> p.getName().contains("공항"))
                .findFirst().orElse(null);
        if (startAirport != null) {
            unvisited.remove(startAirport);
        }

        // 2. 관광지 후보에 숙소가 섞여 있다면 중복 방지를 위해 제거
        if (baseCamp != null) {
            unvisited.removeIf(p -> p.getPlaceId().equals(baseCamp.getPlaceId()));
        }

        // 3. 일차별로 타임라인 꽉 채우기
        for (int day = 0; day < totalDays; day++) {
            List<Place> dayRoute = new ArrayList<>();
            int currentDayMinutes = 0;
            int maxMinutes = getDailyPhysicalMinutes(request, day + 1, totalDays);
            Place currentLoc = null;

            // 1일 차는 공항, 그 이후는 무조건 숙소에서 출발
            if (day == 0 && startAirport != null) {
                dayRoute.add(startAirport);
                currentLoc = startAirport;
            } else if (baseCamp != null) {
                dayRoute.add(baseCamp);
                currentLoc = baseCamp;
            }

            // 하루 일과 시간이 끝날 때까지 무한 루프
            while (!unvisited.isEmpty() && currentDayMinutes < maxMinutes) {
                Place nearest = null;
                double minCost = Double.MAX_VALUE;

                // 내 현재 위치에서 가장 가까운 곳 탐색
                if (currentLoc == null || (currentLoc.getLatitude() == 0.0 && currentLoc.getLongitude() == 0.0)) {
                    nearest = unvisited.get(0);
                } else {
                    for (Place candidate : unvisited) {
                        double dist = DistanceUtil.calculateDistance(
                                currentLoc.getLatitude(), currentLoc.getLongitude(),
                                candidate.getLatitude(), candidate.getLongitude()
                        );
                        if (dist < minCost) {
                            minCost = dist;
                            nearest = candidate;
                        }
                    }
                }

                // 이동 시간(30분) + 체류 시간 + 버퍼 시간 합산
                int costTime = calculateDwellTime(nearest, request) + calculateBufferTime(request) + (currentLoc != null ? 30 : 0);

                // 일과 시간이 초과되면 오늘 일정 종료
                if (currentDayMinutes + costTime > maxMinutes) {
                    break;
                }

                dayRoute.add(nearest);
                unvisited.remove(nearest);
                currentDayMinutes += costTime;
                currentLoc = nearest;
            }

            // [도착지 강제 고정] 마지막 날은 공항으로, 일반 날은 무조건 숙소로 귀환
            if (day == totalDays - 1 && startAirport != null) {
                dayRoute.add(startAirport);
            } else if (baseCamp != null) {
                dayRoute.add(baseCamp);
            }

            dailyRoutes.add(dayRoute);
        }

        return dailyRoutes;
    }

    // ============================================================================
    // 앵커(숙소/공항) 기반 시뮬레이터
    // ============================================================================
    public SimulationResult runScheduleSimulation(List<Place> draftRoute, PlanRequest request, int dayNumber, int totalDays, boolean insertDummyNode) {
        SimulationResult result = new SimulationResult();
        List<SimulatedItinerary> validRoute = new ArrayList<>();

        LocalTime defaultStart = getDailyStart(request);
        LocalTime defaultEnd = getDailyEnd(request);

        LocalTime currentTime = defaultStart;
        if (dayNumber == 1) {
            LocalTime inTime = parseInOutTime(request.getInTime(), true, request).plusHours(2);
            currentTime = inTime.isAfter(currentTime) ? inTime : currentTime;
        }

        LocalTime dayEndTime = defaultEnd;
        if (dayNumber == totalDays) {
            LocalTime outTime = parseInOutTime(request.getOutTime(), false, request).minusHours(3);
            dayEndTime = outTime.isBefore(dayEndTime) ? outTime : dayEndTime;
        }

        Place prevPlace = null;
        int currentBudgetUsed = 0;
        int maxBudget = Integer.MAX_VALUE;
        int dailyFoodCount = 0;
        int dailyShoppingCount = 0;

        boolean isShoppingLover = request.getThemes() != null && request.getThemes().contains("쇼핑");
        int maxShoppingLimit = isShoppingLover ? 3 : 1;

        boolean isFoodLover = request.getThemes() != null && (request.getThemes().contains("맛집") || request.getThemes().contains("카페"));
        int maxFoodLimit = isFoodLover ? 4 : 3;

        for (Place p : draftRoute) {
            // 숙소와 공항은 앵커(Anchor)로서 시간 제약을 무시하고 보호받습니다.
            boolean isAnchor = "숙소".equals(p.getCategory()) || p.getName().contains("공항");

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

            // 해당 날짜의 첫 번째 장소(출발지)라면 출발 시간을 현재 시간으로 픽스
            if (validRoute.isEmpty()) {
                arrivalTime = currentTime;
            }

            // 일반 관광지라면 일과 시간 범위를 벗어날 경우 패스
            if (!isAnchor) {
                if (arrivalTime.isBefore(currentTime) || arrivalTime.isAfter(dayEndTime)) {
                    continue;
                }
            }
            currentTime = arrivalTime;

            if (request.getFixedSchedules() != null && !isAnchor) {
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
                if (dailyFoodCount >= maxFoodLimit) continue;
                if (prevPlace != null && "식음".equals(prevPlace.getCategory())) {
                    boolean isCurrentCafe = (p.getTheme() != null && p.getTheme().contains("카페")) || p.getName().toLowerCase().contains("cafe") || p.getName().contains("커피");
                    boolean isPrevCafe = (prevPlace.getTheme() != null && prevPlace.getTheme().contains("카페")) || prevPlace.getName().toLowerCase().contains("cafe") || prevPlace.getName().contains("커피");
                    if (isCurrentCafe == isPrevCafe) continue;
                }
            }

            if ("쇼핑".equals(p.getCategory())) {
                if (dailyShoppingCount >= maxShoppingLimit) continue;
            }

            boolean isNightSpot = p.getName().contains("오뎅") || p.getName().contains("이자카야") || p.getName().contains("술") || (p.getTheme() != null && p.getTheme().contains("야경"));
            if (isNightSpot && currentTime.isBefore(LocalTime.of(17, 0))) {
                currentTime = LocalTime.of(17, 30);
            }

            LocalTime openTime = parseOpenTime(p.getOpeningHours(), request, dayNumber);
            LocalTime closeTime = parseCloseTime(p.getOpeningHours(), request, dayNumber);

            // 앵커(숙소, 공항)는 내부 체류 시간을 0으로 계산하여 붕괴 방지
            int dwellTime = isAnchor ? 0 : calculateDwellTime(p, request);
            int bufferTime = isAnchor ? 0 : calculateBufferTime(request);
            int estimatedCost = "테마파크".equals(p.getCategory()) ? 8000 : ("식음".equals(p.getCategory()) ? 3000 : 0);
            LocalTime finishTime = currentTime.plusMinutes(dwellTime).plusMinutes(bufferTime);

            if (!isAnchor) {
                // 오픈시간 전이거나 영업 종료 이후면 스킵
                if (currentTime.isBefore(openTime) || finishTime.isAfter(closeTime) || finishTime.isBefore(currentTime) || finishTime.isAfter(dayEndTime)) {
                    continue;
                }
                currentBudgetUsed += estimatedCost;
                if (currentBudgetUsed > maxBudget) continue;
            }

            if ("식음".equals(p.getCategory())) dailyFoodCount++;
            if ("쇼핑".equals(p.getCategory())) dailyShoppingCount++;

            validRoute.add(new SimulatedItinerary(p, currentTime.toString()));
            currentTime = finishTime;
            prevPlace = p;
        }

        // 일정이 지나치게 일찍 끝나버렸고, 유저가 자유시간을 허용했다면 더미 노드 1개만 안전하게 추가
        if (validRoute.isEmpty() || (insertDummyNode && currentTime.isBefore(LocalTime.of(15, 0)))) {
            Place dummyNode = new Place();
            dummyNode.setName("[자유 시간 및 로컬 탐방]");
            dummyNode.setCategory("자유시간");
            dummyNode.setTheme("힐링,산책");
            dummyNode.setLatitude(prevPlace != null && prevPlace.getLatitude() != null && prevPlace.getLatitude() != 0.0 ? prevPlace.getLatitude() : 0.0);
            dummyNode.setLongitude(prevPlace != null && prevPlace.getLongitude() != null && prevPlace.getLongitude() != 0.0 ? prevPlace.getLongitude() : 0.0);

            validRoute.add(new SimulatedItinerary(dummyNode, currentTime.toString()));
        }

        result.setSuccess(true);
        result.setValidRoute(validRoute);
        return result;
    }

    // ============================================================================
    // 문자열 분해 및 오픈/마감 시간 추출 유틸리티 (실제 방문 요일 매칭 완벽 지원)
    // ============================================================================
    private LocalTime parseOpenTime(String hours, PlanRequest request, int dayNumber) {
        if (hours != null && hours.contains("24시간")) return LocalTime.of(0, 0);
        if (hours == null || hours.isEmpty() || hours.contains("없음")) return LocalTime.of(9, 0);

        try {
            java.time.LocalDate targetDate = request.getStartDate().plusDays(dayNumber - 1);
            String[] dayNames = {"월요일", "화요일", "수요일", "목요일", "금요일", "토요일", "일요일"};
            String targetDay = dayNames[targetDate.getDayOfWeek().getValue() - 1];

            String targetDailyHours = "";
            for (String dailyStr : hours.split("\\|")) {
                if (dailyStr.contains(targetDay)) {
                    targetDailyHours = dailyStr;
                    break;
                }
            }
            if (targetDailyHours.isEmpty()) targetDailyHours = hours.split("\\|")[0];

            // 오늘이 휴무일이면 시뮬레이터에서 무조건 탈락되도록 오픈 시간을 밤 11시 59분으로 둔갑시킴
            if (targetDailyHours.contains("휴무")) return LocalTime.of(23, 59);

            String timeRange = targetDailyHours.substring(targetDailyHours.indexOf(":") + 1).trim();
            String openStr = timeRange.split("~|-")[0].trim();
            return extractTime(openStr);
        } catch (Exception e) {}

        return LocalTime.of(9, 0);
    }

    private LocalTime parseCloseTime(String hours, PlanRequest request, int dayNumber) {
        if (hours != null && hours.contains("24시간")) return LocalTime.of(23, 59);
        if (hours == null || hours.isEmpty() || hours.contains("없음")) return LocalTime.of(22, 0);

        try {
            java.time.LocalDate targetDate = request.getStartDate().plusDays(dayNumber - 1);
            String[] dayNames = {"월요일", "화요일", "수요일", "목요일", "금요일", "토요일", "일요일"};
            String targetDay = dayNames[targetDate.getDayOfWeek().getValue() - 1];

            String targetDailyHours = "";
            for (String dailyStr : hours.split("\\|")) {
                if (dailyStr.contains(targetDay)) {
                    targetDailyHours = dailyStr;
                    break;
                }
            }
            if (targetDailyHours.isEmpty()) targetDailyHours = hours.split("\\|")[0];

            // 오늘이 휴무일이면 시뮬레이터에서 무조건 탈락되도록 마감 시간을 자정으로 둔갑시킴
            if (targetDailyHours.contains("휴무")) return LocalTime.of(0, 0);

            String timeRange = targetDailyHours.substring(targetDailyHours.indexOf(":") + 1).trim();
            String closeStr = timeRange.split("~|-")[1].trim();
            return extractTime(closeStr);
        } catch (Exception e) {}

        return LocalTime.of(22, 0);
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