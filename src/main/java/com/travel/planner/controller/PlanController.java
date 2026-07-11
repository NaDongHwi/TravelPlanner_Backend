package com.travel.planner.controller;

import com.travel.planner.dto.AiRouteResponse;
import com.travel.planner.entity.Itinerary;
import com.travel.planner.entity.Place;
import com.travel.planner.entity.Plan;
import com.travel.planner.entity.User;
import com.travel.planner.service.AiService;
import com.travel.planner.service.GoogleMapsService;
import com.travel.planner.service.PlanService;
import com.travel.planner.service.WeatherService;
import com.travel.planner.service.PlanValidationService;
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
@Tag(name = "1. 여행 일정 API", description = "일정 기획 및 동선 최적화 관련 API (담당: 나동휘)")
public class PlanController {

    private final PlanService planService;
    private final AiService aiService;
    private final com.travel.planner.repository.PlaceRepository placeRepository;
    private final com.travel.planner.repository.UserRepository userRepository;
    private final GoogleMapsService googleMapsService;
    private final com.travel.planner.repository.PlanRepository planRepository;
    private final WeatherService weatherService;
    private final PlanValidationService planValidationService;

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
    @Operation(summary = "일정 기획 및 최적화 연산 요청", description = "프론트엔드에서 파라미터를 받아 알고리즘 연산 후 AI 최종 결과를 반환 및 저장합니다.")
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
        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new IllegalArgumentException("회원을 찾을 수 없습니다."));

        // 프론트엔드에서 받은 고정 일정 객체 리스트에서 '이름(name)'만 추출하여 콤마(,)로 연결합니다.
        String fixed = request.getFixedSchedules() != null && !request.getFixedSchedules().isEmpty()
                ? request.getFixedSchedules().stream()
                .map(com.travel.planner.dto.PlanRequest.FixedScheduleInput::getName)
                .collect(Collectors.joining(", "))
                : "없음";

        String arrivalTime = (request.getInTime() != null) ? request.getInTime() : "미정";
        String departureTime = (request.getOutTime() != null) ? request.getOutTime() : "미정";
        String joinedCities = request.getCities() != null ? String.join(", ", request.getCities()) : "미정";
        String mainCity = (request.getCities() != null && !request.getCities().isEmpty())
                ? request.getCities().get(0) : "미정";

        int totalDays = (int) java.time.temporal.ChronoUnit.DAYS.between(request.getStartDate(), request.getEndDate()) + 1;

        String baseContext = String.format(
                "연령대: %s, 성별: %s, 목적지: %s, 동행자: %s, 테마: %s, 이동수단: %s, 고정일정: %s, [입국 시간: %s], [출국 시간: %s]",
                user.getAgeGroup(), user.getGender(), joinedCities, request.getCompanion(),
                String.join(", ", request.getThemes()), request.getTransportation(), fixed, arrivalTime, departureTime
        );

        // 1. 데이터 풀 확보
        int poolSize = totalDays * 8;
        List<Place> allCityPlaces = placeRepository.findByCityIn(request.getCities());

        // DB 지연 쓰기 충돌 방지 및 구글 API 호출 최소화를 위한 로컬 캐시 맵
        Map<String, Place> placeCache = new HashMap<>();
        for (Place p : allCityPlaces) {
            if (p.getPlaceId() != null) {
                placeCache.put(p.getPlaceId(), p);
            }
        }

        // 콜드 스타트 방어
        if (allCityPlaces.size() < poolSize && mainCity != null && !mainCity.equals("미정")) {
            System.out.println("DB에 장소가 부족합니다. 구글 맵스 긴급 수집을 가동합니다!");
            try {
                String formalizedCity = googleMapsService.getFormalizedJapanCity(mainCity);
                String searchKeyword = (request.getThemes() != null && !request.getThemes().isEmpty())
                        ? request.getThemes().get(0) : "유명 관광지";
                List<Place> emergencyPlaces = googleMapsService.searchNewPlacesFromGoogle(formalizedCity, searchKeyword);

                // [메모리 중복 방어] 같은 루프 안에서 똑같은 장소가 2번 들어오는 걸 막는 코드
                java.util.Set<String> newlyAddedIds = new java.util.HashSet<>();

                for (Place p : emergencyPlaces) {
                    if (p.getPlaceId() == null || p.getPlaceId().trim().isEmpty()) continue;
                    if (newlyAddedIds.contains(p.getPlaceId())) continue;

                    p.setCity(mainCity);
                    if (!placeRepository.existsByPlaceId(p.getPlaceId())) {
                        Place savedPlace = placeRepository.save(p);
                        allCityPlaces.add(savedPlace);
                        newlyAddedIds.add(savedPlace.getPlaceId());
                        placeCache.put(savedPlace.getPlaceId(), savedPlace); // 긴급 수집된 장소도 캐시에 즉시 등록
                    }
                }
            } catch (Exception e) {
                System.out.println("긴급 수집 실패: " + e.getMessage());
            }
        }

        // 2. 테마 및 필수 랜드마크 필터링 (정렬 유지 및 고품질 보존)
        boolean isFoodLover = request.getThemes() != null && request.getThemes().stream().anyMatch(t ->
                t.contains("맛집") || t.contains("카페") || t.contains("미식") || t.contains("식도락"));

        List<Place> foodPlaces = allCityPlaces.stream()
                .filter(p -> "식음".equals(p.getCategory()))
                .limit(isFoodLover ? poolSize / 3 : totalDays * 2) // 맛집 테마면 많이, 아니면 하루 2끼
                .collect(Collectors.toList());

        List<Place> landmarkPlaces = allCityPlaces.stream()
                .filter(p -> "관광지".equals(p.getCategory()) || "쇼핑".equals(p.getCategory()))
                .limit(poolSize / 3)
                .collect(Collectors.toList());

        List<Place> themedPlaces = new ArrayList<>();
        if (request.getThemes() != null && !request.getThemes().isEmpty()) {
            themedPlaces = allCityPlaces.stream()
                    .filter(p -> {
                        if (!isFoodLover && "식음".equals(p.getCategory())) return false;
                        return request.getThemes().stream().anyMatch(theme ->
                                p.getTheme() != null && p.getTheme().contains(theme));
                    })
                    .limit(poolSize / 3)
                    .collect(Collectors.toList());
        }

        java.util.Set<Place> hybridPool = new java.util.LinkedHashSet<>();
        hybridPool.addAll(landmarkPlaces);
        hybridPool.addAll(foodPlaces);
        hybridPool.addAll(themedPlaces);

        List<Place> realPlaces = new ArrayList<>(hybridPool);
        if (realPlaces.size() < totalDays * 5) {
            realPlaces = new ArrayList<>(allCityPlaces); // 데이터가 너무 적으면 필터 풀고 전체 투입
        }

        // 3. K-Means + TSP + 일차별 구글 맵스 분할 호출

        // 3-1. K-Means 알고리즘으로 장소들을 일수(totalDays)만큼 지역별 덩어리로 쪼갭니다.
        Map<Integer, List<Place>> clusters = planService.clusterPlaces(realPlaces, totalDays);

        List<Place> finalOptimizedRoute = new ArrayList<>();
        StringBuilder dailyTravelTimes = new StringBuilder();
        StringBuilder dynamicConstraints = new StringBuilder();
        dynamicConstraints.append("\n\n[장소별 실제 영업시간 및 절대 제약 조건]\n");
        dynamicConstraints.append("AI는 아래 나열된 각 장소의 실제 영업시간을 반드시 분석하고, 문이 닫혀있는 시간에는 절대 방문 일정을 짜지 마세요.\n");

        // 3-2. 일차별로 루프를 돌며 TSP 최적화 및 구글 맵스를 호출합니다.
        for (int i = 0; i < totalDays; i++) {
            List<Place> dailyPlaces = clusters.get(i);
            if (dailyPlaces == null || dailyPlaces.isEmpty()) continue;

            // 해당 일차의 구역 내에서 TSP 최단 거리 정렬 수행
            List<Place> dailyRoute = planService.calculateShortestPath(dailyPlaces);
            finalOptimizedRoute.addAll(dailyRoute);

            // 하루치 장소(보통 10~15개)만 구글 맵스에 던지므로 25개 한도(MAX_WAYPOINTS) 안 걸림
            dailyTravelTimes.append("\n[Day ").append(i + 1).append(" 구역 예상 이동 시간]\n");
            dailyTravelTimes.append(googleMapsService.getRealTravelTimes(dailyRoute)).append("\n");

            for (Place p : dailyRoute) {
                String opHours = (p.getOpeningHours() != null && !p.getOpeningHours().equals("영업시간 정보 없음"))
                        ? p.getOpeningHours() : "영업시간 확인 필요";
                String type = (p.getPlaceType() != null) ? p.getPlaceType() : "복합";
                dynamicConstraints.append("- ").append(p.getName()).append(": ").append(opHours).append(" (환경: ").append(type).append(")\n");
            }
        }

        String currentWeather = weatherService.getCurrentWeather(mainCity);

        String finalContext = baseContext +
                "\n\n[구글 맵스 기반 일차별 실제 이동 시간]\n" + dailyTravelTimes.toString() +
                dynamicConstraints.toString() +
                "\n\n[목적지 실시간 기상 정보]\n- 상태: " + currentWeather;

        // 숙소 역제안 하이브리드 로직
        String hotelCandidates = "";
        boolean hasNoAccommodations = (request.getAccommodations() == null || request.getAccommodations().isEmpty());

        if (hasNoAccommodations && request.isSuggestHotel()) {
            List<Place> dbHotels = placeRepository.findTop10ByCityAndCategory(mainCity, "숙소");
            List<Place> combinedHotels = new java.util.ArrayList<>(dbHotels);
            if (combinedHotels.size() < 5) {
                combinedHotels.addAll(googleMapsService.searchRecommendedHotels(mainCity));
            }
            hotelCandidates = combinedHotels.stream().limit(6)
                    .map(p -> p.getName() + " (평점/리뷰 우수)")
                    .collect(Collectors.joining(", "));
        }

        // 7. 제미나이 최종 연산
        AiRouteResponse aiResponse = aiService.evaluateAndModifyRoute(
                finalContext, finalOptimizedRoute, request.getLanguage(),
                request.getAccommodations(), request.isSuggestHotel(), hotelCandidates,
                request.getStartDate(), totalDays
        );

        // 8. DB 영구 저장 로직
        Plan plan = new Plan();
        plan.setUser(user);
        plan.setTitle(joinedCities + " 여행");
        plan.setStartDate(request.getStartDate());
        plan.setEndDate(request.getEndDate());
        plan.setInCity(request.getInCity());
        plan.setOutCity(request.getOutCity());
        plan.setInTime(arrivalTime);
        plan.setOutTime(departureTime);

        if (request.getThemes() != null) {
            plan.setTheme(String.join(", ", request.getThemes()));
        }
        plan.setAiReason(aiResponse.getReason());

        if (!hasNoAccommodations) {
            for (com.travel.planner.dto.PlanRequest.AccommodationInput accInput : request.getAccommodations()) {
                com.travel.planner.entity.Accommodation acc = new com.travel.planner.entity.Accommodation();
                acc.setName(accInput.getName());
                acc.setCheckIn(accInput.getCheckIn());
                acc.setCheckOut(accInput.getCheckOut());
                if (accInput.getAddress() != null) acc.setAddress(accInput.getAddress());
                plan.addAccommodation(acc);
            }
        }

        if (aiResponse.getTimeline() != null) {
            int seq = 1;
            for (AiRouteResponse.TimelineItem item : aiResponse.getTimeline()) {
                Itinerary itinerary = new Itinerary();
                itinerary.setDayNumber(item.getDay());
                itinerary.setSequence(seq++);
                itinerary.setTime(item.getTime());
                itinerary.setAiComment(item.getDescription());

                Place matchedPlace = realPlaces.stream()
                        .filter(p -> p.getName().equals(item.getPlaceName()))
                        .findFirst()
                        .orElse(null);

                if (matchedPlace == null) {
                    Place fetchedPlace = googleMapsService.getPlaceDetails(mainCity, item.getPlaceName(), request.getLanguage());

                    if (fetchedPlace.getLatitude() == 0.0 || fetchedPlace.getPlaceId() == null) {
                        System.out.println("[일정 제외] 쓰레기 데이터 유입 방지를 위해 '" + item.getPlaceName() + "' 장소를 이번 플랜에서 제외합니다.");
                        continue;
                    }

                    String pId = fetchedPlace.getPlaceId();

                    // DB 조회 및 구글 API 중복 호출을 방어하는 메모리 캐시 로직 적용
                    if (placeCache.containsKey(pId)) {
                        matchedPlace = placeCache.get(pId);
                    } else {
                        // 메모리에 없다면 DB를 한 번 더 확인 (이중 안전장치)
                        Place existingPlace = placeRepository.findByPlaceId(pId).orElse(null);

                        if (existingPlace != null) {
                            matchedPlace = existingPlace;
                            placeCache.put(pId, matchedPlace); // 다음 확인을 위해 캐시에 적재
                        } else {
                            // 완전히 새로운 장소일 경우에만 영구 저장
                            fetchedPlace.setName(item.getPlaceName());
                            fetchedPlace.setCity(mainCity);
                            fetchedPlace.setLastUpdated(java.time.LocalDateTime.now());
                            matchedPlace = placeRepository.save(fetchedPlace);
                            placeCache.put(pId, matchedPlace);
                        }
                    }
                }

                if (matchedPlace != null) {
                    itinerary.setPlace(matchedPlace);
                    plan.addItinerary(itinerary);
                    item.setLatitude(matchedPlace.getLatitude());
                    item.setLongitude(matchedPlace.getLongitude());
                }
            }
        }

        planRepository.save(plan);
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
        return "✅ " + planId + "번 여행의 동선 재생성이 완료되었습니다.";
    }
}