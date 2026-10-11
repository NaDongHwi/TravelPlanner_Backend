package com.travel.planner.dto;

import lombok.Getter;
import lombok.Setter;

/** 노선의 운임 계산 거리(영업킬로) */
@Getter
@Setter
public class LineKmDto {
    private Long id;            // (응답 전용)
    private String line;        // 노선 이름 원문 (예: "東海道新幹線")
    private String stations;    // "東京:0,品川:6.8,新横浜:28.8"
    private String reference;
    private String verifiedAt;
    private String note;
    private String source;      // (응답 전용)
}
