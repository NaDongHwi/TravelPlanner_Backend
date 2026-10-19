package com.travel.planner.dto;

import com.travel.planner.util.TripProfile;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * 마이페이지 "장기 일정" 설정. 15일 이상 여행에만 적용된다.
 * 보낼 때 비워 둔 칸(null)은 바꾸지 않는다. 기본값으로 되돌리려면 DELETE 를 쓴다.
 */
@Getter
@Setter
@NoArgsConstructor
@Schema(description = "장기 일정(15일 이상) 여유일 설정")
public class LongTripSettingsDto {

    @Schema(description = "살아보기(도시 고정) 여유일 간격(일). 0 이면 여유일 없음, 2~30", example = "5")
    private Integer liveRestEveryDays;

    @Schema(description = "살아보기 여유일의 관광 시간 상한(시간). 0 이면 관광지 없이 식사·카페·산책만, 0~10 (0.5 단위)", example = "0")
    private Double liveRestSightHours;

    @Schema(description = "투어(도시 고정 해제) 가벼운 날 간격(일). 0 이면 없음, 2~30", example = "7")
    private Integer tourLightEveryDays;

    @Schema(description = "투어 가벼운 날의 관광 시간 상한(시간). 0~10 (0.5 단위)", example = "3")
    private Double tourLightSightHours;

    @Schema(description = "응답에만: 기본값으로 쓰고 있는 칸이면 true", accessMode = Schema.AccessMode.READ_ONLY)
    private Boolean usingDefaults;

    public static LongTripSettingsDto of(TripProfile.PaceSettings pace, boolean usingDefaults) {
        LongTripSettingsDto d = new LongTripSettingsDto();
        d.liveRestEveryDays = pace.liveRestEvery;
        d.liveRestSightHours = pace.liveRestSightMin / 60.0;
        d.tourLightEveryDays = pace.tourLightEvery;
        d.tourLightSightHours = pace.tourLightSightMin / 60.0;
        d.usingDefaults = usingDefaults;
        return d;
    }
}
