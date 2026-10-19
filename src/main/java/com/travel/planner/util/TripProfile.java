package com.travel.planner.util;

/**
 * 여행 길이와 장기 여행의 방식.
 *
 *  - 단기 1~7일 / 중기 8~14일 / 장기 15일 이상 (일수로 자동)
 *  - 장기일 때만 방식이 둘로 나뉜다. "근교 제외"(선택한 도시 안에서만)를 켰으면 한 도시에 머무는 <b>살아보기</b>,
 *    껐으면 여러 도시를 도는 <b>투어</b>.
 *     · 살아보기: 하루 관광지 2~3곳, 카페·동네 산책을 늘리고, 5일마다 여유일. 일과를 늦게 시작해 일찍 끝낸다.
 *     · 투어: 지금처럼 빽빽해도 되고, 일정이 길면 근처 도시를 자동으로 더한다. 7일마다 가벼운 날.
 *    여유일 간격과 그날의 관광 시간은 마이페이지 설정(PaceSettings)으로 바꿀 수 있다.
 *  - 중기 중 10일 이상이면 3분의 2 지점에 가벼운 날을 하루 넣는다 (관광 3시간, 설정과 무관).
 */
public final class TripProfile {

    private TripProfile() {}

    public enum Length {
        SHORT("단기"), MEDIUM("중기"), LONG("장기");
        public final String label;
        Length(String label) { this.label = label; }
    }

    public enum Style {
        LIVE("살아보기"), TOUR("투어");
        public final String label;
        Style(String label) { this.label = label; }
    }

    public static final int SHORT_MAX_DAYS = 7;
    public static final int MEDIUM_MAX_DAYS = 14;

    /** 살아보기에서 여유일을 두는 간격(일), 투어에서 가벼운 날을 두는 간격(일) */
    public static final int LIVE_REST_EVERY = 5;
    public static final int TOUR_LIGHT_EVERY = 7;
    /** 투어에서 도시 하나에 머무는 기준 일수 (이보다 길면 근처 도시를 더한다) */
    public static final int TOUR_DAYS_PER_CITY = 4;

    public static Length lengthOf(int days) {
        if (days <= SHORT_MAX_DAYS) return Length.SHORT;
        if (days <= MEDIUM_MAX_DAYS) return Length.MEDIUM;
        return Length.LONG;
    }

    /** 장기가 아니면 null */
    public static Style styleOf(int days, boolean stayInSelectedCities) {
        if (lengthOf(days) != Length.LONG) return null;
        return stayInSelectedCities ? Style.LIVE : Style.TOUR;
    }

    /** 하루의 결: 보통 / 살아보기 보통날 / 살아보기 여유일 / 투어(또는 10일 이상 중기)의 가벼운 날 */
    public enum DayKind { NORMAL, LIVE_DAY, LIVE_REST, TOUR_LIGHT }

    /** 중기 여행이 이 일수 이상이면 중간(3분의 2 지점)에 가벼운 날을 하루 넣는다 */
    public static final int MEDIUM_LIGHT_FROM_DAYS = 10;

    /** 중기 여행의 가벼운 날: 3분의 2 지점 (10일 → 7일째, 14일 → 9일째). 10일 미만이거나 중기가 아니면 0 */
    public static int mediumLightDay(int totalDays) {
        if (lengthOf(totalDays) != Length.MEDIUM || totalDays < MEDIUM_LIGHT_FROM_DAYS) return 0;
        return (int) Math.round(totalDays * 2 / 3.0);
    }

    /**
     * 장기 여행의 여유일 설정 (마이페이지에서 사용자가 바꾼다. 비워 두면 기본값).
     *  - 간격: 며칠마다 여유일(가벼운 날)을 둘지. 0 이면 두지 않는다.
     *  - 관광 시간: 그날 관광지(식당·카페 제외) 체류 시간의 상한(분). 0 이면 관광지 없이 식사·카페·산책만.
     */
    public static final class PaceSettings {
        public static final int DEFAULT_LIVE_REST_EVERY = LIVE_REST_EVERY;
        public static final int DEFAULT_LIVE_REST_SIGHT_MIN = 0;
        public static final int DEFAULT_TOUR_LIGHT_EVERY = TOUR_LIGHT_EVERY;
        public static final int DEFAULT_TOUR_LIGHT_SIGHT_MIN = 180;

        /** 간격으로 쓸 수 있는 값: 0(없음) 또는 2~30일 */
        public static final int MIN_EVERY = 2;
        public static final int MAX_EVERY = 30;
        /** 관광 시간 상한으로 쓸 수 있는 값: 0~10시간 */
        public static final int MAX_SIGHT_MIN = 600;

        public final int liveRestEvery;
        public final int liveRestSightMin;
        public final int tourLightEvery;
        public final int tourLightSightMin;

        public PaceSettings(Integer liveRestEvery, Integer liveRestSightMin, Integer tourLightEvery, Integer tourLightSightMin) {
            this.liveRestEvery = liveRestEvery == null ? DEFAULT_LIVE_REST_EVERY : liveRestEvery;
            this.liveRestSightMin = liveRestSightMin == null ? DEFAULT_LIVE_REST_SIGHT_MIN : liveRestSightMin;
            this.tourLightEvery = tourLightEvery == null ? DEFAULT_TOUR_LIGHT_EVERY : tourLightEvery;
            this.tourLightSightMin = tourLightSightMin == null ? DEFAULT_TOUR_LIGHT_SIGHT_MIN : tourLightSightMin;
        }

        public static final PaceSettings DEFAULT = new PaceSettings(null, null, null, null);

        public static boolean validEvery(int days) {
            return days == 0 || (days >= MIN_EVERY && days <= MAX_EVERY);
        }

        public static boolean validSightMinutes(int minutes) {
            return minutes >= 0 && minutes <= MAX_SIGHT_MIN;
        }
    }

    /** 기본 설정으로 그날의 결 */
    public static DayKind dayKind(Style style, int dayNumber, int totalDays, boolean hasFixedSchedule) {
        return dayKind(style, dayNumber, totalDays, hasFixedSchedule, PaceSettings.DEFAULT);
    }

    /**
     * 장기 여행의 그날 결. 첫날·마지막 날(입출국)과 고정 일정이 있는 날은 여유일로 두지 않는다.
     * 살아보기는 보통날도 느슨하게(LIVE_DAY) 짜고, liveRestEvery 일째마다 여유일. 투어는 tourLightEvery 일째마다 가벼운 날.
     * (중기의 가벼운 날은 고정 일정이 있으면 다음 날로 미뤄야 해서 PlanService 가 따로 정한다)
     */
    public static DayKind dayKind(Style style, int dayNumber, int totalDays, boolean hasFixedSchedule, PaceSettings pace) {
        if (style == null) return DayKind.NORMAL;
        if (pace == null) pace = PaceSettings.DEFAULT;
        boolean edge = dayNumber == 1 || dayNumber == totalDays;
        if (style == Style.LIVE) {
            if (!edge && !hasFixedSchedule && pace.liveRestEvery > 0 && dayNumber % pace.liveRestEvery == 0) return DayKind.LIVE_REST;
            return DayKind.LIVE_DAY;
        }
        if (!edge && !hasFixedSchedule && pace.tourLightEvery > 0 && dayNumber % pace.tourLightEvery == 0) return DayKind.TOUR_LIGHT;
        return DayKind.NORMAL;
    }
}
