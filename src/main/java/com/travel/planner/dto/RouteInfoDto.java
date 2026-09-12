package com.travel.planner.dto;

import lombok.Data;
import java.util.List;
import java.util.Set;

@Data
public class RouteInfoDto {
    private int totalTime;
    private int ticketFare;
    private int icCardFare;

    private List<RouteSegmentDto> segments;
    private Set<String> operators; // 탑승한 모든 운영사 목록
    private List<String> pathDetails;

    private int optimalFare; // 최종 최적화 요금
    private String recommendedPassName; // 추천 패스권 이름
    private String optimizationMessage; // 프론트엔드에 띄워줄 절약 메시지
}