package com.travel.planner.util;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.travel.planner.dto.PlanRequest;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 고정 일정 목록 ↔ JSON 문자열.
 * Plan 에 저장해 두었다가 재탐색(reroute) 때 원래 조건을 복원하는 데 쓴다.
 * 날짜·시각은 문자열로 직접 넣고 빼므로 Jackson 의 java.time 모듈 설정에 의존하지 않는다.
 */
public final class FixedScheduleCodec {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private FixedScheduleCodec() {}

    public static String toJson(List<PlanRequest.FixedScheduleInput> schedules) {
        if (schedules == null || schedules.isEmpty()) return null;
        List<Map<String, Object>> rows = new ArrayList<>();
        for (PlanRequest.FixedScheduleInput f : schedules) {
            if (f == null || f.getStartTime() == null || f.getEndTime() == null) continue;
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("name", f.getName());
            row.put("startTime", f.getStartTime().toString());
            row.put("endTime", f.getEndTime().toString());
            if (f.getDate() != null) row.put("date", f.getDate().toString());
            if (f.getDayNumber() != null) row.put("dayNumber", f.getDayNumber());
            if (f.getLatitude() != null) row.put("latitude", f.getLatitude());
            if (f.getLongitude() != null) row.put("longitude", f.getLongitude());
            rows.add(row);
        }
        try {
            return rows.isEmpty() ? null : MAPPER.writeValueAsString(rows);
        } catch (Exception e) {
            return null;
        }
    }

    public static List<PlanRequest.FixedScheduleInput> fromJson(String json) {
        List<PlanRequest.FixedScheduleInput> result = new ArrayList<>();
        if (json == null || json.isBlank()) return result;
        try {
            List<Map<String, Object>> rows = MAPPER.readValue(json, new TypeReference<List<Map<String, Object>>>() {});
            for (Map<String, Object> row : rows) {
                PlanRequest.FixedScheduleInput f = new PlanRequest.FixedScheduleInput();
                f.setName((String) row.get("name"));
                f.setStartTime(LocalTime.parse((String) row.get("startTime")));
                f.setEndTime(LocalTime.parse((String) row.get("endTime")));
                if (row.get("date") != null) f.setDate(LocalDate.parse((String) row.get("date")));
                if (row.get("dayNumber") != null) f.setDayNumber(((Number) row.get("dayNumber")).intValue());
                if (row.get("latitude") != null) f.setLatitude(((Number) row.get("latitude")).doubleValue());
                if (row.get("longitude") != null) f.setLongitude(((Number) row.get("longitude")).doubleValue());
                result.add(f);
            }
        } catch (Exception e) {
            // 형식이 깨진 값은 고정 일정 없음으로 처리
            return new ArrayList<>();
        }
        return result;
    }
}
