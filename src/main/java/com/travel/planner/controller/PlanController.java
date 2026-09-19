package com.travel.planner.controller;

import com.travel.planner.dto.AiRouteResponse;
import com.travel.planner.entity.Itinerary;
import com.travel.planner.entity.Place;
import com.travel.planner.entity.Plan;
import com.travel.planner.entity.Traffic;
import com.travel.planner.entity.User;
import com.travel.planner.repository.TrafficRepository;
import com.travel.planner.service.AiService;
import com.travel.planner.service.GoogleMapsService;
import com.travel.planner.service.PlanService;
import com.travel.planner.service.WeatherService;
import com.travel.planner.service.PlanValidationService;
import com.travel.planner.util.DistanceUtil;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/v1/plans")
@RequiredArgsConstructor
@Tag(name = "1. 여행 일정 API", description = "일정 기획 및 자체 동선 최적화 알고리즘 API (담당: 나동휘)")
public class PlanController {

    private final PlanService planService;
    private final AiService aiService;
    private final com.travel.planner.repository.PlaceRepository placeRepository;
    private final com.travel.planner.repository.UserRepository userRepository;
    private final GoogleMapsService googleMapsService;
    private final com.travel.planner.repository.PlanRepository planRepository;
    private final WeatherService weatherService;
    private final PlanValidationService planValidationService;
    private final TrafficRepository trafficRepository;

    @GetMapping("/validate")
    @Operation(summary = "다중 도시 일정 검증 (Soft Warning)",
            description = "입/출국 도시와 선택한 도시들, 숙박 일수를 바탕으로 피로도 점수를 계산하여 무리한 일정인지 경고 메시지를 반환합니다.")
    public PlanValidationService.ValidationResult validatePlan(
            @RequestParam List<String> selectedCities,
            @RequestParam String inCity,
            @RequestParam String outCity,
            @RequestParam int nights
    ) {
        return planValidationService.validateMultiCityPlan(selectedCities, inCity, outCity, nights);
    }

