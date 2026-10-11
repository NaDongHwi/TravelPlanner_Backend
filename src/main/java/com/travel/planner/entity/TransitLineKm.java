package com.travel.planner.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 노선의 운임 계산 거리(영업킬로). 신칸센처럼 운임을 재는 거리가 실제 선로 거리와 다른 노선에 등록한다.
 * 등록되어 있으면 그 노선을 탄 구간의 요금은 선로 거리 대신 이 거리로 계산한다.
 */
@Entity
@Getter
@Setter
@Table(name = "transit_line_km")
public class TransitLineKm {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 100)
    private String lineName;          // 노선 이름 (철도망의 노선명 원문, 예: "東海道新幹線")

    @Column(columnDefinition = "TEXT", nullable = false)
    private String stations;          // "東京:0,品川:6.8,新横浜:28.8" = 역 이름 원문과 기점부터의 누적 거리(km)

    @Column(length = 500)
    private String referenceUrl;

    private LocalDate verifiedAt;

    @Column(columnDefinition = "TEXT")
    private String note;

    @Column(length = 20)
    private String source;            // bundled / manual

    private LocalDateTime updatedAt;
}
