package com.travel.planner.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

/** 같은 노선에서 서로 이웃한 두 역과 그 사이의 선로 거리. 방향 없이 한 쌍에 한 건만 둔다. */
@Entity
@Getter
@Setter
@Table(name = "transit_link")
public class TransitLink {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long fromStationId;

    @Column(nullable = false)
    private Long toStationId;

    @Column(nullable = false)
    private Double distanceKm;
}
