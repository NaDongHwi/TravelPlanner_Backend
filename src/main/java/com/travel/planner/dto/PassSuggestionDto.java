package com.travel.planner.dto;

import lombok.Getter;
import lombok.Setter;

import java.util.List;

/** 이 일정에서 사면 이득인 교통 패스 한 장 */
@Getter
@Setter
public class PassSuggestionDto {
    private String key;                  // 패스 식별자 (예: "tokyo-subway-ticket-24h")
    private String name;                 // "Tokyo Subway Ticket 24시간권"
    private String city;                 // "도쿄"
    private int priceYen;                // 성인 1인 가격
    private Integer childPriceYen;       // 어린이 가격 (없으면 null)
    private String validity;             // "24시간" / "1일" / "연속 3일"

    private List<Integer> days;          // 이 패스를 쓰는 일차 (1부터)
    private String startDate;            // 쓰기 시작하는 날 "2026-10-12" (날짜를 모르면 null)
    private String endDate;              // 마지막으로 쓰는 날
    private String startTime;            // 시간권일 때 처음 타는 시각 "09:20" (여기서부터 유효 시간이 흐른다)

    private int coveredFareYen;          // 이 패스로 타는 구간들의 요금 합 (패스가 없을 때 낼 돈)
    private int savingYen;               // 아끼는 금액 = coveredFareYen − priceYen
    private int rideCount;               // 이 패스로 타는 횟수 (요금을 내는 단위 기준)
    private boolean estimated;           // 요금에 추정값이 섞여 있어 아끼는 금액도 추정인가
    private List<String> lines;          // 이 패스로 타는 노선 (최대 5개)

    private String summary;              // 한 문장 안내
    private String conditions;           // 살 수 있는 사람·사용 조건 (예: 방일 외국인 전용, 토·일·공휴일만)
    private String note;                 // 범위의 예외 등 알아 둘 점
    private boolean approximateCoverage; // 이용 범위를 대략적인 지역 경계로 판단했는가 (구입 전에 범위를 확인해야 한다)
    private String url;                  // 공식 안내 페이지
    private String verifiedAt;           // 가격·범위를 확인한 날 "2026-10-11"
}
