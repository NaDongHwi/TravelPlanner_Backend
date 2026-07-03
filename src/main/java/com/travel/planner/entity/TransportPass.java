package com.travel.planner.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import java.time.LocalDateTime;

@Entity
@Getter
@Setter
public class TransportPass {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String name; // 예: "도쿄 서브웨이 티켓 72시간권"

    @Column(nullable = false)
    private String city; // 예: "도쿄"

    @Column(nullable = false)
    private Integer priceEnyen; // 패스 가격 (엔화)

    @Column(nullable = false)
    private Integer validityDays; // 유효 기간 (일 단위)

    @Column(columnDefinition = "TEXT")
    private String coverageDescription; // 패스 적용 범위 및 혜택 설명

    private LocalDateTime lastUpdated; // 마지막 업데이트 시간
}