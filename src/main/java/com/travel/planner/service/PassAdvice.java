package com.travel.planner.service;

import com.travel.planner.dto.AiRouteResponse;
import com.travel.planner.dto.PassSuggestionDto;
import com.travel.planner.dto.TransitLegDto;
import com.travel.planner.entity.TransportPass;
import com.travel.planner.transit.PassAdvisor;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * 일정 응답에 교통 패스 추천을 붙인다.
 * 구간별 교통 정보에 담아 둔 "요금을 낸 단위"를 여행 전체에서 모아 {@link PassAdvisor} 로 넘기고, 결과를 응답 형식으로 바꾼다.
 */
public final class PassAdvice {

    private PassAdvice() {}

    /**
     * @param passes   추천 대상 패스
     * @param rows     패스 키 → 안내용 정보(조건·주소·확인일). 없으면 null 을 돌려주는 함수
     * @param lineName 노선 이름 원문 → 화면 표기
     */
    public static void apply(AiRouteResponse response, List<PassAdvisor.Pass> passes, Function<String, TransportPass> rows,
                             Function<String, String> lineName) {
        if (response == null || response.getTimeline() == null) return;
        response.setPasses(new ArrayList<>());
        response.setTransitFareWithPassesYen(null);
        if (passes == null || passes.isEmpty()) return;

        Map<Integer, LocalDate> dates = new HashMap<>();
        if (response.getDays() != null) {
            for (AiRouteResponse.DaySummary day : response.getDays()) {
                LocalDate date = parseDate(day.getDate());
                if (date != null) dates.put(day.getDay(), date);
            }
        }
        List<PassAdvisor.Ride> rides = collectRides(response.getTimeline(), dates);
        List<PassAdvisor.Suggestion> suggestions = PassAdvisor.advise(passes, rides);
        if (suggestions.isEmpty()) return;

        int saved = 0;
        List<PassSuggestionDto> list = new ArrayList<>();
        for (PassAdvisor.Suggestion s : suggestions) {
            list.add(toDto(s, rows == null ? null : rows.apply(s.pass.key), dates, lineName));
            saved += s.savingYen;
        }
        response.setPasses(list);
        if (response.getTransitFareYen() != null) {
            response.setTransitFareWithPassesYen(Math.max(0, response.getTransitFareYen() - saved));
        }
        if (response.getReason() != null) {
            PassSuggestionDto top = list.get(0);
            response.setReason(response.getReason() + " 추천 교통 패스(" + top.getName() + (list.size() > 1 ? " 외 " + (list.size() - 1) + "종" : "")
                    + ")를 쓰면 약 " + TransitTotals.yen(saved) + "을 아낄 수 있습니다(추정).");
        }
    }

    /** 타임라인의 구간들에서 요금을 낸 단위를 모으고, 일차·날짜·타는 시각을 채운다 */
    static List<PassAdvisor.Ride> collectRides(List<AiRouteResponse.TimelineItem> timeline, Map<Integer, LocalDate> dates) {
        List<PassAdvisor.Ride> rides = new ArrayList<>();
        for (AiRouteResponse.TimelineItem item : timeline) {
            TransitLegDto leg = item.getTransit();
            if (leg == null || leg.getFareRides() == null || leg.getFareRides().isEmpty()) continue;
            // 이 줄의 시각은 도착 시각이다. 이동 시간을 빼면 출발(타는) 시각이 된다
            int arrive = parseMinute(item.getTime());
            int depart = arrive < 0 ? 12 * 60 : Math.max(0, arrive - Math.max(0, leg.getMinutes()));
            for (PassAdvisor.Ride source : leg.getFareRides()) {
                PassAdvisor.Ride ride = new PassAdvisor.Ride();
                ride.day = item.getDay();
                ride.date = dates.get(item.getDay());
                ride.minute = depart;
                ride.fareYen = source.fareYen;
                ride.estimated = source.estimated;
                ride.legs = source.legs;
                rides.add(ride);
            }
        }
        return rides;
    }

