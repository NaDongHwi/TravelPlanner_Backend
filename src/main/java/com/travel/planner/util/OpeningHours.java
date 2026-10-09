package com.travel.planner.util;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.travel.planner.entity.Place;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 영업시간 해석기.
 *
 * 반환 형식은 "그 날짜의 영업 구간 목록"이다. 각 구간은 [open, close] (자정 기준 분).
 *  - close 는 1440 을 넘을 수 있다 (새벽 2시 마감 = 1560).
 *  - null      : 정보 없음 → 호출하는 쪽이 유형별 기본값을 쓴다.
 *  - 빈 리스트 : 그 날은 휴무.
 *
 * 1순위는 구글 regularOpeningHours.periods (요일·시·분 구조)이고,
 * 2순위로 기존 DB 에 쌓여 있는 weekdayDescriptions 문자열을 해석한다.
 * 문자열 해석은 분할 영업("11:00~15:00, 17:00~22:00"), 자정 넘김, 한쪽에만 붙은 오전/오후,
 * 요일별 "24시간 영업"/"휴무"를 모두 처리한다.
 */
public final class OpeningHours {

    /** 구글에 조회했지만 영업시간이 없던 장소에 남기는 표식 (매번 재조회되는 것을 막는다). */
    public static final String CHECKED_NO_DATA = "확인 불가";
    public static final String LEGACY_DEFAULT = "영업시간 정보 없음";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String[][] DAY_NAMES = {
            {"월요일", "monday", "月曜日"},
            {"화요일", "tuesday", "火曜日"},
            {"수요일", "wednesday", "水曜日"},
            {"목요일", "thursday", "木曜日"},
            {"금요일", "friday", "金曜日"},
            {"토요일", "saturday", "土曜日"},
            {"일요일", "sunday", "日曜日"}
    };

    private static final Pattern RANGE_SEPARATOR = Pattern.compile("\\s*[~–—〜～-]\\s*");
    private static final Pattern CLOCK = Pattern.compile("(\\d{1,2})(?:\\s*[:시時]\\s*(\\d{1,2}))?");

    private OpeningHours() {}

    public static boolean isUnknownText(String text) {
        return text == null || text.isBlank() || text.contains("없음") || text.equals(CHECKED_NO_DATA);
    }

    /** 해당 날짜의 영업 구간. null=정보 없음, 빈 리스트=휴무. */
    public static List<int[]> intervalsOn(Place place, LocalDate date) {
        DayOfWeek dow = date.getDayOfWeek();
        List<int[]> fromPeriods = fromPeriodsJson(place.getOpeningPeriods(), dow);
        if (fromPeriods != null) return fromPeriods;
        return fromWeekdayText(place.getOpeningHours(), dow);
    }

    // ------------------------------------------------------------------
    // 1) 구조화 데이터 (regularOpeningHours.periods JSON)
    // ------------------------------------------------------------------
    public static List<int[]> fromPeriodsJson(String json, DayOfWeek dow) {
        if (json == null || json.isBlank()) return null;
        try {
            JsonNode periods = MAPPER.readTree(json);
            if (!periods.isArray() || periods.isEmpty()) return null;

            int today = dow.getValue() % 7;          // 구글: 0=일요일 … 6=토요일
            int yesterday = (today + 6) % 7;
            List<int[]> result = new ArrayList<>();

            for (JsonNode period : periods) {
                JsonNode open = period.path("open");
                JsonNode close = period.path("close");
                if (open.isMissingNode()) continue;

                // close 가 없는 단일 period = 연중무휴 24시간
                if (close.isMissingNode() || close.isNull()) {
                    result.add(new int[]{0, 2880});
                    continue;
                }

                int openDay = open.path("day").asInt(0);
                int openMin = open.path("hour").asInt(0) * 60 + open.path("minute").asInt(0);
                int closeDay = close.path("day").asInt(openDay);
                int closeMin = close.path("hour").asInt(0) * 60 + close.path("minute").asInt(0);

                int span = ((closeDay - openDay + 7) % 7) * 1440 + closeMin - openMin;
                if (span <= 0) span += 7 * 1440;
                span = Math.min(span, 2880);

                if (openDay == today) {
                    result.add(new int[]{openMin, openMin + span});
                } else if (openDay == yesterday && openMin + span > 1440) {
                    // 전날 밤에 열어 오늘 새벽까지 이어지는 구간
                    result.add(new int[]{0, openMin + span - 1440});
                }
            }
            return merge(result);
        } catch (Exception e) {
            return null;
        }
    }

