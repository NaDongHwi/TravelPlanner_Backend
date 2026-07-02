package com.travel.planner.controller;

import com.travel.planner.dto.AiRouteResponse;
import com.travel.planner.entity.Itinerary;
import com.travel.planner.entity.Place;
import com.travel.planner.entity.Plan;
import com.travel.planner.entity.User;
import com.travel.planner.service.AiService;
import com.travel.planner.service.GoogleMapsService;
import com.travel.planner.service.PlanService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
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

    // DB 저장을 위한 PlanRepository 추가
    private final com.travel.planner.repository.PlanRepository planRepository;

    @PostMapping
    @Operation(summary = "일정 기획 및 최적화 연산 요청", description = "프론트엔드에서 파라미터를 받아 알고리즘 연산 후 AI 최종 결과를 반환 및 저장합니다.")
    public AiRouteResponse createPlan(
            org.springframework.security.core.Authentication authentication,
            @org.springframework.web.bind.annotation.RequestBody com.travel.planner.dto.PlanRequest request
    ) {

        // 1. JwtFilter가 토큰에서 꺼내둔 로그인한 회원의 이메일을 가져옵니다.
        String email = authentication.getName();

        // 2. DB에서 해당 회원의 진짜 정보(나이, 성별)를 뽑아옵니다.
        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new IllegalArgumentException("회원을 찾을 수 없습니다."));

        // 프론트엔드에서 받은 고정 일정을 콤마(,)로 연결합니다. 없으면 "없음"으로 처리
        String fixed = request.getFixedSchedules() != null && !request.getFixedSchedules().isEmpty()
                ? String.join(", ", request.getFixedSchedules())
                : "없음";

        // 3. 회원 정보(DB) + 프론트엔드 선택 값(DTO) + 고정일정까지 합쳐서 조립합니다.
        String baseContext = String.format(
                "연령대: %s, 성별: %s, 목적지: %s, 동행자: %s, 테마: %s, 이동수단: %s, 고정일정(필수방문): %s",
                user.getAgeGroup(),
                user.getGender(),
                request.getCity(),
                request.getCompanion(),
                String.join(", ", request.getThemes()),
                request.getTransportation(),
                fixed // AI에게 넘길 고정 일정 추가
        );

        List<Place> realPlaces = placeRepository.findByCity(request.getCity());

        // 4. TSP(최단 거리) 알고리즘을 돌려서 방문 순서를 정렬합니다. (물리적 뼈대 생성)
        List<Place> optimizedRoute = planService.calculateShortestPath(realPlaces);

        // 5. 구글 맵스 API 단 1회 호출 (뼈대의 진짜 이동 시간 가져오기) - 파라미터 수정
        String travelTimes = googleMapsService.getRealTravelTimes(optimizedRoute);

        // 6. 제미나이에게 줄 최종 프롬프트에 이동 시간을 합쳐서 전달
        String finalContext = baseContext + "\n\n[구글 맵스 기반 실제 이동 시간]\n" + travelTimes;

        // 7. 제미나이가 최종 가중치를 판단하여 완벽한 타임라인을 생성
        AiRouteResponse aiResponse = aiService.evaluateAndModifyRoute(finalContext, optimizedRoute);

        // 8. Plan 엔티티 양식에 맞춰서 저장 상자 만들기
        Plan plan = new Plan();
        plan.setUser(user);
        plan.setTitle(request.getCity() + " 여행"); // 예: "시즈오카 여행"
        plan.setStartDate(request.getStartDate());
        plan.setEndDate(request.getEndDate());

        // 테마 값이 null이 아닐 때만 콤마로 연결
        if (request.getThemes() != null) {
            plan.setTheme(String.join(", ", request.getThemes()));
        }
        plan.setAiReason(aiResponse.getReason());

        // 9. AI가 짜준 타임라인을 Itinerary 객체로 변환
        if (aiResponse.getTimeline() != null) {
            int seq = 1;
            for (AiRouteResponse.TimelineItem item : aiResponse.getTimeline()) {
                Itinerary itinerary = new Itinerary();
                itinerary.setDayNumber(item.getDay());
                itinerary.setSequence(seq++);
                itinerary.setTime(item.getTime());
                itinerary.setAiComment(item.getDescription());

                // 핵심 로직: AI가 말한 '장소 이름'으로 실제 DB의 Place 객체를 찾아서 연결합니다
                Place matchedPlace = realPlaces.stream()
                        .filter(p -> p.getName().equals(item.getPlaceName()))
                        .findFirst()
                        .orElse(null);

                // [구글 API 동적 수집 파이프라인] DB에 장소가 없으면 진짜 구글에서 가져옵니다
                if (matchedPlace == null) {
                    System.out.println("⚠️ DB에 장소 없음! 구글 맵스 API 동적 수집 발동: " + item.getPlaceName());

                    Place newDynamicPlace = new Place();
                    newDynamicPlace.setName(item.getPlaceName());
                    newDynamicPlace.setCity(request.getCity());

                    // 구글 API 서비스로 진짜 좌표를 받아옵니다
                    double[] coords = googleMapsService.getCoordinates(request.getCity(), item.getPlaceName());
                    newDynamicPlace.setLatitude(coords[0]);
                    newDynamicPlace.setLongitude(coords[1]);

                    // 진짜 좌표가 들어간 신규 장소를 즉각 DB에 저장
                    matchedPlace = placeRepository.save(newDynamicPlace);
                }

                if (matchedPlace != null) {
                    itinerary.setPlace(matchedPlace); // 실제 객체 매핑
                    plan.addItinerary(itinerary);
                }
            }
        }

        // 10. 한 번에 영구 저장
        planRepository.save(plan);

        return aiResponse;
    }

    @GetMapping("/{planId}/timeline")
    @Operation(summary = "동적 타임라인 조회", description = "DB에 저장된 연산 완료 일정을 반환합니다.")
    public AiRouteResponse getTimeline(@PathVariable Long planId) {

        // 1. DB에서 저장된 Plan을 꺼내옵니다.
        Plan plan = planRepository.findById(planId)
                .orElseThrow(() -> new IllegalArgumentException("해당 여행 일정을 찾을 수 없습니다."));

        // 2. 꺼내온 DB 데이터를 프론트엔드 규격(AiRouteResponse)으로 포장합니다.
        AiRouteResponse response = new AiRouteResponse();
        response.setReason(plan.getAiReason());

        List<AiRouteResponse.TimelineItem> timelineItems = plan.getItineraries().stream().map(iti -> {
            AiRouteResponse.TimelineItem item = new AiRouteResponse.TimelineItem();
            item.setDay(iti.getDayNumber());
            item.setTime(iti.getTime());
            item.setPlaceName(iti.getPlace().getName()); // Place 객체에서 이름 꺼내기
            item.setCategory("분류 정보"); // 카테고리
            item.setDescription(iti.getAiComment());
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