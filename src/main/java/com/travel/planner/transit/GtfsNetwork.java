package com.travel.planner.transit;

import java.util.ArrayList;
import java.util.List;

/** 여러 GTFS 피드의 정류장·노선 패턴을 하나로 합친 것 (정류장 번호를 전체에서 이어 매긴다). 경로 탐색 그래프의 입력이다. */
public final class GtfsNetwork {

    public final List<GtfsFeed.Stop> stops = new ArrayList<>();
    public final List<GtfsFeed.Pattern> patterns = new ArrayList<>();

    /** 피드 하나를 덧붙인다. 넘겨받은 feed 의 정류장·패턴 객체를 그대로 쓰면서 번호만 고친다. */
    public GtfsNetwork add(String feedKey, GtfsFeed feed) {
        int offset = stops.size();
        for (GtfsFeed.Stop stop : feed.stops) {
            stop.index += offset;
            stops.add(stop);
        }
        for (GtfsFeed.Pattern pattern : feed.patterns) {
            if (offset > 0) {
                int[] shifted = new int[pattern.stops.length];
                for (int i = 0; i < shifted.length; i++) shifted[i] = pattern.stops[i] + offset;
                pattern.stops = shifted;
            }
            pattern.feedKey = feedKey;
            pattern.feedExpired = feed.expired;
            patterns.add(pattern);
        }
        return this;
    }
}
