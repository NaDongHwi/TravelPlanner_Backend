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
     * @param railNetworkLoaded 전국 철도망을 가져온 상태인가 (아니면 모든 구간이 거리 기준 추정이다)
     */
    public static void apply(AiRouteResponse response, boolean railNetworkLoaded) {
        if (response == null || response.getTimeline() == null) return;
        if (response.getDays() != null) {
            for (AiRouteResponse.DaySummary day : response.getDays()) applyDay(day, response.getTimeline());
        }

        int total = 0;
        int priced = 0;
        int unknownRoute = 0;
        for (AiRouteResponse.TimelineItem item : response.getTimeline()) {
            TransitLegDto leg = item.getTransit();
            if (leg == null) continue;
            if (leg.getFareYen() != null) {
                total += leg.getFareYen();
                if (leg.getFareYen() > 0) priced++;
            }
            if ("distance".equals(leg.getSource()) && leg.getFareYen() != null) unknownRoute++;
        }
        if (priced == 0) return;   // 전부 도보이거나 자동차 여행

        response.setTransitFareYen(total);
        String note = "성인 1인 기준 추정입니다. 철도 구간은 전국 철도망의 선로 거리와 종류별 요금표로 계산했고, 열차 시각·급행·패스 할인은 반영하지 않았습니다.";
        if (!railNetworkLoaded) {
            note = "성인 1인 기준 추정입니다. 전국 철도망 데이터를 아직 가져오지 않아 모든 구간을 거리 기준으로만 계산했습니다.";
        } else if (unknownRoute > 0) {
            note += " " + unknownRoute + "개 구간은 맞는 철도 경로가 없어 버스 등으로 보고 거리 기준으로 계산했습니다(노선 미확인).";
        }
        response.setTransitNote(note);
        if (response.getReason() != null) {
            response.setReason(response.getReason() + " 교통비는 합계 약 " + yen(total) + "(성인 1인, 추정)입니다.");
        }
    }

    static String yen(int amount) {
        return String.format("%,d엔", amount);
    }
}
