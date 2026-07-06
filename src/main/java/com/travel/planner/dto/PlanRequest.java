package com.travel.planner.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDate;
import java.util.List;

@Getter
@Setter
public class PlanRequest {

    @Schema(description = "여행 시작일 (Step 1)", example = "2026-07-02")
    private LocalDate startDate;

    @Schema(description = "여행 종료일 (Step 1)", example = "2026-07-05")
    private LocalDate endDate;

    @Schema(description = "여행 도시 목록 (Step 2)", example = "[\"오사카\", \"교토\"]")
    private List<String> cities;

    @Schema(description = "동행자 유형 (Step 3)", example = "친구")
    private String companion;

    @Schema(description = "여행 테마 최대 3개 (Step 4)", example = "[\"맛집\", \"사진\", \"카페\"]")
    private List<String> themes;

    @Schema(description = "주요 이동수단 (Step 5)", example = "도보 및 대중교통")
    private String transportation;

    @Schema(description = "고정 일정 리스트 (Step 6)")
    private List<FixedScheduleInput> fixedSchedules;

    @Getter @Setter
    public static class FixedScheduleInput {
        private String name;
        private java.time.LocalTime startTime;
        private java.time.LocalTime endTime;
    }

    // 입출국 도시
    private String inCity;
    private String outCity;

    // 입출국 시간 (예: "오전", "오후", "저녁", "미정")
    private String inTime;
    private String outTime;

    @Schema(description = "사용자 앱/단말기 언어 설정", example = "ko")
    private String language;

    @Schema(description = "사용자가 확정한 숙소 리스트 (없으면 빈 배열)")
    private List<AccommodationInput> accommodations;

    @Schema(description = "숙소 역제안 받기 여부 (true: 추천해줘, false: 숙소 없이 동선 짜줘)", example = "true")
    private boolean suggestHotel;

    // 내부 클래스로 숙소 정보 규격 정의
    @Getter
    @Setter
    public static class AccommodationInput {
        private String name;
        private String address; // 혹은 구글 placeId
        private LocalDate checkIn;
        private LocalDate checkOut;
    }
}