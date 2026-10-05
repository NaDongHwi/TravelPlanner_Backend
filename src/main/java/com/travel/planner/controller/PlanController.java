package com.travel.planner.controller;

import com.travel.planner.dto.AiRouteResponse;
import com.travel.planner.dto.PlanRequest;
import com.travel.planner.dto.RerouteRequest;
import com.travel.planner.dto.RerouteResponse;
import com.travel.planner.entity.Itinerary;
import com.travel.planner.entity.Place;
import com.travel.planner.entity.Plan;
import com.travel.planner.entity.Traffic;
import com.travel.planner.entity.User;
import com.travel.planner.repository.PlaceRepository;
import com.travel.planner.repository.PlanRepository;
import com.travel.planner.repository.TrafficRepository;
import com.travel.planner.repository.UserRepository;
import com.travel.planner.service.*;
import com.travel.planner.util.DistanceUtil;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.Arrays;
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
    private final PlanModifierService planModifierService;

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
        // Step 3. 시간 총량(Time-Volume) 기반 장소 후보 수집 (잉여 시간 산출 및 근교 확장)
        // ==============================================================================
        List<Place> allCityPlaces = placeRepository.findByCityIn(request.getCities());
        int physicalAvailableMinutes = planService.calculateTotalPhysicalMinutes(request, totalDays);
        int fixedScheduleMinutes = 0;
        if (request.getFixedSchedules() != null) {
            for (PlanRequest.FixedScheduleInput fixed : request.getFixedSchedules()) {
                fixedScheduleMinutes += (int) java.time.Duration.between(fixed.getStartTime(), fixed.getEndTime()).toMinutes();
            }
        }
        int minRequiredVolume = Math.max(0, physicalAvailableMinutes - fixedScheduleMinutes);
        int targetPoolVolume = (int) (minRequiredVolume * 1.5);

        // 기존 다중 도시 검증 로직(PlanValidationService)을 역이용한 '잉여 일수(Overflow Days)' 계산
        // 도시 1개당 평균 2일이 필요하다고 가정. (1개 도시에 10일 일정이면 8일의 잉여 발생)
        int excessDays = totalDays - (request.getCities().size() * 2);

        int emergencyCallCount = 0;
        int maxEmergencyCalls = Math.max(3, request.getThemes() != null ? request.getThemes().size() : 3);

        while (emergencyCallCount < maxEmergencyCalls) {
            int currentDbVolume = allCityPlaces.stream()
                    .mapToInt(p -> planService.calculateDwellTime(p, request))
                    .sum();

            if (currentDbVolume >= targetPoolVolume || mainCity == null || mainCity.equals("미정")) {
                break;
            }

            try {
                // 요청된 여러 도시를 번갈아가며 검색
                String targetCity = request.getCities().get(emergencyCallCount % request.getCities().size());
                String formalizedCity = googleMapsService.getFormalizedJapanCity(targetCity);

                List<String> dynamicKeywords = new ArrayList<>();
                if (request.getThemes() != null && !request.getThemes().isEmpty()) {
                    dynamicKeywords.addAll(request.getThemes());
                } else {
                    dynamicKeywords.addAll(Arrays.asList("필수 관광지", "인기 맛집", "랜드마크"));
                }

                String baseKeyword = dynamicKeywords.get(emergencyCallCount % dynamicKeywords.size());
                String searchKeyword = baseKeyword;

                if (excessDays >= 3 && !request.isExcludeSuburbs()) {
                    searchKeyword = baseKeyword + " 근교 명소";
                }

                List<Place> emergencyPlaces = googleMapsService.searchNewPlacesFromGoogle(formalizedCity, searchKeyword, true);

                for (Place p : emergencyPlaces) {
                    if (p.getPlaceId() == null || p.getPlaceId().trim().isEmpty()) continue;
                    if (p.getCity() == null) p.setCity(targetCity);
                    if (p.getCategory() == null) p.setCategory("관광지");
                    if (p.getTheme() == null) p.setTheme(baseKeyword);
                    if (p.getRecommendedDuration() == null) p.setRecommendedDuration(120);

                    if (!placeRepository.existsByPlaceId(p.getPlaceId())) {
                        Place savedPlace = placeRepository.save(p);
                        allCityPlaces.add(savedPlace);
                    } else {
                        Place existingPlace = placeRepository.findByPlaceId(p.getPlaceId()).get();
                        if (!allCityPlaces.contains(existingPlace)) allCityPlaces.add(existingPlace);
                    }
                }
            } catch (Exception e) {
                System.out.println("긴급 수집 통신 에러: " + e.getMessage());
            }
            emergencyCallCount++;
        }

        List<Place> openPlaces = planService.filterClosedPlaces(allCityPlaces, request.getStartDate());

        // ==============================================================================
        // Step 4. 장소 적합도 계산 (Scoring)
        // ==============================================================================
        List<Place> scoredPlaces = planService.applyWeightedScoring(openPlaces, currentWeather, request);

        // Step 4.5. 베이스캠프(숙소) 최우선 확보
        Place baseCamp = null;
        if (request.getAccommodations() != null && !request.getAccommodations().isEmpty()) {
            // 1. 유저가 직접 입력한 숙소가 있는 경우 (구글 API로 좌표 찾기)
            PlanRequest.AccommodationInput acc = request.getAccommodations().get(0);
            List<Place> searchResult = googleMapsService.searchNewPlacesFromGoogle(mainCity, acc.getName(), false);
            if (!searchResult.isEmpty()) {
                baseCamp = searchResult.get(0);
                baseCamp.setCategory("숙소");
            }
        } else if (request.isSuggestHotel()) {
            // 2. AI 숙소 추천을 켠 경우
            List<Place> hotels = googleMapsService.searchRecommendedHotels(mainCity);
            if (!hotels.isEmpty()) {
                baseCamp = hotels.get(0);
                baseCamp.setCategory("숙소");
                if (!placeRepository.existsByPlaceId(baseCamp.getPlaceId())) {
                    baseCamp = placeRepository.save(baseCamp);
                } else {
                    baseCamp = placeRepository.findByPlaceId(baseCamp.getPlaceId()).get();
                }
            }
        }

        // ==============================================================================
        // Step 10. 경로/일정 재산출 루프 및 사후 균등 재분배(Post-Load Balancing)
        // ==============================================================================
        boolean isSimulationSuccess = false;
        boolean forceDummyNode = false;
        int maxRetries = 3;
        int currentTry = 0;

        // 평면 리스트 대신, 즉시 DTO 형태로 꽂아넣을 타임라인 리스트 생성
        List<AiRouteResponse.TimelineItem> finalVerifiedTimeline = new ArrayList<>();
        String errorLogs = "";

        while (!isSimulationSuccess && currentTry < maxRetries) {
            currentTry++;
            finalVerifiedTimeline.clear();

            List<Place> selectedCandidates = planService.selectCandidates(scoredPlaces, request, totalDays);

            if (forceDummyNode && selectedCandidates.size() > 0) {
                System.out.println("[데이터 기근 감지] 후반부 일정 비어있음 사후 재분배 및 가상 블록(Dummy Node) 삽입 트리거");
            }

            List<List<Place>> dailyRoutes = planService.calculateTspWithTimeWindows(selectedCandidates, totalDays, baseCamp, forceDummyNode, request);
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
                    for (PlanService.SimulatedItinerary simIti : simResult.getValidRoute()) {
                        AiRouteResponse.TimelineItem item = new AiRouteResponse.TimelineItem();
                        item.setDay(day + 1);
                        item.setTime(simIti.getTime());
                        item.setPlaceId(simIti.getPlace().getPlaceId());
                        item.setPlaceName(simIti.getPlace().getName());
                        item.setCategory(simIti.getPlace().getCategory());

                        String desc = simIti.getPlace().getTheme() != null ? simIti.getPlace().getTheme() + " 일정" : "추천 일정";
                        item.setDescription(desc);

                        item.setLatitude(simIti.getPlace().getLatitude());
                        item.setLongitude(simIti.getPlace().getLongitude());

                        // 시작일(startDate) + 현재 루프의 일차(day) = 실제 방문 날짜 계산
                        java.time.LocalDate targetDate = request.getStartDate().plusDays(day);

                        // 계산된 실제 방문 날짜를 함께 넘겨서 그 요일에 맞는 영업시간만 빼옵니다.
                        String[] displayDetails = googleMapsService.getPlaceDetailsForDisplay(simIti.getPlace().getPlaceId(), request.getLanguage(), targetDate);

                        item.setFormattedAddress(displayDetails[0]);
                        item.setPhoneNumber(displayDetails[1]);
                        item.setOpeningHours(displayDetails[2]);

                        finalVerifiedTimeline.add(item);
                    }
                } else {
                    errorLogs += String.format("[시도 %d/Day %d 실패] %s\n", currentTry, (day + 1), simResult.getReason());
                    if (simResult.getProblemPlace() != null) scoredPlaces.remove(simResult.getProblemPlace());
                    dailySuccessAll = false;
                    break;
                }
            }

