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

    // 구글 weekdayDescriptions 를 " | " 로 이은 표시용 문자열 (예: "월요일: 오전 9:00 ~ 오후 5:00 | ...")
    @Column(columnDefinition = "TEXT")
    private String openingHours;

    // 구글 regularOpeningHours.periods 원본 JSON (요일·시·분 구조).
    // 일정 엔진은 이 값을 우선 쓰고, 없을 때만 위 문자열을 해석한다.
    @Column(columnDefinition = "TEXT")
    private String openingPeriods;

    // 타임라인 표시용 주소/전화. 일정 생성 때마다 Place Details 를 부르지 않도록 수집 시 함께 저장한다.
    @Column(length = 500)
    private String address;

    @Column(length = 50)
    private String phone;

    // 구글 평점/리뷰 수. 테마가 같은 후보 사이의 우선순위(인지도)에 쓴다.
    @Column
    private Double rating;

    @Column
    private Integer userRatingCount;

    // 30일마다 갱신하기 위한 마지막 업데이트 시간 기록
    @Column
    private java.time.LocalDateTime lastUpdated;

    // [중복 방어 핵심] 구글 고유 place_id (Unique 제약 조건 설정)
    @Column(unique = true, nullable = false)
    private String placeId;

    @Column(name = "category")
    private String category; // 역할 분류: 관광지, 식음, 쇼핑, 숙소, 교통, 테마파크

    public String getCategory() {
        return category;
    }

    public void setCategory(String category) {
        this.category = category;
    }

    @Column
    private Integer recommendedDuration; // 평균 체류 시간 (분 단위)

    // 목록만 보고도 어떤 곳인지 알 수 있게 하는 정보 (AI 인리치먼트가 채운다. 비어 있으면 PlaceDescriber 가 기본 문구를 만든다)
    @Column(length = 50)
    private String subType;   // 세부 유형: "라멘", "스시", "신사", "공원", "전망대" 등

    @Column(length = 300)
    private String summary;   // 한 줄 소개: "에도 시대 정원을 재현한 일본식 정원" 등

    public Integer getRecommendedDuration() { return recommendedDuration; }
    public void setRecommendedDuration(Integer recommendedDuration) { this.recommendedDuration = recommendedDuration; }
}
