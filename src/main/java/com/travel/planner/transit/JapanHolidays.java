package com.travel.planner.transit;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.TemporalAdjusters;

/**
 * 일본의 공휴일. 버스·전철은 평일 / 토요일 / 일요일·공휴일 시간표가 따로 있어, 날짜가 어느 쪽인지 알아야 운행 횟수를 맞게 고를 수 있다.
 * 2020년 이후의 현행 법령 기준이다 (춘분·추분은 천문 계산 근사식, 2099년까지 유효).
 */
public final class JapanHolidays {

    private JapanHolidays() {}

    public static final int WEEKDAY = 0;
    public static final int SATURDAY = 1;
    public static final int HOLIDAY = 2;

    /** 0 = 평일, 1 = 토요일, 2 = 일요일·공휴일 (연말연시 12/29~1/3 은 대부분 휴일 시간표로 운행하므로 2) */
    public static int dayType(LocalDate date) {
        if (date == null) return WEEKDAY;
        if (date.getDayOfWeek() == DayOfWeek.SUNDAY || isHoliday(date) || isYearEnd(date)) return HOLIDAY;
        if (date.getDayOfWeek() == DayOfWeek.SATURDAY) return SATURDAY;
        return WEEKDAY;
    }

    static boolean isYearEnd(LocalDate date) {
        int m = date.getMonthValue();
        int d = date.getDayOfMonth();
        return (m == 12 && d >= 29) || (m == 1 && d <= 3);
    }

    public static boolean isHoliday(LocalDate date) {
        if (isNamedHoliday(date)) return true;
        // 대체 휴일: 일요일과 겹친 공휴일 다음의, 공휴일이 아닌 첫날
        for (LocalDate prev = date.minusDays(1); isNamedHoliday(prev); prev = prev.minusDays(1)) {
            if (prev.getDayOfWeek() == DayOfWeek.SUNDAY) return true;
        }
        // 국민의 휴일: 앞뒤가 모두 공휴일인 평일
        return date.getDayOfWeek() != DayOfWeek.SUNDAY && isNamedHoliday(date.minusDays(1)) && isNamedHoliday(date.plusDays(1));
    }

    /** 법으로 이름이 정해진 공휴일 (대체 휴일·국민의 휴일 제외) */
    static boolean isNamedHoliday(LocalDate date) {
        int y = date.getYear();
        int m = date.getMonthValue();
        int d = date.getDayOfMonth();
        switch (m) {
            case 1: return d == 1 || date.equals(nthMonday(y, 1, 2));
            case 2: return d == 11 || (d == 23 && y >= 2020);
            case 3: return d == vernalEquinox(y);
            case 4: return d == 29;
            case 5: return d == 3 || d == 4 || d == 5;
            case 7: return date.equals(nthMonday(y, 7, 3));
            case 8: return d == 11;
            case 9: return date.equals(nthMonday(y, 9, 3)) || d == autumnalEquinox(y);
            case 10: return date.equals(nthMonday(y, 10, 2));
            case 11: return d == 3 || d == 23;
            case 12: return d == 23 && y <= 2018;
            default: return false;
        }
    }

    private static LocalDate nthMonday(int year, int month, int n) {
        return LocalDate.of(year, month, 1).with(TemporalAdjusters.dayOfWeekInMonth(n, DayOfWeek.MONDAY));
    }

    static int vernalEquinox(int year) {
        return (int) (20.8431 + 0.242194 * (year - 1980) - Math.floor((year - 1980) / 4.0));
    }

    static int autumnalEquinox(int year) {
        return (int) (23.2488 + 0.242194 * (year - 1980) - Math.floor((year - 1980) / 4.0));
    }
}
