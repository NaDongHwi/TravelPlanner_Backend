package com.travel.planner.dto;

import lombok.Getter;
import lombok.Setter;

/** 역 쌍 요금 한 건 */
@Getter
@Setter
public class FarePairDto {
    private Long id;            // (응답 전용)
    private String group;       // 요금 단위: JR 은 "JR", 그 외에는 운영사 이름 원문
    private String a;           // 역 이름 원문
    private String b;
    private String kind;        // FARE(운임, 기본값) / SURCHARGE(신칸센 자유석 특급요금)
    private Integer adultYen;
    private Integer childYen;   // 비우면 성인의 절반
    private String reference;
    private String verifiedAt;
    private String note;
    private String source;      // (응답 전용)
}
