package com.travel.planner.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

@Getter
@Setter
public class PlanRequest {

    @com.fasterxml.jackson.annotation.JsonFormat(shape = com.fasterxml.jackson.annotation.JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd")
    @Schema(description = "여행 시작일 (Step 1)", example = "2026-07-02")
    private LocalDate startDate;

    @com.fasterxml.jackson.annotation.JsonFormat(shape = com.fasterxml.jackson.annotation.JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd")
    @Schema(description = "여행 종료일 (Step 1)", example = "2026-07-05")
    private LocalDate endDate;

    @Schema(description = "여행 도시 목록 (Step 2)", example = "[\"오사카\", \"교토\"]")
    private List<String> cities;

    @Schema(description = "동행자 유형 (Step 3)", example = "친구")
    private String companion;

    @Schema(description = "여행 테마 최대 3개 (Step 4)", example = "[\"맛집\", \"사진\", \"카페\"]")
    private List<String> themes;

    // 사용자가 제외하고 싶은 테마(블랙리스트) 파라미터 추가
    @Schema(description = "제외할 테마 (선택 사항)", example = "[\"서브컬쳐\", \"액티비티\"]")
    private List<String> excludedThemes;

    // 유동적인 일과 시작/종료 시간 파라미터 추가 (미입력 시 09:00~22:00 적용)
    @Schema(description = "선호하는 하루 일정 시작 시간 (기본 09:00)", type = "string", example = "09:00:00")
    private LocalTime preferredStartTime;

    @Schema(description = "선호하는 하루 일정 종료 시간 (기본 22:00)", type = "string", example = "22:00:00")
    private LocalTime preferredEndTime;

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

    private String inCity;
    private String outCity;

    private String inTime;
    private String outTime;

    @Schema(description = "사용자 앱/단말기 언어 설정", example = "ko")
    private String language;

    @Schema(description = "사용자가 확정한 숙소 리스트 (없으면 빈 배열)")
    private List<AccommodationInput> accommodations;

    @Schema(description = "숙소 역제안 받기 여부 (true: 추천해줘, false: 숙소 없이 동선 짜줘)", example = "true")
    private boolean suggestHotel;

    @Getter
    @Setter
    public static class AccommodationInput {
        private String name;
        private String address;
        private LocalDate checkIn;
        private LocalDate checkOut;
    }
}