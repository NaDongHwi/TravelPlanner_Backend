package com.travel.planner.dto;

import lombok.Getter;
import lombok.Setter;

import java.util.List;

@Getter
@Setter
public class AiRouteResponse {

    // 피그마 UI에 맞춰 일자별/시간대별 일정을 담는 리스트
    private List<TimelineItem> timeline;

    // 전체 동선에 대한 AI의 추천 사유
    private String reason;

    @Getter
    @Setter
    public static class TimelineItem {
        private int day;            // 예: 1 (1일차)
        private String time;        // 예: "09:00"
        private String placeName;   // 예: "아사쿠사"
        private String category;    // 예: "관광", "문화", "맛집" 등
        private String description; // 예: "현지인들에게 인기 있는 전통 거리입니다."
    }
}