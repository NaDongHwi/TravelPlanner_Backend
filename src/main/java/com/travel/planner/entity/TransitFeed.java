package com.travel.planner.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 가져온 GTFS 피드(운영사가 공개한 노선·시각표·요금 파일) 하나의 등록 정보.
 * 어디서 받았는지, 언제까지 유효한지, 출처 표시에 쓸 이용 조건이 무엇인지 남긴다.
 */
@Entity
@Getter
@Setter
@Table(name = "transit_feed")
public class TransitFeed {

    public static final String OK = "OK";
    public static final String EXPIRED = "EXPIRED";
    public static final String FAILED = "FAILED";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 60)
    private String feedKey;          // 우리가 붙인 짧은 이름 (예: toei-bus). 다시 가져오면 같은 키의 자료를 통째로 바꾼다

    @Column(length = 150)
    private String name;             // 표시 이름

    @Column(columnDefinition = "TEXT")
    private String url;              // 내려받은 주소 (파일에서 가져왔으면 파일 경로)

    @Column(length = 300)
    private String license;          // 이용 조건·출처 표시 (예: CC BY 4.0 / 東京都交通局)

    @Column(length = 500)
    private String agencies;         // 운영사 이름들 (일본어 원문, " / " 로 구분)

    private LocalDate validFrom;     // 파일의 유효 기간
    private LocalDate validTo;

    private LocalDateTime importedAt;    // 마지막으로 성공한 가져오기
    private LocalDateTime checkedAt;     // 마지막으로 시도한 시각 (실패 포함)

    private Integer stopCount;
    private Integer patternCount;
    private Integer routeCount;
    private Integer tripCount;
    private Integer fareCoveragePercent; // 정류장 쌍 가운데 요금을 아는 비율

    private Boolean includeRail;     // 철도·지하철 노선까지 가져왔는가 (다시 가져올 때 같은 방식으로 읽는다)

    @Column(length = 10)
    private String status;           // OK / EXPIRED(유효 기간이 지난 파일) / FAILED(마지막 시도 실패, 이전 자료는 유지)

    @Column(columnDefinition = "TEXT")
    private String message;          // 가져오기 결과·경고·오류
}
