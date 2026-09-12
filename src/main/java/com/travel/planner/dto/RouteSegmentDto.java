package com.travel.planner.dto;

import lombok.Data;

@Data
public class RouteSegmentDto {
    private String lineName;    // 탑승 노선명 (예: JR 야마노테선)
    private String operator;    // 운영사 (예: JR East)
    private int timeMinutes;    // 소요 시간(분)
    private int segmentFare;    // 구간 요금(엔)
}