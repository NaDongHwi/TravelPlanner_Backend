package com.travel.planner.controller;

import com.travel.planner.dto.AiRouteResponse;
import com.travel.planner.dto.PlanRequest;
import com.travel.planner.entity.Itinerary;
import com.travel.planner.entity.Place;
import com.travel.planner.entity.Plan;
import com.travel.planner.entity.Traffic;
import com.travel.planner.entity.User;
import com.travel.planner.repository.PlaceRepository;
import com.travel.planner.repository.PlanRepository;
import com.travel.planner.repository.TrafficRepository;
import com.travel.planner.repository.UserRepository;
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
import java.util.List;

@RestController
@RequestMapping("/api/v1/plans")
@RequiredArgsConstructor
@Tag(name = "1. 여행 일정 API", description = "13단계 자체 동선 최적화 알고리즘 기반 파이프라인")
public class PlanController {

    private final PlanService planService;
    private final AiService aiService;
    private final PlaceRepository placeRepository;
    private final UserRepository userRepository;
    private final GoogleMapsService googleMapsService;
    private final PlanRepository planRepository;
    private final WeatherService weatherService;
    private final PlanValidationService planValidationService; // [모듈] TravelRequestValidator 역할
    private final TrafficRepository trafficRepository;

    @GetMapping("/validate")
    @Operation(summary = "다중 도시 일정 검증 (Soft Warning)", description = "입/출국 도시와 선택한 도시들, 숙박 일수를 바탕으로 피로도 점수를 계산하여 무리한 일정인지 경고 메시지를 반환합니다.")
    public PlanValidationService.ValidationResult validatePlan(
            @RequestParam List<String> selectedCities,
            @RequestParam String inCity,
            @RequestParam String outCity,
            @RequestParam int nights
    ) {
        return planValidationService.validateMultiCityPlan(selectedCities, inCity, outCity, nights);
    }

