package com.travel.planner.controller;

import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/plans")
public class PlanController {

    // 1. 일정 기획 및 최적화 연산 요청 수신
    @PostMapping
    public String createPlan() {
        // 나중에 건의 님이 보낸 파라미터(3박4일, 휠체어 등)를 여기서 받아서 K-Means 연산을 돌릴 예정입니다.
        return "✅ AI 최적화 연산이 시작되었습니다. (임시 응답)";
    }

    // 2. 동적 타임라인 및 거점 조회
    @GetMapping("/{planId}/timeline")
    public String getTimeline(@PathVariable Long planId) {
        // 나중에 연산이 다 끝난 1일차, 2일차 타임라인과 추천 숙소 위치를 건의 님에게 던져줄 예정입니다.
        return "✅ " + planId + "번 여행의 타임라인 데이터입니다. (임시 응답)";
    }

    // 3. 동선 재생성 (일정 수정 시)
    @PutMapping("/{planId}/timeline")
    public String regenerateTimeline(@PathVariable Long planId) {
        // 사용자가 장소를 삭제하면 여기서 알림을 받고 다시 알고리즘을 굴립니다.
        return "✅ " + planId + "번 여행의 동선 재생성이 완료되었습니다. (임시 응답)";
    }
}