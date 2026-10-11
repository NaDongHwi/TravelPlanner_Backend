package com.travel.planner.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

/** GTFS 에서 가져온 정류장(승강장) 하나. 버스 정류장, 노면전차 정거장, 선착장 등. */
@Entity
@Getter
@Setter
@Table(name = "transit_stop")
public class TransitStop {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 60)
    private String feedKey;

    @Column(length = 120)
    private String stopCode;      // 원본의 stop_id

    @Column(nullable = false, length = 200)
    private String name;          // 일본어 원문

    @Column(length = 200)
    private String nameKo;        // 한국어 표기 (원본의 한국어 번역 또는 읽기를 한글로 옮긴 것). 없으면 null

    @Column(nullable = false)
    private Double latitude;

    @Column(nullable = false)
    private Double longitude;
}
