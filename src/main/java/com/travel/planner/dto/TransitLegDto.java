package com.travel.planner.dto;

import lombok.Getter;
import lombok.Setter;

import java.util.ArrayList;
import java.util.List;

/** 직전 일정에서 이 일정까지 가는 방법 (노선·요금·단계). 시각표가 아니라 거리와 평균 속도로 계산한 추정값이다. */
@Getter
@Setter
public class TransitLegDto {
    private String mode;            // "도보", "철도", "지하철", "신칸센", "노면전차", "철도·지하철", "버스 등", "자동차"
    private int minutes;            // 이 경로의 추정 소요 시간(분). 일정의 이동 시간(travelMinutes)과 다를 수 있다
    private Integer fareYen;        // 성인 1인 요금(엔). 알 수 없으면 null
    private Integer childFareYen;   // 아동 1인 요금(엔)
    private boolean estimated;      // 추정값인가 (지금은 항상 true)
    private String source;          // "walk" / "rail-network"(전국 철도망 계산) / "distance"(노선 미확인, 거리 기준)
    private String summary;         // 한 줄 요약: "JR 야마노테선 신주쿠역 → 시부야역 (3정거장)"
    private String note;            // 주의할 점 (추정 기준 등)
    private Double distanceKm;      // 직선거리
    private int transfers;          // 환승 횟수
    private List<Step> steps = new ArrayList<>();

    @Getter
    @Setter
    public static class Step {
        private String type;        // "도보" / "승차" / "환승"
        private String category;    // 승차일 때: "JR", "지하철", "신칸센" …
        private String line;        // 노선명
        private String operator;    // 운영 회사
        private String from;        // 출발 (역 이름 또는 장소 이름)
        private String to;          // 도착
        private int minutes;
        private Integer stops;      // 승차일 때 정거장 수
        private Double km;
    }
}
