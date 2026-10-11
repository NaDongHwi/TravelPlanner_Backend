package com.travel.planner.service;

import com.travel.planner.entity.Place;
import com.travel.planner.repository.PlaceRepository;
import com.travel.planner.util.PlaceNames;
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

/**
 * 일정·문서에 나오는 장소 가운데 이름이 영어·일본어로만 된 곳에 "보여 줄 한국어 이름"을 붙인다.
 *
 * DB 의 원래 이름(place.name)은 바꾸지 않는다. 한국어 이름은 place.name_ko 에 따로 저장하고, 화면에 보여 줄 때만 쓴다.
 * 한 번 만든 이름은 DB 에 남으므로 같은 장소는 다시 묻지 않는다(일정 화면과 PDF 에 같은 이름이 나온다).
 * 정해진 시간 안에 답이 오지 않으면 기다리지 않고 원래 이름으로 응답하며, 늦게 온 답은 DB 에만 저장해 다음부터 쓴다.
 */
@Service
@RequiredArgsConstructor
public class PlaceNameService {

    private static final int CHUNK_SIZE = 40;       // AI 한 번에 보내는 이름 수
    private static final int MAX_PLACES = 160;      // 한 번에 옮기는 최대 장소 수

    private final AiService aiService;
    private final PlaceRepository placeRepository;

    /** false 면 한국어 이름을 만들지 않는다 (이미 만들어 둔 이름은 계속 보여 준다) */
    @Value("${app.plan.translate-names:true}")
    private boolean enabled = true;

    /** 일정 생성·문서 출력이 이름을 기다려 주는 최대 시간(초) */
    @Value("${app.plan.translate-names-timeout-seconds:15}")
    private int timeoutSeconds = 15;

    private final ExecutorService executor = Executors.newFixedThreadPool(2, runnable -> {
        Thread t = new Thread(runnable, "place-name");
        t.setDaemon(true);
        return t;
    });

    /**
     * 한국어 이름이 필요한데 아직 없는 장소를 AI 로 채운다. 전달받은 Place 객체에 바로 값을 넣는다.
     * @return 제한 시간 안에 채운 장소 수
     */
    public int fillMissing(Collection<Place> places) {
        if (!enabled || places == null || places.isEmpty()) return 0;

        Map<String, Place> targets = new LinkedHashMap<>();
        for (Place p : places) {
            if (p == null || p.getPlaceId() == null || p.getName() == null) continue;
            if (p.getNameKo() != null && !p.getNameKo().isBlank()) continue;
            if (PlanService.isPseudoCategory(p.getCategory())) continue;
            if (!PlaceNames.needsKorean(p.getName())) continue;
            if (targets.size() >= MAX_PLACES) break;
            targets.putIfAbsent(p.getPlaceId(), p);
        }
        if (targets.isEmpty()) return 0;

        List<Place> list = new ArrayList<>(targets.values());
        List<CompletableFuture<Map<Place, String>>> jobs = new ArrayList<>();
        for (int from = 0; from < list.size(); from += CHUNK_SIZE) {
            List<Place> chunk = new ArrayList<>(list.subList(from, Math.min(list.size(), from + CHUNK_SIZE)));
            jobs.add(CompletableFuture.supplyAsync(() -> translate(chunk), executor));
        }

        long started = System.currentTimeMillis();
        boolean timedOut = false;
        try {
            CompletableFuture.allOf(jobs.toArray(new CompletableFuture[0])).get(Math.max(1, timeoutSeconds), TimeUnit.SECONDS);
        } catch (java.util.concurrent.TimeoutException e) {
            timedOut = true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            System.out.println("[장소 이름] AI 호출 중 오류가 있어 그 묶음은 원래 이름을 사용합니다: " + e.getMessage());
        }

        // 제때 온 답은 부른 쪽(이 스레드)에서 Place 객체에 넣고 저장한다.
        //  - 일정 바꾸기처럼 트랜잭션 안에서 불렸을 때, 아직 확정되지 않은 새 장소를 다른 스레드가 고치려다 서로 기다리는 일을 막는다.
        //  - 늦게 온 답은 Place 객체를 건드리지 않고 DB 에만 저장한다. 응답을 만드는 도중에 이름이 바뀌어
        //    한 응답 안에서 같은 장소가 두 가지 이름으로 나오는 일을 막기 위해서다.
        int filled = 0;
        for (CompletableFuture<Map<Place, String>> job : jobs) {
            if (job.isDone()) {
                if (job.isCompletedExceptionally()) continue;
                for (Map.Entry<Place, String> e : job.join().entrySet()) {
                    e.getKey().setNameKo(e.getValue());
                    save(e.getKey(), e.getValue());
                    filled++;
                }
            } else {
                job.thenAcceptAsync(late -> late.forEach(this::save), executor);
            }
        }
        if (timedOut) {
            System.out.println("[장소 이름] " + timeoutSeconds + "초 안에 AI 응답이 다 오지 않아 " + filled + "/" + list.size()
                    + "곳만 반영했습니다. 나머지는 원래 이름으로 응답하고, 늦게 온 답은 DB 에 저장해 다음부터 씁니다.");
        } else {
            System.out.println("[장소 이름] 영어·일본어 이름 " + list.size() + "곳 중 " + filled + "곳에 한국어 이름을 붙였습니다. ("
                    + (System.currentTimeMillis() - started) + "ms)");
        }
        return filled;
    }

    /** AI 에 묻고, 쓸 수 있는 답만 골라 {장소: 한국어 이름}으로 돌려준다. (Place 객체와 DB 는 건드리지 않는다) */
    private Map<Place, String> translate(List<Place> chunk) {
        Map<Place, String> named = new java.util.IdentityHashMap<>();
        Map<String, String> translated = aiService.translatePlaceNames(chunk);
        if (translated == null || translated.isEmpty()) return named;
        for (Place place : chunk) {
            String korean = PlaceNames.clean(translated.get(place.getPlaceId()), place.getName());
            if (korean != null) named.put(place, korean);
        }
        return named;
    }

    /** 이름 칸만 고친다 (같은 장소의 소개를 다른 작업이 동시에 저장해도 서로 덮어쓰지 않도록) */
    private void save(Place place, String korean) {
        try {
            if (place.getId() != null) placeRepository.updateNameKo(place.getId(), korean);
        } catch (Exception e) {
            System.out.println("[장소 이름] 저장 실패(" + place.getName() + "): " + e.getMessage());
        }
    }
}
