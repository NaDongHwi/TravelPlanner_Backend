package com.travel.planner.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import java.time.LocalDate;
import java.util.List;
import java.util.ArrayList;

@Entity
@Getter
@Setter
public class Plan {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // 어떤 유저의 일정인지 연결 (다대일 관계)
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id")
    @org.hibernate.annotations.OnDelete(action = org.hibernate.annotations.OnDeleteAction.CASCADE)
    private User user;

    @Column(nullable = false)
    private String title;

    @Column(nullable = false)
    private LocalDate startDate;

    @Column(nullable = false)
    private LocalDate endDate;

    // 테마 (힐링, 액티비티 등)
    private String theme;

    @Column(columnDefinition = "TEXT")
    private String aiReason;

    @Column
    private String inCity;

    @Column
    private String outCity;

    @Column
    private String inTime; // 입국 시간 (오전/오후/저녁/미정)

    @Column
    private String outTime; // 출국 시간 (오전/오후/저녁/미정)

    // ---- 재탐색(reroute) 때 원래 요청 조건을 복원하기 위한 값들 ----
    @Column(length = 500)
    private String cities;              // "오사카, 교토"

    @Column
    private String companion;

    @Column
    private String transportation;

    @Column
    private java.time.LocalTime preferredStartTime;

    @Column
    private java.time.LocalTime preferredEndTime;

    @Column(length = 500)
    private String excludedThemes;

    @Column(columnDefinition = "TEXT")
    private String fixedSchedulesJson;  // 고정 일정 목록(JSON)

    @Column(length = 10)
    private String language;

    @OneToMany(mappedBy = "plan", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("dayNumber ASC, sequence ASC")
    private List<Itinerary> itineraries = new ArrayList<>();

    public void addItinerary(Itinerary itinerary) {
        itineraries.add(itinerary);
        itinerary.setPlan(this);
    }

    @OneToMany(mappedBy = "plan", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<Accommodation> accommodations = new ArrayList<>();

    // 숙소를 여행 계획에 담아주는 편의 메서드
    public void addAccommodation(Accommodation accommodation) {
        accommodations.add(accommodation);
        accommodation.setPlan(this);
    }
}
