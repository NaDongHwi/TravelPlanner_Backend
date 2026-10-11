package com.travel.planner.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 역 쌍 요금: "이 역에서 저 역까지 얼마" (방향 구분 없음).
 * 거리로는 나오지 않는 값(운영사가 따로 정한 특정 구간 운임, 신칸센 특급요금)을 확인해서 넣는다. 요금표보다 먼저 쓴다.
 */
@Entity
@Getter
@Setter
@Table(name = "transit_fare_pair")
public class TransitFarePair {

    public static final String FARE = "FARE";             // 운임
    public static final String SURCHARGE = "SURCHARGE";   // 신칸센 자유석 특급요금

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 100)
    private String groupName;         // 요금 단위: JR 은 "JR", 그 외에는 운영사 이름 원문

    @Column(name = "station_a", nullable = false, length = 100)
    private String stationA;          // 역 이름 원문

    @Column(name = "station_b", nullable = false, length = 100)
    private String stationB;

    @Column(nullable = false, length = 20)
    private String fareKind;          // FARE / SURCHARGE

    @Column(nullable = false)
    private Integer adultYen;

    private Integer childYen;         // 없으면 성인의 절반으로 계산

    @Column(length = 500)
    private String referenceUrl;

    private LocalDate verifiedAt;

    @Column(columnDefinition = "TEXT")
    private String note;

    @Column(length = 20)
    private String source;            // bundled / manual

    private LocalDateTime updatedAt;
}
