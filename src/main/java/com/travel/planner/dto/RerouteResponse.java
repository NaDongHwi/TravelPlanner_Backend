package com.travel.planner.dto;

import lombok.Getter;
import lombok.Setter;
import java.util.List;

@Getter
@Setter
public class RerouteResponse {
    private boolean success;
    private boolean requireConfirmation; // true면 프론트엔드에서 "마지막 일정을 포기하시겠습니까?" 팝업 띄움
    private String message;              // 경고 및 안내 메시지

    private List<String> droppedPlaces;  // 시간 부족으로 인해 드랍된 기존 장소들
    private List<AiRouteResponse.TimelineItem> updatedTimeline; // 새롭게 정렬된 타임라인
}