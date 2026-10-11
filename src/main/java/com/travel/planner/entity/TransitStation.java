package com.travel.planner.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

/**
 * 철도역 (노선별 한 건). 같은 역이라도 노선이 다르면 행이 따로 있고, groupCode 가 같으면 환승할 수 있는 같은 역이다.
 * 국토교통성 국토수치정보(철도)에서 가져온다.
 */
@Entity
@Getter
@Setter
@Table(name = "transit_station")
public class TransitStation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(length = 30)
    private String code;          // 원본의 역 코드

    @Column(length = 30)
    private String groupCode;     // 같은 역으로 묶이는 그룹 코드

    @Column(nullable = false, length = 100)
    private String name;          // 역명 (일본어 원문)

    @Column(name = "operator_name", nullable = false, length = 100)
    private String operator;      // 운영 회사 (일본어 원문)

    @Column(name = "line_name", nullable = false, length = 100)
    private String line;          // 노선명 (일본어 원문)

    @Column(length = 5)
    private String railClass;     // 원본의 철도 구분 코드

    @Column(length = 5)
    private String operatorType;  // 원본의 사업자 종별 코드

    @Column(nullable = false, length = 20)
    private String category;      // SHINKANSEN / JR / SUBWAY / PRIVATE / THIRD_SECTOR / TRAM / GUIDED / CABLE

    @Column(nullable = false)
    private Double latitude;

    @Column(nullable = false)
    private Double longitude;

    @Column(length = 60)
    private String source;        // 어느 파일에서 가져왔는지
}
