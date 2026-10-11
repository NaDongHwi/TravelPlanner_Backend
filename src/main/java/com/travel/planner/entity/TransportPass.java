package com.travel.planner.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 교통 패스 (1일권·24시간권·광역 레일패스 등).
 * 가격과 "무엇을 탈 수 있는지"(coverage)를 확인해서 넣는다. 일정의 요금 합계와 견주어 이득일 때 추천한다.
 */
@Entity
@Getter
@Setter
public class TransportPass {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String name; // 예: "Tokyo Subway Ticket 72시간권"

    @Column(nullable = false)
    private String city; // 예: "도쿄" (여러 도시에 걸치면 대표 지역: "간사이", "전국")

    @Column(nullable = false)
    private Integer priceEnyen; // 패스 가격 (엔화, 성인)

    @Column(nullable = false)
    private Integer validityDays; // 유효 기간 (일 단위). 시간권은 걸치는 날 수 (24시간권 = 1)

    @Column(columnDefinition = "TEXT")
    private String coverageDescription; // 패스 적용 범위 및 혜택 설명 (사람이 읽는 글)

    private LocalDateTime lastUpdated; // 마지막 업데이트 시간

    // ---- 추천 계산에 쓰는 항목 (이전에 만든 행에는 비어 있고, 비어 있으면 추천 대상이 아니다) ----

    @Column(length = 80)
    private String passKey;           // 식별자 (예: "tokyo-subway-ticket-24h")

    private Integer childPriceYen;    // 어린이 가격

    private Integer validityHours;    // 처음 쓴 때부터 N시간짜리이면 그 시간 (24시간권 = 24). 달력 기준 일수권이면 null

    private Boolean consecutive;      // 일수권: 연속한 날에만 쓸 수 있는가 (null 이면 연속)

    @Column(length = 20)
    private String dayRule;           // ANY(매일) / WEEKDAY(평일만) / HOLIDAY(토·일·공휴일만)

    @Column(length = 100)
    private String extraDaysOfMonth;  // HOLIDAY 규칙에서 함께 쓸 수 있는 "매월 며칠" (쉼표로 구분, 예: "8")

    private Boolean yearEndAsHoliday; // HOLIDAY 규칙에서 연말연시(12/29~1/3)도 쓸 수 있는가

    @Column(columnDefinition = "TEXT")
    private String coverage;          // 탈 수 있는 범위 (JSON: 운영사·종류·노선·지역). 형식은 TransportPassDto.CoverDto

    @Column(columnDefinition = "TEXT")
    private String conditions;        // 살 수 있는 사람·사용 조건

    private Boolean approximate;      // 이용 범위를 대략적인 지역 경계로 적었는가

    @Column(length = 500)
    private String url;               // 공식 안내 페이지

    private LocalDate verifiedAt;     // 가격·범위를 확인한 날

    @Column(columnDefinition = "TEXT")
    private String note;

    @Column(length = 20)
    private String source;            // bundled / manual

    private Boolean enabled;
}
