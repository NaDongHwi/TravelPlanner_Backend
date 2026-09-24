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
    private final PlanValidationService planValidationService;
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
        // Step 1. 사용자 여행 조건 입력 및 검증
        // ==============================================================================
        String email = authentication.getName();
        User user = userRepository.findByEmail(email).orElseThrow(() -> new IllegalArgumentException("회원을 찾을 수 없습니다."));

        String joinedCities = request.getCities() != null ? String.join(", ", request.getCities()) : "미정";
        String mainCity = (request.getCities() != null && !request.getCities().isEmpty()) ? request.getCities().get(0) : "미정";
        int totalDays = (int) java.time.temporal.ChronoUnit.DAYS.between(request.getStartDate(), request.getEndDate()) + 1;
        String currentWeather = weatherService.getCurrentWeather(mainCity);

        if (request.getAccommodations() != null && !request.getAccommodations().isEmpty()) {
            PlanValidationService.ValidationResult accValidation = planValidationService.validateAccommodations(
                    request.getAccommodations(), request.getStartDate(), request.getEndDate()
            );
            if (accValidation.isWarning) throw new IllegalArgumentException(accValidation.warningMessage);
        }

        // ==============================================================================
        // Step 3. 시간 총량(Time-Volume) 기반 장소 후보 수집 (Cold Start 방어)
        // ==============================================================================
        List<Place> allCityPlaces = placeRepository.findByCityIn(request.getCities());

        int physicalAvailableMinutes = planService.calculateTotalPhysicalMinutes(request, totalDays);

        // 필요 최소 볼륨 = 물리적 가용 시간 총합 - (고정 일정 소요 시간)
        int fixedScheduleMinutes = 0;
        if (request.getFixedSchedules() != null) {
            for (PlanRequest.FixedScheduleInput fixed : request.getFixedSchedules()) {
                fixedScheduleMinutes += (int) java.time.Duration.between(fixed.getStartTime(), fixed.getEndTime()).toMinutes();
            }
        }
        int minRequiredVolume = Math.max(0, physicalAvailableMinutes - fixedScheduleMinutes);
        int targetPoolVolume = (int) (minRequiredVolume * 1.5); // ③ 최종 수집 타겟 볼륨

        int currentDbVolume = allCityPlaces.stream()
                .mapToInt(p -> planService.calculateDwellTime(p, request))
                .sum();

        System.out.println("현재 DB 확보 시간 볼륨: " + currentDbVolume + "분 / 필요 볼륨: " + targetPoolVolume + "분");

        if (currentDbVolume < targetPoolVolume && mainCity != null && !mainCity.equals("미정")) {
            System.out.println("DB 시간 볼륨 부족 감지. 구글 API 긴급 수집 가동 (최대 3회 Safety Cap 적용)");
            try {
                String formalizedCity = googleMapsService.getFormalizedJapanCity(mainCity);
                String searchKeyword = (request.getThemes() != null && !request.getThemes().isEmpty()) ? request.getThemes().get(0) : "유명 관광지";
                List<Place> emergencyPlaces = googleMapsService.searchNewPlacesFromGoogle(formalizedCity, searchKeyword, true);
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
                System.out.println("긴급 수집 통신 에러: " + e.getMessage());
            }
        }

        List<Place> openPlaces = planService.filterClosedPlaces(allCityPlaces, request.getStartDate());

        // ==============================================================================
        // Step 4. 장소 적합도 계산 (Scoring)
        // ==============================================================================
        List<Place> scoredPlaces = planService.applyWeightedScoring(openPlaces, currentWeather, request);

        // ==============================================================================
        // Step 10. 경로/일정 재산출 루프 및 사후 균등 재분배(Post-Load Balancing)
        // ==============================================================================
        boolean isSimulationSuccess = false;
        boolean forceDummyNode = false; // 사후 재분배 발동 플래그
        int maxRetries = 3;
        int currentTry = 0;
        List<PlanService.SimulatedItinerary> finalVerifiedItineraries = new ArrayList<>();
        String errorLogs = "";

        while (!isSimulationSuccess && currentTry < maxRetries) {
            currentTry++;
            finalVerifiedItineraries.clear();

            List<Place> selectedCandidates = planService.selectCandidates(scoredPlaces, request, totalDays);

            if (forceDummyNode && selectedCandidates.size() > 0) {
                System.out.println("[데이터 기근 감지] 후반부 일정 비어있음 - 사후 재분배 및 가상 블록(Dummy Node) 삽입 트리거");
            }

            List<List<Place>> dailyRoutes = planService.calculateTspWithTimeWindows(selectedCandidates, totalDays, request.getAccommodations(), forceDummyNode, request);
            boolean dailySuccessAll = true;
            int emptyDaysCount = 0;

            for (int day = 0; day < totalDays; day++) {
                List<Place> routeForDay = dailyRoutes.get(day);
                if (routeForDay.isEmpty()) {
                    emptyDaysCount++;
                    if (!forceDummyNode) continue;
                }

                PlanService.SimulationResult simResult = planService.runScheduleSimulation(
                        routeForDay, request, day + 1, totalDays, forceDummyNode
                );

                if (simResult.isSuccess()) {
                    finalVerifiedItineraries.addAll(simResult.getValidRoute());
                } else {
                    errorLogs += String.format("[시도 %d/Day %d 실패] %s\n", currentTry, (day + 1), simResult.getReason());
                    if (simResult.getProblemPlace() != null) scoredPlaces.remove(simResult.getProblemPlace());
                    dailySuccessAll = false;
                    break;
                }
            }

            // 후반부 일정이 비어버리는 Edge Case 발생 시, 에러를 내지 않고 Dummy Node 플래그를 켜서 1회 재시도
            if (dailySuccessAll && emptyDaysCount > 0 && !forceDummyNode) {
                forceDummyNode = true;
                dailySuccessAll = false; // 루프를 한 번 더 돌게 강제
                continue;
            }

            if (dailySuccessAll) isSimulationSuccess = true;
        }

        if (!isSimulationSuccess) {
            throw new RuntimeException("현재 조건으로 생성 가능한 일정이 없습니다. 조건을 완화해주세요.\n[로그]:\n" + errorLogs);
        }

        // ==============================================================================
        // Step 11~13. AI 스토리텔링 연동 및 DB 최종 적재
        // ==============================================================================
        AiRouteResponse aiResponse = aiService.generateStorytellingForValidatedRoute(
                finalVerifiedItineraries, request.getLanguage(), totalDays
        );

        Plan plan = new Plan();
        plan.setUser(user);
        plan.setTitle(joinedCities + " 여행");
        plan.setStartDate(request.getStartDate());
        plan.setEndDate(request.getEndDate());
        plan.setInCity(request.getInCity());
        plan.setOutCity(request.getOutCity());
        plan.setInTime(request.getInTime() != null ? request.getInTime() : "미정");
        plan.setOutTime(request.getOutTime() != null ? request.getOutTime() : "미정");

        if (request.getThemes() != null) plan.setTheme(String.join(", ", request.getThemes()));
        plan.setAiReason(aiResponse.getReason());

        if (aiResponse.getTimeline() != null) {
            int seq = 1;
            for (AiRouteResponse.TimelineItem item : aiResponse.getTimeline()) {
                Itinerary itinerary = new Itinerary();
                itinerary.setDayNumber(item.getDay());
                itinerary.setSequence(seq++);
                itinerary.setTime(item.getTime());
                itinerary.setAiComment(item.getDescription());

                // 가상 블록(Dummy Node) DB 안전 매핑 로직
                if ("[자유 시간 및 로컬 탐방]".equals(item.getPlaceName())) {
                    // 1. DB에 'DUMMY_FREE_TIME'이라는 고유 ID를 가진 장소가 없으면 새로 만들고, 있으면 재활용
                    Place dummyPlace = placeRepository.findByPlaceId("DUMMY_FREE_TIME").orElseGet(() -> {
                        Place newDummy = new Place();
                        newDummy.setPlaceId("DUMMY_FREE_TIME");
                        newDummy.setName("[자유 시간 및 로컬 탐방]");
                        newDummy.setCategory("자유시간");
                        newDummy.setTheme("힐링,산책");
                        newDummy.setCity(mainCity);
                        newDummy.setLatitude(0.0);
                        newDummy.setLongitude(0.0);
                        return placeRepository.save(newDummy);
                    });
                    itinerary.setPlace(dummyPlace);

                    // 2. 프론트엔드 지도 마커가 아프리카 앞바다(0,0)에 찍히는 것을 막기 위해, 직전 장소의 위/경도를 물려줌
                    if (!plan.getItineraries().isEmpty()) {
                        Place lastPlace = plan.getItineraries().get(plan.getItineraries().size() - 1).getPlace();
                        item.setLatitude(lastPlace.getLatitude());
                        item.setLongitude(lastPlace.getLongitude());
                    } else {
                        item.setLatitude(0.0);
                        item.setLongitude(0.0);
                    }
                } else {
                    // 일반 장소 매핑 (기존 로직 유지)
                    Place matchedPlace = allCityPlaces.stream()
                            .filter(p -> p.getName().equals(item.getPlaceName()))
                            .findFirst().orElse(null);
                    if (matchedPlace != null) {
                        itinerary.setPlace(matchedPlace);
                        item.setLatitude(matchedPlace.getLatitude());
                        item.setLongitude(matchedPlace.getLongitude());
                    }
                }
                plan.addItinerary(itinerary);
            }
        }
        planRepository.save(plan);

        // ==============================================================================
        // Traffic(이동 정보) 데이터 후처리 적재 (버그 방어 로직 추가)
        // ==============================================================================
        Place prevPlace = null;
        for (Itinerary iti : plan.getItineraries()) {
            Traffic traffic = new Traffic();
            traffic.setItinerary(iti);
            traffic.setTransportType(request.getTransportation() != null ? request.getTransportation() : "대중교통");
            traffic.setEstimatedCost(0);

            if (prevPlace != null && prevPlace.getLatitude() != null && iti.getPlace().getLatitude() != null) {
                // 가상 블록(자유시간)일 경우 이동거리 계산을 스킵하여 시간 뻥튀기 버그 방지
                if ("자유시간".equals(iti.getPlace().getCategory())) {
                    traffic.setDurationMinutes(0);
                } else {
                    double distKm = DistanceUtil.calculateDistance(
                            prevPlace.getLatitude(), prevPlace.getLongitude(),
                            iti.getPlace().getLatitude(), iti.getPlace().getLongitude()
                    );

                    String transport = request.getTransportation() != null ? request.getTransportation() : "대중교통";
                    traffic.setTransportType(transport);

                    // 섬 간 이동 (배/비행기) 소요 시간 산출 로직
                    if (transport.contains("배") || transport.contains("비행기") || transport.contains("항공")) {
                        double rawHours = distKm / 500.0; // 가상 항해/비행 시속 (약 500km/h)
                        int nHours = (int) rawHours;
                        int remainMinutes = (int) ((rawHours - nHours) * 60);

                        if (remainMinutes <= 30) {
                            traffic.setDurationMinutes((nHours * 60) + 90); // n시간 30분 이하 -> 기존 시간 내림(n) + 1시간 30분 추가
                        } else {
                            traffic.setDurationMinutes(((nHours + 1) * 60) + 60); // 초과 -> 올림(n+1) + 1시간 추가
                        }
                    } else {
                        // 일반 육상 이동 (시속 20km 기준)
                        int estimatedMinutes = (int) Math.round((distKm / 20.0) * 60.0);
                        traffic.setDurationMinutes(Math.max(estimatedMinutes, 5));
                    }
                }
            } else {
                traffic.setDurationMinutes(0);
            }
            trafficRepository.save(traffic);
            prevPlace = iti.getPlace();

            // 날짜(Day)가 바뀌면 이전 장소(prevPlace)를 초기화하여 다음날 아침 거리가 계산되지 않게 방어
            if (iti.getSequence() == plan.getItineraries().stream()
                    .filter(i -> i.getDayNumber().equals(iti.getDayNumber()))
                    .mapToInt(Itinerary::getSequence).max().orElse(0)) {
                prevPlace = null;
            }
        }

        return aiResponse;
    }
}