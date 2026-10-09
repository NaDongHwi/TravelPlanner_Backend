package com.travel.planner.dto;

import lombok.Getter;
import lombok.Setter;

import java.util.List;

@Getter
@Setter
public class AiRouteResponse {

    // 피그마 UI에 맞춰 일자별/시간대별 일정을 담는 리스트
    private List<TimelineItem> timeline;

    // 여행 전체 요약 문단 (어떤 여행인지: 도시·기간·테마·이동 순서·숙소·대표 방문지).
    // 이전에는 고정 문구였다. 기존 화면이 reason 을 그대로 보여 줘도 요약이 나오도록 같은 필드에 담는다.
    private String reason;

    // 여행 제목 (예: "시즈오카·하마마쓰 5박 6일 온천·자연 여행")
    private String title;

    // 일자별 개요 (1일차부터 순서대로, 일수만큼)
    private List<DaySummary> days;

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

        // 목록만 보고도 어떤 곳인지 알 수 있게 하는 정보 (자유 시간·자유 식사·고정 일정은 null)
        private String subType;            // 세부 유형: "라멘", "장어", "신사", "공원", "전망대", "쇼핑몰" …
        private String summary;            // 한 줄 소개: "연못을 따라 산책로가 이어지는 일본식 정원"
        private List<String> themes;       // 이 장소의 테마 (예: ["힐링", "자연"])
        private Double rating;             // 구글 평점 (없으면 null)
        private Integer userRatingCount;   // 구글 리뷰 수 (없으면 null)
    }

    /** 하루 일정의 개요 */
    @Getter
    @Setter
    public static class DaySummary {
        private int day;                   // 일차 (1부터)
        private String date;               // "2026-10-10"
        private String dayOfWeek;          // "토"
        private String city;               // 그 날의 중심 도시
        private String title;              // 예: "시즈오카 · 자연·온천"
        private String summary;            // 그 날이 어떤 하루인지 2~4문장
        private List<String> themes;       // 그 날 비중이 큰 테마 (많은 순, 최대 3개)
        private List<String> highlights;   // 주요 방문지 이름 (최대 3곳)
        private int visitCount;            // 방문 장소 수 (식당·카페 포함, 출발·도착·자유 시간 제외)
        private int mealCount;             // 그중 식사(식당·카페·주점, 자유 식사 포함) 수
        private int travelMinutes;         // 하루 이동 시간 합계(분)
        private String startTime;          // 출발 시각 "HH:mm"
        private String endTime;            // 도착 시각 "HH:mm"
        private Boolean badWeather;        // 비·눈 예보 (true/false, 예보 범위 밖이면 null)
    }
}
