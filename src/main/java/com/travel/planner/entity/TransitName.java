package com.travel.planner.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

/** 역·노선·운영 회사 이름의 한국어 표기. 원본에는 일본어만 있어 AI 로 채운다. */
@Entity
@Getter
@Setter
@Table(name = "transit_name")
public class TransitName {

    public static final String STATION = "STATION";
    public static final String LINE = "LINE";
    public static final String OPERATOR = "OPERATOR";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 10)
    private String kind;

    @Column(nullable = false, length = 100)
    private String ja;

    @Column(nullable = false, length = 100)
    private String ko;
}
