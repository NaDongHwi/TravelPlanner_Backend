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

    // 저장된 여행 계획 ID (reroute / PDF 다운로드에 필요)
    private Long planId;

    // 조건을 완전히 만족하지 못한 부분에 대한 안내 (예: 특정 테마 후보 부족, 공항 추정 등)
    private List<String> warnings;

    @Getter
    @Setter
    public static class TimelineItem {
        private int day;            // 예: 1
        private String time;        // 시작(도착) 시각 "HH:mm"
        private String endTime;     // 종료 시각 "HH:mm" (숙소/공항 등은 null)
        private Integer travelMinutes; // 직전 항목에서 여기까지의 예상 이동 시간(분)
        private String placeId;     // 장소 고유 ID 필드
        private String placeName;   // 예: "아사쿠사"
        private String category;    // 예: "관광"
        private String description; // 예: "1줄 설명"

        // 프론트엔드 지도 렌더링을 위한 좌표 데이터
        private Double latitude;
        private Double longitude;

        // 프론트엔드 텍스트 칸 표시를 위해 추가된 필드 3개
        private String formattedAddress;
        private String phoneNumber;
        private String openingHours;
    }
}
