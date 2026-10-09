package com.travel.planner.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.Setter;

import java.util.List;

/** 일정 일부 바꾸기 요청: 고른 줄만 다른 장소로 바꾸고 나머지는 그대로 둔다. */
@Getter
@Setter
public class ReplaceRequest {

    @Schema(description = "바꿀 줄 목록 (일차 + 그 날 화면 순번). placeIds 와 함께 써도 된다.")
    private List<Target> targets;

    @Schema(description = "바꿀 장소의 placeId 목록 (타임라인 항목의 placeId). 자유 식사·자유 시간처럼 placeId 가 없는 줄은 targets 로 지정한다.")
    private List<String> placeIds;

    @Schema(description = "원하는 테마 1개 (선택). 주면 그 테마의 장소로만 바꾼다. 식사 자리는 항상 식당으로 바꾼다.", example = "문화")
    private String theme;

    @Schema(description = "true 면 결과만 계산해 보여 주고 저장하지 않는다 (미리보기). 기본 false = 바로 저장")
    private boolean preview;

    @Getter
    @Setter
    public static class Target {
        @Schema(description = "일차 (1부터)", example = "2")
        private int dayNumber;
        @Schema(description = "그 날 화면 순번 (1부터, 숙소 출발이 1번)", example = "3")
        private int sequence;
    }
}