    @PostMapping
    @Operation(summary = "13단계 파이프라인 최적화 연산 요청", description = "입력 검증 -> 필터링 -> 스코어링 -> 그리디 선정 -> TSP-TW 라우팅 -> 시뮬레이션 -> 검증 로직 가동")
    public AiRouteResponse createPlan(
            org.springframework.security.core.Authentication authentication,
            @RequestBody PlanRequest request
    ) {
        // ==============================================================================
        // Step 1. 사용자 여행 조건 입력 (request DTO로 수신 완료)
        // ==============================================================================
        String email = authentication.getName();
        User user = userRepository.findByEmail(email).orElseThrow(() -> new IllegalArgumentException("회원을 찾을 수 없습니다."));

        String joinedCities = request.getCities() != null ? String.join(", ", request.getCities()) : "미정";
        String mainCity = (request.getCities() != null && !request.getCities().isEmpty()) ? request.getCities().get(0) : "미정";
        int totalDays = (int) java.time.temporal.ChronoUnit.DAYS.between(request.getStartDate(), request.getEndDate()) + 1;
        String currentWeather = weatherService.getCurrentWeather(mainCity);

        // ==============================================================================
        // Step 2. 여행 조건 전처리 [모듈: TravelRequestValidator]
        // ==============================================================================
        if (request.getAccommodations() != null && !request.getAccommodations().isEmpty()) {
            PlanValidationService.ValidationResult accValidation = planValidationService.validateAccommodations(
                    request.getAccommodations(), request.getStartDate(), request.getEndDate()
            );
            if (accValidation.isWarning) throw new IllegalArgumentException(accValidation.warningMessage);
        }

        // ==============================================================================
        // Step 3. 장소 후보 수집 [모듈: PlaceCandidateService]
        // ==============================================================================
        int poolSize = totalDays * 8;
        List<Place> allCityPlaces = placeRepository.findByCityIn(request.getCities());

        // 콜드 스타트 방어: DB 데이터 부족 시 구글 맵스 API 실시간 수집 (기존 로직 유지)
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

        // [핵심] 휴무일 필터링 등 하드 제약 조건 1차 가지치기 (Pruning)
        List<Place> openPlaces = planService.filterClosedPlaces(allCityPlaces, request.getStartDate());

        // ==============================================================================
        // Step 4. 장소 적합도 계산 [알고리즘: Weighted Scoring Algorithm]
        // ==============================================================================
        // 테마, 날씨, 예산, 평점 등을 선형 결합 수식으로 점수 부여
        List<Place> scoredPlaces = planService.applyWeightedScoring(openPlaces, currentWeather, request);

        // ==============================================================================
        // Step 10. 경로/일정 재산출 루프 [알고리즘: Route Recalculation]
        // ==============================================================================
        boolean isSimulationSuccess = false;
        int maxRetries = 3;
        int currentTry = 0;
        List<PlanService.SimulatedItinerary> finalVerifiedItineraries = new ArrayList<>();
        String errorLogs = "";

        while (!isSimulationSuccess && currentTry < maxRetries) {
            currentTry++;
            finalVerifiedItineraries.clear();

            // ==============================================================================
            // Step 5. 방문 장소 선정 [알고리즘: Candidate Selection (Greedy/Knapsack)]
            // ==============================================================================
            // 점수순 정렬된 후보군 중 예산(Budget)과 가용 시간을 고려하여 최적 조합 추출
            List<Place> selectedCandidates = planService.selectCandidates(scoredPlaces, request, totalDays);

            // ==============================================================================
            // Step 6. 이동 경로 계산 [알고리즘: TSP with Time Windows + 2-opt]
            // ==============================================================================
            // 단순히 K-Means를 도는 것이 아니라, 2-Opt 내부에서 Time Window 위반 시 Penalty를 부여하는 방식
            List<List<Place>> dailyRoutes = planService.calculateTspWithTimeWindows(selectedCandidates, totalDays, request.getAccommodations());

            boolean dailySuccessAll = true;

            for (int day = 0; day < totalDays; day++) {
                List<Place> routeForDay = dailyRoutes.get(day);
                if (routeForDay.isEmpty()) continue;

                // ==============================================================================
                // Step 7~9. 시간 배정 & 시뮬레이션 & 검증
                // [모듈: Constraint-based Scheduling, ScheduleSimulator, ScheduleValidator]
                // ==============================================================================
                PlanService.SimulationResult simResult = planService.runScheduleSimulation(routeForDay, request, day + 1);

                if (simResult.isSuccess()) {
                    finalVerifiedItineraries.addAll(simResult.getValidRoute());
                } else {
                    // [Step 10 재산출 발동] 검증 실패 시 문제 노드를 Drop 하고 Iteration 반복
                    errorLogs += String.format("[시도 %d/Day %d 실패] %s\n", currentTry, (day + 1), simResult.getReason());
                    if (simResult.getProblemPlace() != null) {
                        scoredPlaces.remove(simResult.getProblemPlace()); // 차순위 후보가 올라올 수 있도록 배제
                    }
                    dailySuccessAll = false;
                    break;
                }
            }

            if (dailySuccessAll) {
                isSimulationSuccess = true;
            }
        }

        // 최대 3회 재계산 후에도 실패하면 에러 반환
        if (!isSimulationSuccess) {
            throw new RuntimeException("현재 조건(시간, 예산, 고정일정)으로 생성 가능한 일정이 없습니다. 조건을 완화해주세요.\n[시스템 로그]:\n" + errorLogs);
        }

        // ==============================================================================
        // Step 11. 일정 초안 생성 [모듈: ItineraryPreviewService (AI 스토리텔링 연동)]
        // ==============================================================================
        // 확정된 알고리즘 동선을 바탕으로 AI에게 감성적인 설명(Description)만 추가하도록 요청
        AiRouteResponse aiResponse = aiService.generateStorytellingForValidatedRoute(
                finalVerifiedItineraries, request.getLanguage(), totalDays
        );

        // ==============================================================================
        // Step 12. 사용자 최종 검토 및 제약 업데이트 [모듈: UserConstraintUpdate]
        // ==============================================================================
        // (본 기능은 현재 컨트롤러에서는 초안 반환으로 대응하며, 프론트에서 PUT 요청 시 Step 10을 재트리거하는 방식으로 동작)

        // ==============================================================================
        // Step 13. 최종 일정 확정 [모듈: ItineraryService]
        // ==============================================================================
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

                // AI가 생성한 장소 이름으로 DB의 원본 객체를 매핑
                Place matchedPlace = allCityPlaces.stream()
                        .filter(p -> p.getName().equals(item.getPlaceName()))
                        .findFirst().orElse(null);

                if (matchedPlace != null) {
                    itinerary.setPlace(matchedPlace);
                    plan.addItinerary(itinerary);
                    item.setLatitude(matchedPlace.getLatitude());
                    item.setLongitude(matchedPlace.getLongitude());
                }
            }
        }
        planRepository.save(plan);

        // Traffic(이동 정보) 데이터 후처리 적재
        Place prevPlace = null;
        for (Itinerary iti : plan.getItineraries()) {
            Traffic traffic = new Traffic();
            traffic.setItinerary(iti);
            traffic.setTransportType(request.getTransportation() != null ? request.getTransportation() : "대중교통");
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
                prevPlace = null;
            }
        }

        return aiResponse;
    }
}