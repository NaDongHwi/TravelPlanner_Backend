package com.travel.planner.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

@Entity
@Getter
@Setter
public class Place {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String name;

    @Column
    private String city;

    // K-Means 및 TSP 연산을 위한 핵심 위도/경도 데이터
    @Column(nullable = false)
    private Double latitude;

    @Column(nullable = false)
    private Double longitude;

    // 무장애 설비 여부 등 (기획서 제약 조건 반영)
    private Boolean isWheelchairAccessible;

    // AI 데이터 파이프라인으로 채워 넣을 테마 (예: "맛집, 사진")
    @Column(length = 100)
    private String theme;

    @Column(length = 20)
    private String placeType; // 예: "실내", "실외", "복합"

    // 구글 API로 미리 긁어올 영업시간 (예: "09:00-21:00")
    @Column(length = 255)
    private String openingHours;

    // 30일마다 갱신하기 위한 마지막 업데이트 시간 기록
    @Column
    private java.time.LocalDateTime lastUpdated;

    // [중복 방어 핵심] 구글 고유 place_id (Unique 제약 조건 설정)
    @Column(unique = true, nullable = false)
    private String placeId;

    @Column(name = "category")
    private String category; // 역할 분류: 관광지, 식음, 쇼핑, 숙소, 교통

    public String getCategory() {
        return category;
    }

    public void setCategory(String category) {
        this.category = category;
    }
}