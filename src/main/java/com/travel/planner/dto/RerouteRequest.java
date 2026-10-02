package com.travel.planner.dto;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class RerouteRequest {
    private int dayNumber;          // 몇 일차 일정을 수정할 것인지 (예: 2)
    private int insertSequence;     // 몇 번째 순서로 끼워 넣을 것인지 (예: 3번째)

    // 프론트엔드가 구글 맵스에서 검색한 신규 장소 정보
    private String placeId;         // 구글 고유 Place ID
    private String placeName;       // 장소명
    private double latitude;        // 위도
    private double longitude;       // 경도
    private String city;            // 도시명

    // 도미노 붕괴 경고(Soft Warning)를 무시하고 기존 일정을 포기할지 여부
    private boolean forceDrop;
}