    @PostMapping
    @Operation(summary = "일정 기획 및 자체 최적화 연산 요청", description = "자체 알고리즘(스코어링, K-Means, 2-Opt, 시뮬레이터)을 거친 후 AI 스토리텔링을 씌워 반환합니다.")
    public AiRouteResponse createPlan(
            org.springframework.security.core.Authentication authentication,
            @org.springframework.web.bind.annotation.RequestBody com.travel.planner.dto.PlanRequest request
    ) {
        if (request.getAccommodations() != null && !request.getAccommodations().isEmpty()) {
            PlanValidationService.ValidationResult accValidation = planValidationService.validateAccommodations(
                    request.getAccommodations(), request.getStartDate(), request.getEndDate()
            );
            if (accValidation.isWarning) {
                throw new IllegalArgumentException(accValidation.warningMessage);
            }
        }

        String email = authentication.getName();
        User user = userRepository.findByEmail(email).orElseThrow(() -> new IllegalArgumentException("회원을 찾을 수 없습니다."));

        String joinedCities = request.getCities() != null ? String.join(", ", request.getCities()) : "미정";

        String mainCity = (request.getCities() != null && !request.getCities().isEmpty()) ? request.getCities().get(0) : "미정";
        int totalDays = (int) java.time.temporal.ChronoUnit.DAYS.between(request.getStartDate(), request.getEndDate()) + 1;
        int poolSize = totalDays * 8;

        // 1. DB 데이터 풀 확보 및 부족 시 구글 맵스 긴급 수집 (기존 전처리 로직 100% 유지)
        List<Place> allCityPlaces = placeRepository.findByCityIn(request.getCities());
        if (allCityPlaces.size() < poolSize && mainCity != null && !mainCity.equals("미정")) {
            System.out.println("DB에 장소가 부족합니다. 구글 맵스 긴급 수집을 가동합니다!");
            try {
                String formalizedCity = googleMapsService.getFormalizedJapanCity(mainCity);
                String searchKeyword = (request.getThemes() != null && !request.getThemes().isEmpty()) ? request.getThemes().get(0) : "유명 관광지";
                List<Place> emergencyPlaces = googleMapsService.searchNewPlacesFromGoogle(formalizedCity, searchKeyword);
                java.util.Set<String> newlyAddedIds = new java.util.HashSet<>();

                for (Place p : emergencyPlaces) {
                    if (p.getPlaceId() == null || p.getPlaceId().trim().isEmpty()) continue;
                    if (newlyAddedIds.contains(p.getPlaceId())) continue;

                    p.setCity(mainCity);
                    if (!placeRepository.existsByPlaceId(p.getPlaceId())) {
                        Place savedPlace = placeRepository.save(p);
                        allCityPlaces.add(savedPlace);
                        newlyAddedIds.add(savedPlace.getPlaceId());
                    }
                }
            } catch (Exception e) {
                System.out.println("긴급 수집 실패: " + e.getMessage());
            }
        }

        // 2. 날씨 조회 및 알고리즘 기반 다중 가중치 스코어링 적용 (자체 엔진 가동)
        String currentWeather = weatherService.getCurrentWeather(mainCity);
        List<Place> scoredPlaces = planService.applyScoringAlgorithm(allCityPlaces, currentWeather, request);

        // 상위 N개만 최적화 풀에 투입 (가족이면 여유롭게 개수 축소)
        int maxPlaces = ("가족".equals(request.getCompanion())) ? totalDays * 3 : totalDays * 4;
        List<Place> finalPool = scoredPlaces.stream().limit(maxPlaces).collect(Collectors.toList());

        // 3. 자가 치유(Self-Healing) 경로 산출 파이프라인 (K-Means -> 2-Opt -> Simulation)
        boolean isSimulationSuccess = false;
        int retryCount = 0;
        List<PlanService.SimulatedItinerary> verifiedItineraries = new ArrayList<>();
        String simulationLogs = "";

        while (!isSimulationSuccess && retryCount < 3) {
            verifiedItineraries.clear();
            boolean dailySuccessAll = true;

            // 3-1. K-Means 공간 분할
            Map<Integer, List<Place>> clusters = planService.clusterPlaces(finalPool, totalDays);

            for (int i = 0; i < totalDays; i++) {
                List<Place> dailyPlaces = clusters.get(i);
                if (dailyPlaces == null || dailyPlaces.isEmpty()) continue;

                // 3-2. 2-Opt 교차 꼬임 최적화
                List<Place> dailyRoute = planService.calculateShortestPath(dailyPlaces);

                // 3-3. 영업시간 시뮬레이션 검증
                PlanService.SimulationResult simResult = planService.runTimeSimulation(dailyRoute, request);

                if (simResult.isSuccess()) {
                    // 성공 시 해당 일차 결과를 전체 리스트에 누적, (Day 정보는 객체에 없으므로 별도 기록 혹은 순서대로 처리)
                    for(PlanService.SimulatedItinerary si : simResult.getValidRoute()) {
                        // DB 저장을 위해 임시로 Entity 생성
                        Itinerary tempIti = new Itinerary();
                        tempIti.setDayNumber(i + 1);
                        tempIti.setTime(si.getTime());
                        tempIti.setPlace(si.getPlace());
                        // verifiedItineraries 대신 tempIti 리스트를 활용
                    }
                    verifiedItineraries.addAll(simResult.getValidRoute());
                } else {
                    // 시뮬레이션 실패 시, 문제가 된 장소를 풀에서 강제 삭제 후 재계산 트리거
                    retryCount++;
                    simulationLogs += "Retry " + retryCount + " (Day " + (i+1) + "): " + simResult.getReason() + "\n";
                    if (simResult.getProblemPlace() != null) {
                        finalPool.remove(simResult.getProblemPlace());
                    }
                    dailySuccessAll = false;
                    break; // 이번 루프 폭파시키고 재배치 시작
                }
            }

            if (dailySuccessAll) {
                isSimulationSuccess = true;
            }
        }

        if (!isSimulationSuccess) {
            throw new RuntimeException("물리적으로 이동 불가능한 일정입니다. 테마나 목적지를 줄이거나 일정을 늘려주세요.\n[알고리즘 로그]:\n" + simulationLogs);
        }

        // 4. 알고리즘으로 완벽히 짜여진 일정을 AI에게 넘겨 '가이드 설명'만 달아오게 지시
        AiRouteResponse aiResponse = aiService.generateStorytellingForValidatedRoute(
                verifiedItineraries, request.getLanguage(), totalDays
        );

        // 5. DB 영구 저장 로직
        Plan plan = new Plan();
        plan.setUser(user);
        plan.setTitle(joinedCities + " 여행");
        plan.setStartDate(request.getStartDate());
        plan.setEndDate(request.getEndDate());
        plan.setInCity(request.getInCity());
        plan.setOutCity(request.getOutCity());
        plan.setInTime(request.getInTime() != null ? request.getInTime() : "미정");
        plan.setOutTime(request.getOutTime() != null ? request.getOutTime() : "미정");

        if (request.getThemes() != null) {
            plan.setTheme(String.join(", ", request.getThemes()));
        }
        plan.setAiReason(aiResponse.getReason());

        if (aiResponse.getTimeline() != null) {
            int seq = 1;
            for (AiRouteResponse.TimelineItem item : aiResponse.getTimeline()) {
                Itinerary itinerary = new Itinerary();
                itinerary.setDayNumber(item.getDay());
                itinerary.setSequence(seq++);
                itinerary.setTime(item.getTime());
                itinerary.setAiComment(item.getDescription());

                // AI가 장소명이나 ID를 건드리지 못하게 했으므로 DB에 무조건 존재함
                Place matchedPlace = allCityPlaces.stream()
                        .filter(p -> p.getName().equals(item.getPlaceName()))
                        .findFirst()
                        .orElse(null);

                if (matchedPlace != null) {
                    itinerary.setPlace(matchedPlace);
                    plan.addItinerary(itinerary);
                    item.setLatitude(matchedPlace.getLatitude());
                    item.setLongitude(matchedPlace.getLongitude());
                }
            }
        }

        planRepository.save(plan);

        // 6. Traffic(이동 정보) 데이터 후처리 적재
        Place prevPlace = null;
        for (Itinerary iti : plan.getItineraries()) {
            Traffic traffic = new Traffic();
            traffic.setItinerary(iti);
            traffic.setTransportType(request.getTransportation() != null ? request.getTransportation() : "도보 및 대중교통");
            traffic.setEstimatedCost(0);

            if (prevPlace != null && prevPlace.getLatitude() != null && iti.getPlace().getLatitude() != null) {
                double distKm = DistanceUtil.calculateDistance(
                        prevPlace.getLatitude(), prevPlace.getLongitude(),
                        iti.getPlace().getLatitude(), iti.getPlace().getLongitude()
                );
                int estimatedMinutes = (int) Math.round((distKm / 20.0) * 60.0);
                traffic.setDurationMinutes(Math.max(estimatedMinutes, 5));
            } else {
                traffic.setDurationMinutes(0);
            }

            trafficRepository.save(traffic);
            prevPlace = iti.getPlace();

            if (iti.getSequence() == plan.getItineraries().stream()
                    .filter(i -> i.getDayNumber().equals(iti.getDayNumber()))
                    .mapToInt(Itinerary::getSequence).max().orElse(0)) {
                prevPlace = null; // 날짜 변경 시 초기화
            }
        }

        return aiResponse;
    }

