package com.travel.planner.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.travel.planner.transit.PassAdvisor;
import lombok.Getter;
import lombok.Setter;

import java.util.ArrayList;
import java.util.List;

/**
 * 직전 일정에서 이 일정까지 가는 방법 (노선·요금·단계).
 * 소요 시간은 추정값이다: 철도는 거리와 평균 속도, 버스 등 GTFS 노선은 시각표의 평균 소요 시간과 운행 간격으로 계산한다.
 * 개별 열차·버스의 출발 시각은 다루지 않는다.
 */
@Getter
@Setter
public class TransitLegDto {
    private String mode;            // "도보", "철도", "지하철", "신칸센", "노면전차", "버스", "JR·버스", "버스 등", "자동차"
    private int minutes;            // 이 경로의 추정 소요 시간(분). 일정의 이동 시간(travelMinutes)과 다를 수 있다
    private Integer fareYen;        // 성인 1인 요금(엔). 알 수 없으면 null
    private Integer childFareYen;   // 아동 1인 요금(엔)
    private boolean estimated;      // 소요 시간이 추정값인가 (개별 출발 시각을 쓰지 않으므로 항상 true)
    private String source;          // "walk" / "rail-network"(전국 철도망) / "gtfs"(운영사 시각표 자료) / "rail-network+gtfs" / "bus-stops"(전국 버스 정류장 자료로 찾은 노선, 시각 미확인) / "taxi"(잇는 대중교통이 없어 택시) / "distance"(노선 자료가 없어 노선 미확인, 거리 기준)
    private String fareSource;      // 요금의 근거. "official"(운영사가 정한 값: 버스 등의 요금표, 등록한 역 쌍 요금) / "table"(운영사 요금표에 계산한 거리를 넣은 값)
                                    // / "estimate"(종류별 대표 요금표나 거리로 추정) / "mixed"(추정이 일부 섞임). 도보·자동차는 null
    private String summary;         // 한 줄 요약: "JR 야마노테선 신주쿠역 → 시부야역 (3정거장)"
    private String note;            // 주의할 점 (추정 기준, 운행 횟수가 적은 노선 등)
    private Double distanceKm;      // 직선거리
    private int transfers;          // 환승 횟수
    private List<Step> steps = new ArrayList<>();

    /** 패스 추천 계산용: 이 구간에서 요금을 낸 단위들 (운영사·노선 원문, 좌표 포함). 응답에는 내보내지 않는다 */
    @JsonIgnore
    private List<PassAdvisor.Ride> fareRides = new ArrayList<>();

    @Getter
    @Setter
    public static class Step {
        private String type;        // "도보" / "대기"(처음 타기 전 기다림. 철도는 개찰·승강장 이동 포함) / "승차" / "환승"(걷기 + 기다림)
        private String category;    // 승차일 때: "JR", "지하철", "신칸센", "버스" …
        private String line;        // 노선명·계통 번호
        private String operator;    // 운영 회사
        private String from;        // 출발 (역·정류장 이름 또는 장소 이름)
        private String to;          // 도착
        private int minutes;
        private Integer stops;      // 승차일 때 정거장 수
        private Double km;

        // ---- 버스 등 시각표 자료(GTFS)가 있는 노선을 탈 때만 채워진다 ----
        private String fromOriginal;     // 정류장 이름 일본어 원문 (현지 표지판과 맞춰 볼 수 있게)
        private String toOriginal;
        private String headsign;         // 행선지 표시 (일본어 원문)
        private Integer fareYen;         // 이 구간 요금
        private Boolean fareConfirmed;   // true = 운영사가 정한 값, false = 요금표에 거리를 넣었거나 추정한 값
        private Integer tripsPerDay;     // 이 구간을 바로 가는 편의 하루 운행 횟수 (그 요일 기준)
        private Integer headwayMinutes;  // 평균 운행 간격(분)
        private String firstDeparture;   // 타는 정류장 기준 첫차 "06:35"
        private String lastDeparture;    // 막차 "21:10"
        private List<String> alternatives;   // 같은 구간을 바로 가는 다른 노선

        // ---- 승차 단계의 요금 (철도는 같은 회사를 이어 타면 표 한 장이므로, 묶음의 첫 승차에만 요금이 들어간다) ----
        private String fareBasis;        // "official" / "table" / "estimate" (위 fareSource 와 같은 뜻)
        private String fareThroughTo;    // 이 요금으로 갈아타며 계속 가는 마지막 역 (한 번만 타면 null)
        private Integer surchargeYen;    // fareYen 에 들어 있는 신칸센 자유석 특급요금 (없으면 null)
    }
}
