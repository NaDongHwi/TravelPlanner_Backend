package com.travel.planner.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import java.time.LocalDate;

@Entity
@Getter
@Setter
public class Accommodation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // 이 숙소가 어떤 여행 계획(Plan)에 속해 있는지 연결합니다. (다대일 관계)
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "plan_id", nullable = false)
    private Plan plan;

    @Column(nullable = false)
    private String name; // 숙소 이름 (예: "도쿄 프린스 호텔")

    @Column
    private String address; // 숙소 주소 또는 구글 placeId

    @Column(nullable = false)
    private LocalDate checkIn; // 체크인 날짜

    @Column(nullable = false)
    private LocalDate checkOut; // 체크아웃 날짜
}