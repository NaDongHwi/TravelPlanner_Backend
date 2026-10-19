package com.travel.planner.util;

/**
 * 여행 길이와 장기 여행의 방식.
 *
 *  - 단기 1~7일 / 중기 8~14일 / 장기 15일 이상 (일수로 자동)
 *  - 장기일 때만 방식이 둘로 나뉜다. "근교 제외"(선택한 도시 안에서만)를 켰으면 한 도시에 머무는 <b>살아보기</b>,
 *    껐으면 여러 도시를 도는 <b>투어</b>.
 *     · 살아보기: 하루 관광지 2~3곳, 카페·동네 산책을 늘리고, 5일마다 여유일. 일과를 늦게 시작해 일찍 끝낸다.
 *     · 투어: 지금처럼 빽빽해도 되고, 일정이 길면 근처 도시를 자동으로 더한다. 7일마다 가벼운 날.
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

    /** 하루의 결: 보통 / 살아보기 보통날 / 살아보기 여유일 / 투어 가벼운 날 */
    public enum DayKind { NORMAL, LIVE_DAY, LIVE_REST, TOUR_LIGHT }

    /**
     * 그날의 결. 첫날·마지막 날(입출국)과 고정 일정이 있는 날은 보통으로 둔다.
     * 살아보기는 보통날도 느슨하게(LIVE_DAY) 짜고, 5일째마다 여유일. 투어는 7일째마다 가벼운 날.
     */
    public static DayKind dayKind(Style style, int dayNumber, int totalDays, boolean hasFixedSchedule) {
        if (style == null) return DayKind.NORMAL;
        boolean edge = dayNumber == 1 || dayNumber == totalDays;
        if (style == Style.LIVE) {
            if (!edge && !hasFixedSchedule && dayNumber % LIVE_REST_EVERY == 0) return DayKind.LIVE_REST;
            return DayKind.LIVE_DAY;
        }
        if (!edge && !hasFixedSchedule && dayNumber % TOUR_LIGHT_EVERY == 0) return DayKind.TOUR_LIGHT;
        return DayKind.NORMAL;
    }
}
