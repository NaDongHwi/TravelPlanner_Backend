package com.travel.planner.controller;

import com.travel.planner.dto.AiRouteResponse;
import com.travel.planner.dto.PlanRequest;
import com.travel.planner.dto.RerouteRequest;
import com.travel.planner.dto.RerouteResponse;
import com.travel.planner.service.PlanGenerationService;
import com.travel.planner.service.PlanModifierService;
import com.travel.planner.service.PlanValidationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/plans")
@RequiredArgsConstructor
@Tag(name = "1. 여행 일정 API", description = "자체 동선 최적화 알고리즘 기반 일정 생성 파이프라인")
public class PlanController {

    private final PlanGenerationService planGenerationService;
    private final PlanValidationService planValidationService;
    private final PlanModifierService planModifierService;
    private final com.travel.planner.service.PlanReplaceService planReplaceService;

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
    @Operation(summary = "일정 생성 요청",
            description = "입력 검증 -> 후보 수집 -> 공항/숙소 확정 -> 선정·검증 통합 엔진 -> 저장. 응답의 planId 로 재탐색/PDF 를 호출하고, warnings 에는 완전히 만족하지 못한 조건이 담깁니다.")
    public AiRouteResponse createPlan(Authentication authentication, @RequestBody PlanRequest request) {
        return planGenerationService.createPlan(authentication.getName(), request);
    }

    @PostMapping("/{planId}/reroute")
    @Operation(summary = "실시간 동적 경로 재탐색 (Dynamic Rerouting)",
            description = "사용자가 즉석에서 장소를 추가할 때 그 날 일정을 다시 계산합니다. 기존 장소가 시간 안에 들어가지 못하면 requireConfirmation=true 로 확인을 요청하고, forceDrop=true 로 다시 호출하면 해당 장소를 빼고 저장합니다.")
    public RerouteResponse modifyPlanRoute(
            Authentication authentication,
            @PathVariable Long planId,
            @RequestBody RerouteRequest request
    ) {
        return planModifierService.modifyPlanRoute(authentication.getName(), planId, request);
    }

    @PostMapping("/{planId}/replace")
    @Operation(summary = "일정 일부 바꾸기 (고른 일정만 다른 장소로)",
            description = "마음에 들지 않는 줄만 골라 다른 장소로 바꿉니다. 나머지 일정의 장소와 시각은 그대로 두고, 고른 줄의 앞뒤 일정 사이 시간에 맞는 곳만 넣기 때문에 "
                    + "뒤 일정이 밀리거나 빠지지 않습니다. targets(일차+순번) 또는 placeIds 로 고르고, theme 을 주면 그 테마의 장소로만 바꿉니다. "
                    + "식사 자리는 식당으로, 카페는 카페로 바꿉니다. 맞는 곳이 없으면 그 줄은 그대로 두고 changes[].message 에 이유를 담습니다. "
                    + "preview=true 면 저장하지 않고 결과만 돌려줍니다. 바꾼 장소는 기억해 두어 다시 바꿔도 같은 곳이 나오지 않습니다.")
    public com.travel.planner.dto.ReplaceResponse replacePlanItems(
            Authentication authentication,
            @PathVariable Long planId,
            @RequestBody com.travel.planner.dto.ReplaceRequest request
    ) {
        return planReplaceService.replace(authentication.getName(), planId, request);
    }
}
