package com.travel.planner.service;

import com.travel.planner.dto.AiRouteResponse;
import com.travel.planner.dto.TransitLegDto;

import java.util.List;

/** 구간별 교통 정보에서 일자별·여행 전체 교통비를 합산한다. */
public final class TransitTotals {

    private TransitTotals() {}

    /** 그 날의 줄들에서 교통비를 합산해 개요에 넣고, 개요 본문 끝에 한 문장을 붙인다. */
    public static void applyDay(AiRouteResponse.DaySummary summary, List<AiRouteResponse.TimelineItem> timeline) {
        if (summary == null || timeline == null) return;
        int total = 0;
        boolean any = false;
        for (AiRouteResponse.TimelineItem item : timeline) {
            if (item.getDay() != summary.getDay() || item.getTransit() == null || item.getTransit().getFareYen() == null) continue;
            total += item.getTransit().getFareYen();
            any = true;
        }
        if (!any) return;
        summary.setTransitFareYen(total);
        if (total > 0 && summary.getSummary() != null) {
            summary.setSummary(summary.getSummary() + " 교통비는 약 " + yen(total) + "(성인 1인, 추정)입니다.");
        }
    }

    /**
     * 여행 전체: 일자별 합계를 넣고, 전체 합계·기준 설명을 채운 뒤 여행 요약(reason) 끝에 한 문장을 붙인다.
     * @param networkLoaded 교통망(전국 철도망이나 GTFS 피드)을 하나라도 가져온 상태인가 (아니면 모든 구간이 거리 기준 추정이다)
     */
    public static void apply(AiRouteResponse response, boolean networkLoaded) {
        if (response == null || response.getTimeline() == null) return;
        if (response.getDays() != null) {
            for (AiRouteResponse.DaySummary day : response.getDays()) applyDay(day, response.getTimeline());
        }

        int total = 0;
        int priced = 0;
        int unknownRoute = 0;
        int busStopRoute = 0;
        int taxiRoute = 0;
        int officialFare = 0;
        int railEstimate = 0;
        int railTable = 0;
        for (AiRouteResponse.TimelineItem item : response.getTimeline()) {
            TransitLegDto leg = item.getTransit();
            if (leg == null) continue;
            if (leg.getFareYen() != null) {
                total += leg.getFareYen();
                if (leg.getFareYen() > 0) priced++;
            }
            if ("distance".equals(leg.getSource()) && leg.getFareYen() != null) unknownRoute++;
            if ("bus-stops".equals(leg.getSource())) busStopRoute++;
            if ("taxi".equals(leg.getSource())) taxiRoute++;
            boolean rail = leg.getSource() != null && leg.getSource().startsWith("rail-network");
            if (hasFareBasis(leg)) {
                // 승차 단계마다 요금의 근거가 적혀 있다
                if (hasListedBusFare(leg)) officialFare++;
                if (rail && hasRailFare(leg, "estimate")) railEstimate++;
                if (rail && (hasRailFare(leg, "table") || hasRailFare(leg, "official"))) railTable++;
            } else {
                if ("official".equals(leg.getFareSource()) || "mixed".equals(leg.getFareSource())) officialFare++;
                if (rail) railEstimate++;
            }
        }
        if (priced == 0) return;   // 전부 도보이거나 자동차 여행

        response.setTransitFareYen(total);
        StringBuilder note = new StringBuilder("성인 1인 기준 추정입니다.");
        if (!networkLoaded) {
            note.append(" 교통 데이터(전국 철도망·버스 노선)를 아직 가져오지 않아 모든 구간을 거리 기준으로만 계산했습니다.");
        } else {
            if (railTable > 0) {
                note.append(" 철도 ").append(railTable).append("개 구간은 운영사 요금표(현금·승차권 기준)에 철도망에서 잰 거리를 넣어 계산했습니다. 요금 구간의 경계 근처에서는 한 단계 다를 수 있습니다.");
            }
            if (railEstimate > 0) {
                note.append(railTable > 0
                        ? " 요금표가 등록되지 않은 운영사와 신칸센 특급요금은 종류별 대표 요금표로 추정했습니다."
                        : " 철도 구간은 전국 철도망의 선로 거리와 종류별 요금표로 계산했고, 열차 시각·급행·패스 할인은 반영하지 않았습니다.");
            }
            if (officialFare > 0) {
                note.append(" 버스 등 ").append(officialFare).append("개 구간의 요금은 운영사가 공개한 요금표(현금 성인) 기준입니다.");
            }
            if (busStopRoute > 0) {
                note.append(" 버스 ").append(busStopRoute).append("개 구간은 국토수치정보 버스 정류장 자료(2022년)로 노선을 찾았고, 시각표가 없어 시간·요금은 거리 기준 추정입니다.");
            }
            if (taxiRoute > 0) {
                note.append(" ").append(taxiRoute).append("개 구간은 잇는 대중교통이 없어 택시 요금(차 한 대, 추정)을 넣었습니다.");
            }
            if (unknownRoute > 0) {
                note.append(" ").append(unknownRoute).append("개 구간은 가져온 노선 자료에 맞는 경로가 없어 버스 등으로 보고 거리 기준으로 계산했습니다(노선 미확인).");
            }
        }
        response.setTransitNote(note.toString());
        if (response.getReason() != null) {
            response.setReason(response.getReason() + " 교통비는 합계 약 " + yen(total) + "(성인 1인, 추정)입니다.");
        }
    }

    private static boolean hasFareBasis(TransitLegDto leg) {
        for (TransitLegDto.Step step : leg.getSteps()) {
            if (step.getFareBasis() != null) return true;
        }
        return false;
    }

    /** 버스 등 GTFS 노선 가운데 운영사 요금표에 요금이 있는 승차가 있는가 */
    private static boolean hasListedBusFare(TransitLegDto leg) {
        for (TransitLegDto.Step step : leg.getSteps()) {
            if (step.getTripsPerDay() != null && Boolean.TRUE.equals(step.getFareConfirmed())) return true;
        }
        return false;
    }

    /** 철도망 승차 가운데 요금 근거가 basis 인 것이 있는가 */
    private static boolean hasRailFare(TransitLegDto leg, String basis) {
        for (TransitLegDto.Step step : leg.getSteps()) {
            if (step.getTripsPerDay() == null && basis.equals(step.getFareBasis())) return true;
        }
        return false;
    }

    static String yen(int amount) {
        return String.format("%,d엔", amount);
    }
}
