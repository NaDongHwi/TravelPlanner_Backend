package com.travel.planner.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 운영사 요금표: "몇 km 까지 얼마". 운영사가 공개한 표를 그대로 옮겨 적는다.
 * 경로 계산에서 구한 거리를 이 표에 넣어 요금을 낸다. (등록된 표가 없는 운영사는 종류별 대표 요금표로 추정)
 */
@Entity
@Getter
@Setter
@Table(name = "transit_fare_table")
public class TransitFareTable {

    public static final String BUNDLED = "bundled";   // 프로젝트에 들어 있는 기본 자료에서 온 행
    public static final String MANUAL = "manual";     // 관리자 API 로 넣은 행 (기본 자료보다 먼저 쓴다)

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 80)
    private String tableKey;          // 식별자 (예: "jr-east", "tokyo-metro")

    @Column(nullable = false, length = 200)
    private String name;              // 표 이름 (예: "JR 동일본 간선")

    @Column(columnDefinition = "TEXT", nullable = false)
    private String operators;         // 이 표를 쓰는 운영사 (철도망의 운영사 이름 원문, 쉼표로 구분)

    @Column(length = 200)
    private String categories;        // 적용할 교통수단 종류 (JR, SUBWAY …, 쉼표로 구분). 비우면 가리지 않는다

    @Column(columnDefinition = "TEXT")
    private String lineFilter;        // 이 글자가 들어간 노선에만 적용 (쉼표로 구분). 비우면 전 노선

    @Column(columnDefinition = "TEXT")
    private String exceptLines;       // 이 글자가 들어간 노선에는 적용하지 않는다 (쉼표로 구분)

    @Column(columnDefinition = "TEXT")
    private String areaBoxes;         // 적용 지역 "남위도,서경도,북위도,동경도" 를 ; 로 구분. 비우면 어디서나

    @Column(columnDefinition = "TEXT", nullable = false)
    private String bands;             // "3:150,6:190,10:200" = 3km 까지 150엔, 6km 까지 190엔 …

    @Column(columnDefinition = "TEXT")
    private String stationAdds;       // 이 역에서 타거나 내리면 더 내는 금액 "関西空港:220,りんくうタウン:160"

    private Boolean childRoundDown;   // 아동 요금: true = 10엔 미만 버림(JR), 그 외 = 올림

    @Column(length = 30)
    private String effectiveFrom;     // 이 요금이 시행된 때 (예: "2026-03-14")

    @Column(length = 500)
    private String referenceUrl;      // 값을 확인한 자료의 주소

    private LocalDate verifiedAt;     // 값을 확인한 날

    @Column(columnDefinition = "TEXT")
    private String note;

    @Column(length = 20)
    private String source;            // bundled / manual

    private Boolean enabled;

    private LocalDateTime updatedAt;
}
