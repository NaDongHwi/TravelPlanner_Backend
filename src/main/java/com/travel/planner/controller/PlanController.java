package com.travel.planner.controller;

import com.travel.planner.dto.AiRouteResponse;
import com.travel.planner.entity.Place;
import com.travel.planner.service.AiService;
import com.travel.planner.service.PlanService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.List;

@RestController
@RequestMapping("/api/v1/plans")
@RequiredArgsConstructor
@Tag(name = "1. 여행 일정 API", description = "일정 기획 및 동선 최적화 관련 API (담당: 나동휘)")
public class PlanController {

    private final PlanService planService;
    private final AiService aiService;
    private final com.travel.planner.repository.PlaceRepository placeRepository;
    private final com.travel.planner.repository.UserRepository userRepository;

    @PostMapping
    @Operation(summary = "일정 기획 및 최적화 연산 요청", description = "프론트엔드에서 파라미터를 받아 알고리즘 연산 후 AI 최종 결과를 반환합니다.")
    public AiRouteResponse createPlan(org.springframework.security.core.Authentication authentication) { // 괄호 안에 인증 객체 추가

        // JwtFilter가 토큰에서 꺼내둔 로그인한 회원의 이메일을 가져옵니다.
        String email = authentication.getName();

        // DB에서 해당 회원의 진짜 정보(나이, 성별)를 뽑아옵니다.
        com.travel.planner.entity.User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new IllegalArgumentException("❌ 회원을 찾을 수 없습니다."));

        // 회원 정보로 AI에게 줄 조건을 동적으로 조립합니다
        String userContext = "연령대: " + user.getAgeGroup() + ", 성별: " + user.getGender() + ", 동행: 친구, 테마: 시즈오카 후지산 뷰와 인스타 감성";

        // DB에 저장된 시즈오카 명소 5곳을 전부 가져옵니다.
        List<Place> realPlaces = placeRepository.findAll();

        // TSP(최단 거리) 알고리즘을 돌려서 5곳의 방문 순서를 정렬합니다.
        List<Place> optimizedRoute = planService.calculateShortestPath(realPlaces);

        // 수학적으로 최적화된 동선을 AI에게 넘겨서 이유를 붙여달라고 합니다.
        return aiService.evaluateAndModifyRoute(userContext, optimizedRoute);
    }

    @GetMapping("/{planId}/timeline")
    @Operation(summary = "동적 타임라인 조회", description = "연산이 완료된 일자별 타임라인과 거점 데이터를 반환합니다.")
    public String getTimeline(@PathVariable Long planId) {
        return "✅ " + planId + "번 여행의 타임라인 데이터입니다.";
    }

    @PutMapping("/{planId}/timeline")
    @Operation(summary = "동선 재생성 (수정)", description = "사용자가 일정을 수정/삭제하면 알고리즘을 다시 돌립니다.")
    public String regenerateTimeline(@PathVariable Long planId) {
        return "✅ " + planId + "번 여행의 동선 재생성이 완료되었습니다.";
    }
}