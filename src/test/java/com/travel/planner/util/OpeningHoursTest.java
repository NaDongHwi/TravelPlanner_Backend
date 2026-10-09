package com.travel.planner.util;

import com.travel.planner.entity.Place;
import org.junit.jupiter.api.Test;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OpeningHoursTest {

    private static final String WEEK =
            "월요일: 오전 11:00 ~ 오후 3:00, 오후 5:00 ~ 10:00 | 화요일: 휴무일 | 수요일: 24시간 영업"
                    + " | 목요일: 오후 6:00 ~ 오전 2:00 | 금요일: 오전 10:00 ~ 오전 12:00"
                    + " | 토요일: 오전 9:00 ~ 오후 5:00 | 일요일: 오전 9:00 ~ 오후 5:00";

    @Test
    void splitHoursKeepBothRanges() {
        List<int[]> iv = OpeningHours.fromWeekdayText(WEEK, DayOfWeek.MONDAY);
        assertEquals(2, iv.size());
        assertEquals(11 * 60, iv.get(0)[0]);
        assertEquals(15 * 60, iv.get(0)[1]);
        // "오후 5:00 ~ 10:00" 의 10:00 은 오후 10시여야 한다 (이전 구현은 오전 10시로 읽었다)
        assertEquals(17 * 60, iv.get(1)[0]);
        assertEquals(22 * 60, iv.get(1)[1]);
    }

    @Test
    void closedDayIsEmptyNotUnknown() {
        List<int[]> iv = OpeningHours.fromWeekdayText(WEEK, DayOfWeek.TUESDAY);
        assertNotNull(iv);
        assertTrue(iv.isEmpty());
    }

    @Test
    void twentyFourHoursAppliesOnlyToThatDay() {
        assertEquals(1440, OpeningHours.fromWeekdayText(WEEK, DayOfWeek.WEDNESDAY).get(0)[1]);
        // 수요일만 24시간이어도 토요일은 9~17시
        List<int[]> sat = OpeningHours.fromWeekdayText(WEEK, DayOfWeek.SATURDAY);
        assertEquals(9 * 60, sat.get(0)[0]);
        assertEquals(17 * 60, sat.get(0)[1]);
    }

    @Test
    void closingAfterMidnightExtendsPast1440() {
        List<int[]> thu = OpeningHours.fromWeekdayText(WEEK, DayOfWeek.THURSDAY);
        assertEquals(18 * 60, thu.get(0)[0]);
        assertEquals(26 * 60, thu.get(0)[1]);
        // "오전 12:00" 마감 = 자정
        List<int[]> fri = OpeningHours.fromWeekdayText(WEEK, DayOfWeek.FRIDAY);
        // 금요일 새벽에는 목요일 영업이 02:00 까지 이어진다
        assertEquals(0, fri.get(0)[0]);
        assertEquals(2 * 60, fri.get(0)[1]);
        assertEquals(10 * 60, fri.get(1)[0]);
        assertEquals(24 * 60, fri.get(1)[1]);
    }

    @Test
    void englishAndNarrowSpaces() {
        String en = "Monday: 5:00 – 10:00 PM | Tuesday: Closed | Wednesday: Open 24 hours";
        List<int[]> mon = OpeningHours.fromWeekdayText(en, DayOfWeek.MONDAY);
        assertEquals(17 * 60, mon.get(0)[0]);
        assertEquals(22 * 60, mon.get(0)[1]);
        assertTrue(OpeningHours.fromWeekdayText(en, DayOfWeek.TUESDAY).isEmpty());
        assertNull(OpeningHours.fromWeekdayText(en, DayOfWeek.SUNDAY));   // 그 요일 정보가 없으면 "모름"
    }

    @Test
    void unknownTextReturnsNull() {
        assertNull(OpeningHours.fromWeekdayText("영업시간 정보 없음", DayOfWeek.MONDAY));
        assertNull(OpeningHours.fromWeekdayText(OpeningHours.CHECKED_NO_DATA, DayOfWeek.MONDAY));
        assertNull(OpeningHours.fromWeekdayText(null, DayOfWeek.MONDAY));
    }

    @Test
    void structuredPeriodsTakePriority() {
        // 월(1) 18:00 ~ 화(2) 02:00, 화(2) 11:00 ~ 15:00
        String json = "[{\"open\":{\"day\":1,\"hour\":18,\"minute\":0},\"close\":{\"day\":2,\"hour\":2,\"minute\":0}},"
                + "{\"open\":{\"day\":2,\"hour\":11,\"minute\":0},\"close\":{\"day\":2,\"hour\":15,\"minute\":0}}]";
        Place p = new Place();
        p.setOpeningPeriods(json);
        p.setOpeningHours(WEEK);

        List<int[]> mon = OpeningHours.intervalsOn(p, LocalDate.of(2026, 7, 6));   // 월요일
        assertEquals(1, mon.size());
        assertEquals(18 * 60, mon.get(0)[0]);
        assertEquals(26 * 60, mon.get(0)[1]);

        List<int[]> tue = OpeningHours.intervalsOn(p, LocalDate.of(2026, 7, 7));
        assertEquals(2, tue.size());
        assertEquals(0, tue.get(0)[0]);
        assertEquals(2 * 60, tue.get(0)[1]);
        assertEquals(11 * 60, tue.get(1)[0]);

        // periods 에 없는 요일 = 휴무
        assertTrue(OpeningHours.intervalsOn(p, LocalDate.of(2026, 7, 8)).isEmpty());
    }

    @Test
    void alwaysOpenPeriod() {
        Place p = new Place();
        p.setOpeningPeriods("[{\"open\":{\"day\":0,\"hour\":0,\"minute\":0}}]");
        List<int[]> iv = OpeningHours.intervalsOn(p, LocalDate.of(2026, 7, 9));
        assertEquals(0, iv.get(0)[0]);
        assertTrue(iv.get(0)[1] >= 1440);
    }

    @Test
    void flightTimeParsing() {
        assertEquals(14 * 60 + 30, TimeUtil.parseFlightTime("14:30"));
        assertEquals(14 * 60 + 30, TimeUtil.parseFlightTime("오후 2:30"));
        assertEquals(10 * 60, TimeUtil.parseFlightTime("오전"));
        assertEquals(19 * 60, TimeUtil.parseFlightTime("저녁"));
        assertNull(TimeUtil.parseFlightTime("미정"));
        assertNull(TimeUtil.parseFlightTime(null));
        assertEquals("00:30", TimeUtil.format(1470));
    }
}
