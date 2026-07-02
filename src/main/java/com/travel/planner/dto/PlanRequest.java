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

    @Schema(description = "여행 도시 (Step 2)", example = "도쿄")
    private String city;

    @Schema(description = "동행자 유형 (Step 3)", example = "친구")
    private String companion;

    @Schema(description = "여행 테마 최대 3개 (Step 4)", example = "[\"맛집\", \"사진\", \"카페\"]")
    private List<String> themes;

    @Schema(description = "주요 이동수단 (Step 5)", example = "도보 및 대중교통")
    private String transportation;

    @Schema(description = "고정 일정 리스트 (Step 6)", example = "[\"아키하바라 애니메이트\"]")
    private List<String> fixedSchedules;
}