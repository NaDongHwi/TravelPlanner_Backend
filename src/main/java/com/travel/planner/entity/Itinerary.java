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

    @Column(nullable = false)
    private String time;

    // 방문 종료 시각 ("HH:mm"). 숙소/공항 같은 앵커는 null.
    @Column
    private String endTime;

    // 자유시간·식사·고정 일정처럼 실제 장소가 아닌 항목의 표시 이름.
    // (이런 항목은 place 에 공용 더미 Place 가 연결되므로, 화면에는 이 값을 우선 보여준다.)
    @Column
    private String customTitle;

    // AI가 생성해 준 동선 배치 사유
    @Column(columnDefinition = "TEXT")
    private String aiComment;
}