//            if (dailySuccessAll && emptyDaysCount > 0 && !forceDummyNode) {
//                forceDummyNode = true;
//                dailySuccessAll = false;
//                continue;
//            }

            if (dailySuccessAll) isSimulationSuccess = true;
        }

        if (!isSimulationSuccess) {
            throw new RuntimeException("현재 조건으로 생성 가능한 일정이 없습니다. 조건을 완화해주세요.\n[로그]:\n" + errorLogs);
        }

        // ==============================================================================
        // Step 11~13. DTO 조립 및 DB 최종 적재 (AI 의존성 제거 및 100% 안정성 확보)
        // ==============================================================================

        // 불안정한 aiService 호출을 주석 처리하고, 내부 알고리즘 결과를 직접 조립
        AiRouteResponse finalResponse = new AiRouteResponse();
        finalResponse.setReason("여행자의 취향과 물리적 한계를 고려하여 자체 최적화 알고리즘으로 구성된 일정입니다.");
        finalResponse.setTimeline(finalVerifiedTimeline);

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
        plan.setAiReason(finalResponse.getReason());

        if (finalResponse.getTimeline() != null && !finalResponse.getTimeline().isEmpty()) {
            int seq = 1;
            for (AiRouteResponse.TimelineItem item : finalResponse.getTimeline()) {
                Itinerary itinerary = new Itinerary();
                itinerary.setDayNumber(item.getDay());
                itinerary.setSequence(seq++);
                itinerary.setTime(item.getTime());
                itinerary.setAiComment(item.getDescription());

                if ("[자유 시간 및 로컬 탐방]".equals(item.getPlaceName())) {
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

                    if (!plan.getItineraries().isEmpty()) {
                        Place lastPlace = plan.getItineraries().get(plan.getItineraries().size() - 1).getPlace();
                        item.setLatitude(lastPlace.getLatitude());
                        item.setLongitude(lastPlace.getLongitude());
                    } else {
                        item.setLatitude(0.0);
                        item.setLongitude(0.0);
                    }
                } else {
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
        // Traffic(이동 정보) 데이터 후처리 적재
        // ==============================================================================
        Place prevPlace = null;
        for (Itinerary iti : plan.getItineraries()) {
            Traffic traffic = new Traffic();
            traffic.setItinerary(iti);
            traffic.setTransportType(request.getTransportation() != null ? request.getTransportation() : "대중교통");
            traffic.setEstimatedCost(0);

            if (prevPlace != null && prevPlace.getLatitude() != null && iti.getPlace().getLatitude() != null) {
                if ("자유시간".equals(iti.getPlace().getCategory())) {
                    traffic.setDurationMinutes(0);
                } else {
                    double distKm = DistanceUtil.calculateDistance(
                            prevPlace.getLatitude(), prevPlace.getLongitude(),
                            iti.getPlace().getLatitude(), iti.getPlace().getLongitude()
                    );

                    String transport = request.getTransportation() != null ? request.getTransportation() : "대중교통";
                    traffic.setTransportType(transport);

                    if (transport.contains("배") || transport.contains("비행기") || transport.contains("항공")) {
                        double rawHours = distKm / 500.0;
                        int nHours = (int) rawHours;
                        int remainMinutes = (int) ((rawHours - nHours) * 60);

                        if (remainMinutes <= 30) {
                            traffic.setDurationMinutes((nHours * 60) + 90);
                        } else {
                            traffic.setDurationMinutes(((nHours + 1) * 60) + 60);
                        }
                    } else {
                        int estimatedMinutes = (int) Math.round((distKm / 20.0) * 60.0);
                        traffic.setDurationMinutes(Math.max(estimatedMinutes, 5));
                    }
                }
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

        return finalResponse;
    }

    @PostMapping("/{planId}/reroute")
    @Operation(summary = "실시간 동적 경로 재탐색 (Dynamic Rerouting)",
            description = "사용자가 즉석에서 장소를 추가/변경할 때 AI 체류시간 추론 및 시뮬레이터를 가동하여 도미노 붕괴를 방어합니다.")
    public RerouteResponse modifyPlanRoute(
            @PathVariable Long planId,
            @RequestBody RerouteRequest request
    ) {
        return planModifierService.modifyPlanRoute(planId, request);
    }
}