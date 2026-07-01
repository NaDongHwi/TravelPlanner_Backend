package com.travel.planner.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

@Entity
@Getter
@Setter
public class Traffic {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // 이 이동이 어떤 타임라인(Itinerary) 다음에 일어나는지
    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "itinerary_id")
    private Itinerary itinerary;

    @Column(nullable = false)
    private String transportType; // 대중교통, 도보, 렌트카 등

    private Integer estimatedCost; // 예상 비용 (엔화)

    private Integer durationMinutes; // 이동 소요 시간
}