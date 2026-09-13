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

    private RouteInfoDto transportOptimization;

    @Getter
    @Setter
    public static class TimelineItem {
        private int day;            // 예: 1
        private String time;        // 예: "오전/오후/저녁"
        private String placeId;     // 장소 고유 ID 필드
        private String placeName;   // 예: "아사쿠사"
        private String category;    // 예: "관광"
        private String description; // 예: "1줄 설명"

        // 프론트엔드 지도 렌더링을 위한 좌표 데이터
        private Double latitude;
        private Double longitude;
    }
}