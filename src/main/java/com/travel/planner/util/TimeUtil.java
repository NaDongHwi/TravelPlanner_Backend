package com.travel.planner.util;

import java.time.LocalTime;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 일정 계산용 시간 유틸.
 * 엔진 내부에서는 모든 시각을 "자정 기준 분(int)"으로 다룬다.
 * LocalTime 은 24:00 을 넘으면 00:00 으로 되감겨 isAfter/isBefore 비교가 깨지기 때문이다.
 */
public final class TimeUtil {

    private static final Pattern CLOCK = Pattern.compile("(\\d{1,2})\\s*[:시]\\s*(\\d{1,2})?");

    private TimeUtil() {}

    public static int toMinutes(LocalTime t) {
        return t.getHour() * 60 + t.getMinute();
    }

    /** 분 → "HH:mm". 자정을 넘긴 값(예: 1470)은 다음 날 시각("00:30")으로 표시한다. */
    public static String format(int minutes) {
        int m = ((minutes % 1440) + 1440) % 1440;
        return String.format("%02d:%02d", m / 60, m % 60);
    }

    public static int roundUpTo5(double minutes) {
        return (int) (Math.ceil(minutes / 5.0) * 5);
    }

    /**
     * 입국/출국 항공편 시각 해석.
     * "14:30", "오후 2:30", "14시" 같은 실제 시각을 우선 사용하고,
     * 없으면 "오전/오후/저녁" 구간의 대표 시각으로 대체한다. "미정"·빈 값은 null(제약 없음).
     */
    public static Integer parseFlightTime(String raw) {
        if (raw == null) return null;
        String s = raw.trim();
        if (s.isEmpty() || s.contains("미정")) return null;

        boolean pm = s.contains("오후") || s.contains("저녁") || s.contains("밤") || s.toUpperCase().contains("PM");
        boolean am = s.contains("오전") || s.contains("아침") || s.contains("새벽") || s.toUpperCase().contains("AM");

        Matcher m = CLOCK.matcher(s);
        if (m.find()) {
            int hour = Integer.parseInt(m.group(1));
            int minute = m.group(2) != null ? Integer.parseInt(m.group(2)) : 0;
            if (hour <= 24 && minute < 60) {
                if (pm && hour < 12) hour += 12;
                if (am && hour == 12) hour = 0;
                return hour * 60 + minute;
            }
        }

        if (s.contains("새벽")) return 7 * 60;
        if (s.contains("오전") || s.contains("아침")) return 10 * 60;
        if (s.contains("오후") || s.contains("낮") || s.contains("점심")) return 14 * 60;
        if (s.contains("저녁") || s.contains("밤") || s.contains("야간")) return 19 * 60;
        return null;
    }
}