    static PassSuggestionDto toDto(PassAdvisor.Suggestion s, TransportPass row, Map<Integer, LocalDate> dates, Function<String, String> lineName) {
        PassAdvisor.Pass pass = s.pass;
        PassSuggestionDto dto = new PassSuggestionDto();
        dto.setKey(pass.key);
        dto.setName(pass.name);
        dto.setCity(pass.city);
        dto.setPriceYen(pass.priceYen);
        dto.setChildPriceYen(pass.childPriceYen);
        dto.setValidity(validity(pass));
        dto.setDays(s.days);
        int first = s.days.get(0);
        int last = s.days.get(s.days.size() - 1);
        if (dates.get(first) != null) dto.setStartDate(dates.get(first).toString());
        if (dates.get(last) != null) dto.setEndDate(dates.get(last).toString());
        if (s.startMinute >= 0) dto.setStartTime(String.format("%02d:%02d", s.startMinute / 60, s.startMinute % 60));
        dto.setCoveredFareYen(s.coveredFareYen);
        dto.setSavingYen(s.savingYen);
        dto.setRideCount(s.rideCount);
        dto.setEstimated(s.estimated);
        List<String> lines = new ArrayList<>();
        for (String line : PassAdvisor.linesOf(s, 5)) {
            String shown = lineName == null ? line : lineName.apply(line);
            lines.add(shown == null || shown.isBlank() ? line : shown);
        }
        dto.setLines(lines);
        if (row != null) {
            dto.setConditions(row.getConditions());
            dto.setNote(row.getNote());
            dto.setApproximateCoverage(Boolean.TRUE.equals(row.getApproximate()));
            dto.setUrl(row.getUrl());
            dto.setVerifiedAt(row.getVerifiedAt() == null ? null : row.getVerifiedAt().toString());
        }
        dto.setSummary(summary(dto, s));
        return dto;
    }

    static String validity(PassAdvisor.Pass pass) {
        if (pass.hours > 0) return pass.hours + "시간";
        if (pass.days == 1) return "1일";
        return (pass.consecutive ? "연속 " : "") + pass.days + "일";
    }

    private static String summary(PassSuggestionDto dto, PassAdvisor.Suggestion s) {
        StringBuilder sb = new StringBuilder();
        int first = s.days.get(0);
        int last = s.days.get(s.days.size() - 1);
        if (dto.getStartTime() != null) {
            // 시간권: 처음 타는 때부터 유효 시간이 흐른다
            sb.append(first).append("일차 ").append(dto.getStartTime()).append("부터 ").append(s.pass.hours).append("시간 동안 쓰면 ");
        } else {
            sb.append(first == last ? first + "일차" : first + "~" + last + "일차").append("에 쓰면 ");
        }
        sb.append(TransitTotals.yen(dto.getCoveredFareYen())).append("어치(").append(dto.getRideCount()).append("회)를 ")
                .append(TransitTotals.yen(dto.getPriceYen())).append("에 타서 약 ").append(TransitTotals.yen(dto.getSavingYen())).append("을 아낍니다.");
        if (dto.isEstimated()) sb.append(" 요금 일부가 추정값이라 실제 차이는 달라질 수 있습니다.");
        if (dto.isApproximateCoverage()) sb.append(" 이용 범위는 구입 전에 확인해 주세요.");
        return sb.toString();
    }

    private static LocalDate parseDate(String text) {
        try {
            return text == null ? null : LocalDate.parse(text);
        } catch (Exception e) {
            return null;
        }
    }

    /** "HH:mm" → 분. 읽을 수 없으면 -1 */
    private static int parseMinute(String time) {
        if (time == null) return -1;
        String[] parts = time.split(":");
        if (parts.length < 2) return -1;
        try {
            return Integer.parseInt(parts[0].trim()) * 60 + Integer.parseInt(parts[1].trim());
        } catch (NumberFormatException e) {
            return -1;
        }
    }
}
