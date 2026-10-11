package com.travel.planner.dto;

import lombok.Getter;
import lombok.Setter;

import java.util.ArrayList;
import java.util.List;

/** 교통 패스 한 종류 (기본 자료 파일·관리자 API·패스 목록 응답이 같은 형식을 쓴다) */
@Getter
@Setter
public class TransportPassDto {
    private String key;                  // 식별자. 같은 키로 다시 보내면 덮어쓴다
    private String name;                 // 화면에 보일 이름
    private String city;                 // 대표 도시·지역
    private Integer priceYen;            // 성인 가격
    private Integer childPriceYen;
    private Integer days;                // 달력 기준 일수 (1일권 = 1). 시간권이면 비운다
    private Integer hours;               // 처음 쓴 때부터의 시간 (24시간권 = 24). 일수권이면 비운다
    private Boolean consecutive;         // 일수권: 연속한 날에만 쓰는가 (비우면 연속)
    private String dayRule;              // ANY(기본) / WEEKDAY / HOLIDAY
    private List<Integer> extraDaysOfMonth = new ArrayList<>();   // HOLIDAY 규칙에서 함께 쓸 수 있는 "매월 며칠"
    private Boolean yearEndAsHoliday;    // HOLIDAY 규칙에서 12/29~1/3 도 쓸 수 있는가
    private List<CoverDto> coverage = new ArrayList<>();          // 탈 수 있는 범위. 여러 줄 중 하나에만 맞으면 된다
    private String coverageDescription;  // 범위를 사람이 읽는 글로
    private String conditions;           // 살 수 있는 사람·사용 조건
    private Boolean approximate;         // 범위를 대략적인 지역 경계로 적었는가
    private String url;
    private String verifiedAt;
    private String note;
    private String source;               // (응답 전용) bundled / manual
    private Boolean enabled;             // 비우면 사용

    /** 범위 한 줄 */
    @Getter
    @Setter
    public static class CoverDto {
        private List<String> operators = new ArrayList<>();       // 운영사 이름 원문. 철도는 같아야 하고, 버스 등 GTFS 사업자는 이 글자를 포함하면 된다
        private List<String> categories = new ArrayList<>();      // 교통수단 종류. 비우면 신칸센을 뺀 전부
        private List<String> lines = new ArrayList<>();           // 이 글자가 들어간 노선만
        private List<String> exceptLines = new ArrayList<>();     // 이 글자가 들어간 노선 제외
        private List<String> exceptStations = new ArrayList<>();  // 이 역에서 타거나 내리면 제외
        private String boxes;                                     // 지역 "남위도,서경도,북위도,동경도;…"
    }
}
