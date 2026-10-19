package com.travel.planner.util;

import java.time.LocalDate;
import java.time.MonthDay;
import java.util.ArrayList;
import java.util.List;

/**
 * 날씨 예보 범위(오늘부터 16일) 밖 날짜에 붙이는 "평년 기준" 계절 안내. 일정 점수에는 쓰지 않는다.
 * 지역은 좌표로 대략 나눈다: 홋카이도(북위 41.3 이상), 오키나와(북위 27.5 미만), 나머지 본토.
 */
public final class SeasonNotes {

    private SeasonNotes() {}

    public static final String RAINY = "장마철";
    public static final String TYPHOON = "태풍 시기";
    public static final String SNOW = "폭설 지역";

    /** 그날 그곳의 계절 주의 (평년 기준). 없으면 빈 목록 */
    public static List<String> of(LocalDate date, double lat, double lng) {
        List<String> out = new ArrayList<>();
        if (date == null) return out;
        MonthDay md = MonthDay.from(date);
        boolean hokkaido = lat >= 41.3;
        boolean okinawa = lat < 27.5;
        // 장마: 오키나와 5/10~6/25, 본토 6/5~7/20 (홋카이도는 뚜렷한 장마가 없다)
        if (okinawa && between(md, 5, 10, 6, 25)) out.add(RAINY);
        if (!hokkaido && !okinawa && between(md, 6, 5, 7, 20)) out.add(RAINY);
        // 태풍: 8/1~10/10 (홋카이도는 드물다). 오키나와는 7월부터
        if (!hokkaido && (between(md, 8, 1, 10, 10) || (okinawa && between(md, 7, 1, 7, 31)))) out.add(TYPHOON);
        // 폭설: 12/15~3/15, 홋카이도·도호쿠·니가타·호쿠리쿠·히다 산간(북위 37 이상, 또는 북위 36 이상이면서 동경 138.6 서쪽)
        boolean snowy = hokkaido || lat >= 37.0 || (lat >= 36.0 && lng < 138.6 && lng > 136.0);
        if (snowy && (between(md, 12, 15, 12, 31) || between(md, 1, 1, 3, 15))) out.add(SNOW);
        return out;
    }

    private static boolean between(MonthDay md, int m1, int d1, int m2, int d2) {
        MonthDay a = MonthDay.of(m1, d1);
        MonthDay b = MonthDay.of(m2, d2);
        return !md.isBefore(a) && !md.isAfter(b);
    }

    /** 일자별 개요에 붙이는 한 줄 */
    public static String dayNote(List<String> tags) {
        if (tags == null || tags.isEmpty()) return null;
        return String.join("·", tags) + " (평년 기준, 예보 아님)";
    }

    /** 여행 전체 안내 한 문장 */
    public static String tripWarning(String tag) {
        switch (tag) {
            case RAINY: return "일정 중 장마철(평년 기준)에 걸리는 날이 있습니다. 비 오는 날이 많을 수 있으니 실내 일정을 염두에 두세요.";
            case TYPHOON: return "일정 중 태풍 시기(평년 기준)에 걸리는 날이 있습니다. 출발 전 태풍 예보와 항공·열차 운행을 확인해 주세요.";
            case SNOW: return "일정 중 폭설 지역의 겨울(평년 기준)에 걸리는 날이 있습니다. 눈으로 교통이 늦어지거나 멈출 수 있습니다.";
            default: return null;
        }
    }
}
