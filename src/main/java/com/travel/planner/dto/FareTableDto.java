package com.travel.planner.dto;

import lombok.Getter;
import lombok.Setter;

import java.util.ArrayList;
import java.util.List;

/** 운영사 요금표 한 장 (기본 자료 파일과 관리자 API 가 같은 형식을 쓴다) */
@Getter
@Setter
public class FareTableDto {
    private String key;                 // 식별자 (예: "jr-east"). 같은 키로 다시 보내면 덮어쓴다
    private String name;                // 표 이름
    private List<String> operators = new ArrayList<>();    // 운영사 이름 원문 (철도망의 표기 그대로)
    private List<String> categories = new ArrayList<>();   // JR / SUBWAY / PRIVATE / THIRD_SECTOR / TRAM / GUIDED / CABLE. 비우면 가리지 않는다
    private List<String> lines = new ArrayList<>();        // 이 글자가 들어간 노선에만 적용. 비우면 전 노선
    private List<String> exceptLines = new ArrayList<>();  // 이 글자가 들어간 노선은 제외
    private String boxes;               // 적용 지역 "남위도,서경도,북위도,동경도;…". 비우면 어디서나
    private String bands;               // "3:150,6:190,10:200" (몇 km 까지 얼마). 마지막 구간보다 먼 거리는 추정으로 넘어간다
    private String stationAdds;         // 가산운임 "역이름:금액,…"
    private boolean childRoundDown;     // 아동 요금 10엔 미만 버림 (JR)
    private String effectiveFrom;       // 시행일
    private String reference;           // 확인한 자료의 주소
    private String verifiedAt;          // 확인한 날 "2026-10-11"
    private String note;
    private String source;              // (응답 전용) bundled / manual
    private Boolean enabled;            // 비우면 사용
}
