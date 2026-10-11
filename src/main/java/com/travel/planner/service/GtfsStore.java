package com.travel.planner.service;

import com.travel.planner.entity.TransitFeed;
import com.travel.planner.entity.TransitPattern;
import com.travel.planner.entity.TransitStop;
import com.travel.planner.repository.TransitFeedRepository;
import com.travel.planner.repository.TransitPatternRepository;
import com.travel.planner.repository.TransitStopRepository;
import com.travel.planner.transit.GtfsCodec;
import com.travel.planner.transit.GtfsFeed;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * GTFS 피드 하나를 DB 에 넣고 뺀다. 같은 feedKey 의 이전 자료는 통째로 바꾼다.
 * 중간에 실패하면 이전 자료가 그대로 남도록 한 트랜잭션으로 처리한다.
 */
@Service
@RequiredArgsConstructor
public class GtfsStore {

    private static final int CHUNK = 500;

    private final TransitFeedRepository feedRepository;
    private final TransitStopRepository stopRepository;
    private final TransitPatternRepository patternRepository;

    @Transactional
    public TransitFeed replaceFeed(String feedKey, String name, String url, String license, GtfsFeed feed) {
        patternRepository.deleteFeed(feedKey);
        stopRepository.deleteFeed(feedKey);

        // 정류장 저장 (저장하면서 받은 DB id 를 노선 패턴이 가리킨다)
        long[] stopRowIds = new long[feed.stops.size()];
        List<TransitStop> chunk = new ArrayList<>();
        int saved = 0;
        for (GtfsFeed.Stop stop : feed.stops) {
            chunk.add(GtfsCodec.toRow(feedKey, stop));
            if (chunk.size() == CHUNK) {
                saved = saveStops(chunk, stopRowIds, saved);
                chunk = new ArrayList<>();
            }
        }
        if (!chunk.isEmpty()) saveStops(chunk, stopRowIds, saved);

        List<TransitPattern> patterns = new ArrayList<>();
        for (GtfsFeed.Pattern pattern : feed.patterns) {
            patterns.add(GtfsCodec.toRow(feedKey, pattern, stopRowIds));
            if (patterns.size() == CHUNK) {
                patternRepository.saveAll(patterns);
                patterns = new ArrayList<>();
            }
        }
        if (!patterns.isEmpty()) patternRepository.saveAll(patterns);

        TransitFeed row = row(feedKey);
        row.setName(name == null || name.isBlank() ? (feed.publisher != null ? feed.publisher : feedKey) : name);
        row.setUrl(url);
        if (license != null && !license.isBlank()) row.setLicense(cut(license, 300));
        row.setAgencies(cut(String.join(" / ", feed.agencies), 500));
        row.setValidFrom(feed.validFrom);
        row.setValidTo(feed.validTo);
        row.setImportedAt(LocalDateTime.now());
        row.setCheckedAt(LocalDateTime.now());
        row.setStopCount(feed.stops.size());
        row.setPatternCount(feed.patterns.size());
        row.setRouteCount(feed.routeCount);
        row.setTripCount(feed.tripCount);
        row.setFareCoveragePercent(feed.fareCoveragePercent);
        row.setIncludeRail(feed.includesRail);
        row.setStatus(feed.expired ? TransitFeed.EXPIRED : TransitFeed.OK);
        row.setMessage(feed.warnings.isEmpty() ? null : String.join(" ", feed.warnings));
        return feedRepository.save(row);
    }

    private int saveStops(List<TransitStop> chunk, long[] stopRowIds, int offset) {
        List<TransitStop> saved = stopRepository.saveAll(chunk);
        for (int i = 0; i < saved.size(); i++) stopRowIds[offset + i] = saved.get(i).getId();
        return offset + saved.size();
    }

    /** 가져오기에 실패했다는 사실만 남긴다. 이전에 가져온 자료가 있으면 그대로 둔다. */
    @Transactional
    public TransitFeed markFailed(String feedKey, String name, String url, String license, String reason) {
        TransitFeed row = row(feedKey);
        if (row.getName() == null) row.setName(name == null || name.isBlank() ? feedKey : name);
        if (row.getUrl() == null) row.setUrl(url);
        if (row.getLicense() == null && license != null && !license.isBlank()) row.setLicense(cut(license, 300));
        row.setCheckedAt(LocalDateTime.now());
        row.setMessage("가져오기 실패: " + reason + (row.getImportedAt() != null ? " (이전에 가져온 자료를 계속 씁니다)" : ""));
        if (row.getImportedAt() == null) row.setStatus(TransitFeed.FAILED);
        return feedRepository.save(row);
    }

    @Transactional
    public boolean deleteFeed(String feedKey) {
        List<TransitFeed> rows = feedRepository.findByFeedKey(feedKey);
        patternRepository.deleteFeed(feedKey);
        stopRepository.deleteFeed(feedKey);
        feedRepository.deleteAll(rows);
        return !rows.isEmpty();
    }

    private TransitFeed row(String feedKey) {
        List<TransitFeed> existing = feedRepository.findByFeedKey(feedKey);
        if (!existing.isEmpty()) return existing.get(0);
        TransitFeed row = new TransitFeed();
        row.setFeedKey(feedKey);
        return row;
    }

    private static String cut(String text, int max) {
        if (text == null) return null;
        return text.length() <= max ? text : text.substring(0, max);
    }
}
