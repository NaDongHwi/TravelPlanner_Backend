package com.travel.planner.dto;

import lombok.Getter;
import lombok.Setter;

import java.util.ArrayList;
import java.util.List;

@Getter
@Setter
public class ReplaceResponse {
    private boolean success;     // 한 곳이라도 바뀌었는가
    private boolean saved;       // DB 에 저장했는가 (preview=true 이거나 바뀐 곳이 없으면 false)
    private String message;      // 화면에 그대로 보여 줄 수 있는 요약 문장

    // 고른 줄마다의 결과 (바뀌었는지, 무엇으로 바뀌었는지, 못 바꿨으면 이유)
    private List<Change> changes = new ArrayList<>();

    // 바뀐 날의 타임라인 전체 (여러 날이면 일차 순서대로 이어서). 바뀌지 않은 줄은 장소·시각이 그대로다.
    private List<AiRouteResponse.TimelineItem> updatedTimeline = new ArrayList<>();

    // 바뀐 날의 개요
    private List<AiRouteResponse.DaySummary> updatedDaySummaries = new ArrayList<>();

    @Getter
    @Setter
    public static class Change {
        private int dayNumber;
        private int sequence;          // 바꾸기 전 그 날 화면 순번
        private boolean changed;
        private String oldPlaceId;     // 자유 식사·자유 시간이면 null
        private String oldPlaceName;
        private String newPlaceId;     // 못 바꿨으면 null
        private String newPlaceName;
        private String message;        // "바꿨습니다." 또는 못 바꾼 이유
    }
}
