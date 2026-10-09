package com.travel.planner.service;

import com.travel.planner.entity.Place;
import com.travel.planner.repository.PlaceRepository;
import com.travel.planner.util.PlaceDescriber;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 일정에 들어간 장소 가운데 "AI 가 쓴 한 줄 소개"가 아직 없는 곳만 그 자리에서 채운다.
 *
 * 관리자 배치(enrich-summaries)를 돌리지 않았더라도 사용자가 받는 일정에는 장소별 설명이 붙게 하기 위한 것이다.
 * 한 번 채운 소개는 DB(place.summary)에 남으므로 같은 장소는 다시 묻지 않는다. 그래서 처음 몇 번만 느리고 점점 빨라진다.
 * 정해진 시간 안에 답이 오지 않으면 기다리지 않고 기본 문구로 응답하며, 늦게 온 답은 DB 에만 저장해 다음 일정부터 쓴다.
 */
@Service
@RequiredArgsConstructor
public class PlaceDescriptionService {

    private static final int CHUNK_SIZE = 20;       // AI 한 번에 보내는 장소 수 (작을수록 응답이 빠르다)
    private static final int MAX_PLACES = 80;       // 일정 하나에서 채우는 최대 장소 수

    private final AiService aiService;
    private final PlaceRepository placeRepository;

    /** false 면 일정 생성 때 AI 를 부르지 않는다 (소개는 관리자 배치로만 채운다) */
    @Value("${app.plan.describe-on-create:true}")
    private boolean enabled = true;

    /** 일정 생성이 소개를 기다려 주는 최대 시간(초) */
    @Value("${app.plan.describe-timeout-seconds:25}")
    private int timeoutSeconds = 25;

    private final ExecutorService executor = Executors.newFixedThreadPool(4, runnable -> {
        Thread t = new Thread(runnable, "place-describe");
        t.setDaemon(true);
        return t;
    });

    /**
     * 소개가 없는 장소를 AI 로 채운다. 전달받은 Place 객체에 바로 값을 넣으므로, 돌아온 뒤 응답을 조립하면 소개가 함께 나간다.
     * @return 제한 시간 안에 채운 장소 수
     */
    public int fillMissing(Collection<Place> places) {
        if (!enabled || places == null || places.isEmpty()) return 0;

        Map<String, Place> targets = new LinkedHashMap<>();
        for (Place p : places) {
            if (p == null || p.getPlaceId() == null || p.getName() == null) continue;
            if (PlaceDescriber.hasStoredSummary(p)) continue;
            String category = p.getCategory();
            if ("숙소".equals(category) || "교통".equals(category) || PlanService.isPseudoCategory(category)) continue;
            if (targets.size() >= MAX_PLACES) break;
            targets.putIfAbsent(p.getPlaceId(), p);
        }
        if (targets.isEmpty()) return 0;

        List<Place> list = new ArrayList<>(targets.values());
        AtomicInteger filled = new AtomicInteger();
        List<CompletableFuture<Void>> jobs = new ArrayList<>();
        for (int from = 0; from < list.size(); from += CHUNK_SIZE) {
            List<Place> chunk = list.subList(from, Math.min(list.size(), from + CHUNK_SIZE));
            jobs.add(CompletableFuture.runAsync(() -> filled.addAndGet(describeAndSave(chunk)), executor));
        }

        long started = System.currentTimeMillis();
        try {
            CompletableFuture.allOf(jobs.toArray(new CompletableFuture[0])).get(Math.max(1, timeoutSeconds), TimeUnit.SECONDS);
            System.out.println("[장소 소개] 소개가 없던 " + list.size() + "곳 중 " + filled.get() + "곳을 AI 로 채웠습니다. ("
                    + (System.currentTimeMillis() - started) + "ms)");
        } catch (java.util.concurrent.TimeoutException e) {
            System.out.println("[장소 소개] " + timeoutSeconds + "초 안에 AI 응답이 다 오지 않아 " + filled.get() + "/" + list.size()
                    + "곳만 반영했습니다. 나머지는 기본 문구로 응답하고, 늦게 온 답은 DB 에 저장해 다음 일정부터 씁니다.");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            System.out.println("[장소 소개] AI 호출 중 오류로 기본 문구를 사용합니다: " + e.getMessage());
        }
        return filled.get();
    }

    private int describeAndSave(List<Place> chunk) {
        Map<String, String> described = aiService.describePlacesBulk(chunk, null);
        if (described == null || described.isEmpty()) return 0;

        int saved = 0;
        for (Place place : chunk) {
            String[] parsed = AdminAsyncService.parseDescription(described.get(place.getPlaceId()));
            if (parsed == null) continue;
            if (parsed[0] != null && (place.getSubType() == null || place.getSubType().isBlank())) place.setSubType(parsed[0]);
            place.setSummary(parsed[1]);
            try {
                if (place.getId() != null) placeRepository.save(place);   // 다음부터는 AI 를 다시 부르지 않도록 남긴다
            } catch (Exception e) {
                System.out.println("[장소 소개] 저장 실패(" + place.getName() + "): " + e.getMessage());
            }
            saved++;
        }
        return saved;
    }
}
