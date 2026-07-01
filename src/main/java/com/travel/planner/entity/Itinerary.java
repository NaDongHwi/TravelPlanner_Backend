package com.travel.planner.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

@Entity
@Getter
@Setter
public class Itinerary {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // 어떤 여행 계획에 속한 일정인지
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "plan_id", nullable = false)
    private Plan plan;

    // 방문할 장소
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "place_id", nullable = false)
    private Place place;

    @Column(nullable = false)
    private Integer dayNumber; // 1일차, 2일차 등

    @Column(nullable = false)
    private Integer sequence; // 그 날의 방문 순서 (1, 2, 3...)

    // AI가 생성해 준 동선 배치 사유
    @Column(columnDefinition = "TEXT")
    private String aiComment;
}