    // ------------------------------------------------------------------
    // 2) 문자열 (weekdayDescriptions 를 " | " 로 이어 붙인 기존 저장 형식)
    // ------------------------------------------------------------------
    public static List<int[]> fromWeekdayText(String text, DayOfWeek dow) {
        if (isUnknownText(text)) return null;

        String[] segments = normalize(text).split("\\|");
        int todayIdx = dow.getValue() - 1;
        int yesterdayIdx = (todayIdx + 6) % 7;

        List<int[]> today = parseSegment(findSegment(segments, todayIdx));
        if (today == null) return null;

        List<int[]> result = new ArrayList<>(today);
        List<int[]> yesterday = parseSegment(findSegment(segments, yesterdayIdx));
        if (yesterday != null) {
            for (int[] iv : yesterday) {
                if (iv[1] > 1440) result.add(new int[]{0, iv[1] - 1440});
            }
        }
        return merge(result);
    }

    /** 화면 표시용: 그 요일의 영업시간 한 줄. */
    public static String describe(Place place, LocalDate date) {
        String text = place.getOpeningHours();
        if (isUnknownText(text)) return LEGACY_DEFAULT;
        String segment = findSegment(text.split("\\|"), date.getDayOfWeek().getValue() - 1);
        return segment != null ? segment.trim() : LEGACY_DEFAULT;
    }

    private static String normalize(String text) {
        // 구글은 시각과 오전/오후 사이에 좁은 공백(U+202F, U+2009)이나 NBSP 를 쓴다.
        return text.replace(' ', ' ').replace(' ', ' ').replace(' ', ' ').replace('：', ':');
    }

    private static String findSegment(String[] segments, int dayIdx) {
        for (String segment : segments) {
            String lower = segment.trim().toLowerCase();
            for (String name : DAY_NAMES[dayIdx]) {
                if (lower.startsWith(name)) return segment;
            }
        }
        return null;
    }

    private static List<int[]> parseSegment(String segment) {
        if (segment == null) return null;
        String s = normalize(segment);
        int colon = s.indexOf(':');
        if (colon < 0) return null;
        String body = s.substring(colon + 1).trim();
        String lower = body.toLowerCase();

        if (lower.contains("휴무") || lower.contains("closed") || lower.contains("定休") || lower.contains("休業")) {
            return new ArrayList<>();
        }
        if (lower.replace(" ", "").contains("24시간") || lower.contains("24 hours") || lower.replace(" ", "").contains("24時間")) {
            List<int[]> all = new ArrayList<>();
            all.add(new int[]{0, 1440});
            return all;
        }

        List<int[]> result = new ArrayList<>();
        for (String range : body.split("[,、]")) {
            String[] ends = RANGE_SEPARATOR.split(range.trim());
            if (ends.length != 2) return null;

            Boolean openPm = meridiem(ends[0]);
            Boolean closePm = meridiem(ends[1]);
            // "오후 5:00~10:00" / "5:00 – 10:00 PM" 처럼 한쪽에만 붙으면 같은 오전/오후로 본다.
            if (openPm == null && closePm != null) openPm = closePm;
            if (closePm == null && openPm != null) closePm = openPm;

            Integer open = clock(ends[0], openPm);
            Integer close = clock(ends[1], closePm);
            if (open == null || close == null) return null;
            if (close <= open) close += 1440;   // 자정을 넘기는 마감
            result.add(new int[]{open, close});
        }
        return result.isEmpty() ? null : result;
    }

    private static Boolean meridiem(String token) {
        String t = token.toUpperCase();
        if (t.contains("오후") || t.contains("PM") || t.contains("午後")) return Boolean.TRUE;
        if (t.contains("오전") || t.contains("AM") || t.contains("午前")) return Boolean.FALSE;
        return null;
    }

    private static Integer clock(String token, Boolean pm) {
        Matcher m = CLOCK.matcher(token);
        if (!m.find()) return null;
        int hour = Integer.parseInt(m.group(1));
        int minute = m.group(2) != null ? Integer.parseInt(m.group(2)) : 0;
        if (hour > 24 || minute > 59) return null;
        if (pm != null) {
            if (pm && hour < 12) hour += 12;
            if (!pm && hour == 12) hour = 0;
        }
        return hour * 60 + minute;
    }

    private static List<int[]> merge(List<int[]> intervals) {
        if (intervals.size() <= 1) return intervals;
        intervals.sort(Comparator.comparingInt(iv -> iv[0]));
        List<int[]> merged = new ArrayList<>();
        for (int[] iv : intervals) {
            if (!merged.isEmpty() && iv[0] <= merged.get(merged.size() - 1)[1]) {
                int[] last = merged.get(merged.size() - 1);
                last[1] = Math.max(last[1], iv[1]);
            } else {
                merged.add(new int[]{iv[0], iv[1]});
            }
        }
        return merged;
    }
}
