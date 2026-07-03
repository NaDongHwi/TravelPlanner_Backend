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

    @OneToMany(mappedBy = "plan", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<Itinerary> itineraries = new ArrayList<>();

    public void addItinerary(Itinerary itinerary) {
        itineraries.add(itinerary);
        itinerary.setPlan(this);
    }
}