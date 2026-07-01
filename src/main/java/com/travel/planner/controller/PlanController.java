package com.travel.planner.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/plans")
@Tag(name = "1. 여행 일정 API", description = "일정 기획 및 동선 최적화 관련 API (담당: 나동휘)")
public class PlanController {

    @PostMapping
    @Operation(summary = "일정 기획 및 최적화 연산 요청", description = "프론트엔드에서 파라미터를 받아 K-Means 및 TSP 연산을 시작합니다.")
    public String createPlan() {
        return "✅ AI 최적화 연산이 시작되었습니다.";
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