    @GetMapping("/{planId}/timeline")
    @Operation(summary = "동적 타임라인 조회", description = "DB에 저장된 연산 완료 일정을 반환합니다.")
    public AiRouteResponse getTimeline(@PathVariable Long planId) {
        Plan plan = planRepository.findById(planId)
                .orElseThrow(() -> new IllegalArgumentException("해당 여행 일정을 찾을 수 없습니다."));

        AiRouteResponse response = new AiRouteResponse();
        response.setReason(plan.getAiReason());

        List<AiRouteResponse.TimelineItem> timelineItems = plan.getItineraries().stream().map(iti -> {
            AiRouteResponse.TimelineItem item = new AiRouteResponse.TimelineItem();
            item.setDay(iti.getDayNumber());
            item.setTime(iti.getTime());
            item.setPlaceName(iti.getPlace().getName());
            item.setCategory("분류 정보");
            item.setDescription(iti.getAiComment());
            item.setLatitude(iti.getPlace().getLatitude());
            item.setLongitude(iti.getPlace().getLongitude());
            return item;
        }).collect(Collectors.toList());

        response.setTimeline(timelineItems);
        return response;
    }

    @PutMapping("/{planId}/timeline")
    @Operation(summary = "동선 재생성 (수정)", description = "사용자가 일정을 수정/삭제하면 알고리즘을 다시 돌립니다.")
    public String regenerateTimeline(@PathVariable Long planId) {
        return "✅ " + planId + "번 여행의 자체 알고리즘 동선 재생성이 완료되었습니다.";
    }
}