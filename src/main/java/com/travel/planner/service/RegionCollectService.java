package com.travel.planner.service;

import com.travel.planner.entity.Place;
import com.travel.planner.entity.PlaceCollectLog;
import com.travel.planner.entity.Region;
import com.travel.planner.repository.PlaceCollectLogRepository;
import com.travel.planner.repository.PlaceRepository;
import com.travel.planner.util.CollectRegions;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 지역별 장소 수집.
 * CollectRegions 의 도시 목록을 지역 순서대로 돌며 구글 장소 검색으로 장소를 모은다.
 *
 * 호출을 아끼는 장치
 *  - 장소가 이미 넉넉한 도시(큰 도시 80곳, 작은 도시 25곳 이상)와 전에 지역 수집을 끝낸 도시는 건너뛴다.
 *  - 작은 도시는 검색어 8개 × 도심 1곳 × 최대 2쪽만 받는다.
 *  - 한 쪽을 받았는데 대부분 이미 DB 에 있는 장소면 다음 쪽을 받지 않는다.
 *  - 이번 실행에 쓸 최대 호출 수(maxCalls)에 닿으면 멈춘다. 멈춘 도시는 기록하지 않으므로 다음 실행 때 거기서부터 이어서 한다.
 */
@Service
@RequiredArgsConstructor
public class RegionCollectService {

    private final PlaceRepository placeRepository;
    private final PlaceCollectLogRepository collectLogRepository;
    private final GoogleMapsService googleMapsService;

    /** 한 번 실행할 때 쓰는 장소 검색 호출 수 기본값 */
    public static final int DEFAULT_MAX_CALLS = 2000;
    /** 장소가 이만큼 이상 있으면 건너뛴다 */
    public static final int MAJOR_SKIP_AT = 80;
    public static final int TOWN_SKIP_AT = 25;

    static final int MAJOR_MAX_PAGES = 3;
    static final int TOWN_MAX_PAGES = 2;
    static final int MAJOR_GRID_POINTS = 5;

    /** 지금의 /collect-places 와 같은 검색 세트 */
    static final String[] MAJOR_SEARCH = {
            "[TYPE]tourist_attraction", "[TYPE]museum", "[TYPE]park", "[TYPE]amusement_park",
            "[TEXT]신사 사원", "[TEXT]전망대 야경", "[TEXT]정원 자연 명소", "[TEXT]체험 액티비티",
            "[TEXT]애니메이션 굿즈 성지", "[TEXT]온천",
            "[TYPE]restaurant", "[TYPE]cafe", "[TYPE]shopping_mall", "[TEXT]시장", "[TEXT]기념품 상점", "[TYPE]lodging"
    };
    /** 작은 도시용 (숙소는 라쿠텐으로 찾으므로 뺐다) */
    static final String[] TOWN_SEARCH = {
            "[TYPE]tourist_attraction", "[TYPE]park", "[TEXT]신사 사원", "[TEXT]온천",
            "[TEXT]전망대 야경", "[TYPE]restaurant", "[TYPE]cafe", "[TEXT]시장 기념품"
    };

    /** 미리보기 예상치: 실제로 써 본 평균에 가까운 값 (중복이 많으면 더 적게 든다) */
    static final int MAJOR_EST_CALLS = 100;
    static final int TOWN_EST_CALLS = 12;

    /** 장소 검색(Text Search Enterprise) 1000번당 달러, 원 환산 (무료 체험 크레딧 화면 기준 약 1,360원) */
    static final double USD_PER_1000 = 35.0;
    static final int KRW_PER_USD = 1360;

    // ---------------------------------------------------------------- 계획 (미리보기와 실행이 같이 쓴다)

    public static final class Target {
        public final CollectRegions.City city;
        public final long existing;

        Target(CollectRegions.City city, long existing) {
            this.city = city;
            this.existing = existing;
        }

        int maxCalls() {
            return city.major ? MAJOR_GRID_POINTS * MAJOR_SEARCH.length * MAJOR_MAX_PAGES : TOWN_SEARCH.length * TOWN_MAX_PAGES;
        }

        int estCalls() {
            return city.major ? MAJOR_EST_CALLS : TOWN_EST_CALLS;
        }
    }

    public static final class Plan {
        public final List<Target> targets = new ArrayList<>();
        /** 도시 → 건너뛰는 이유 */
        public final Map<String, String> skipped = new LinkedHashMap<>();
    }

    /** 돌 도시와 건너뛸 도시를 정한다 */
    static Plan plan(List<Region> regions, Map<String, Long> placeCounts, Collection<String> collected, boolean force) {
        Plan plan = new Plan();
        Set<String> done = new HashSet<>(collected);
        Set<String> seen = new HashSet<>();
        for (Region region : regions) {
            for (CollectRegions.City city : CollectRegions.of(region)) {
                if (!seen.add(city.name)) continue;
                long count = placeCounts.getOrDefault(city.name, 0L);
                int skipAt = city.major ? MAJOR_SKIP_AT : TOWN_SKIP_AT;
                if (!force && done.contains(city.name)) {
                    plan.skipped.put(city.name, "전에 지역 수집을 끝낸 도시 (장소 " + count + "곳)");
                } else if (!force && count >= skipAt) {
                    plan.skipped.put(city.name, "장소가 이미 " + count + "곳 있음 (기준 " + skipAt + "곳)");
                } else {
                    plan.targets.add(new Target(city, count));
                }
            }
        }
        return plan;
    }

    private Plan currentPlan(List<Region> regions, boolean force) {
        return plan(regions, placeCounts(), collectLogRepository.findCollectedCities(), force);
    }

    private Map<String, Long> placeCounts() {
        Map<String, Long> counts = new HashMap<>();
        for (Object[] row : placeRepository.countByCity()) {
            if (row[0] != null && row[1] instanceof Number n) counts.put(row[0].toString(), n.longValue());
        }
        return counts;
    }

    // ---------------------------------------------------------------- 미리보기 (구글 호출 없음)

    public Map<String, Object> preview(List<Region> regions, int maxCalls, boolean force) {
        return describePlan(currentPlan(regions, force), regions, maxCalls);
    }

    static Map<String, Object> describePlan(Plan plan, List<Region> regions, int maxCalls) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("미리보기", "구글을 부르지 않고 계획만 보여 줍니다. 실제로 돌리려면 dryRun=false 로 다시 요청하세요.");
        out.put("지역", regions.stream().map(Region::getDescription).toList());

        int est = 0;
        int max = 0;
        int estWithinBudget = 0;
        String lastWithinBudget = null;
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Target t : plan.targets) {
            est += t.estCalls();
            max += t.maxCalls();
            if (est <= maxCalls) {
                estWithinBudget = est;
                lastWithinBudget = t.city.name;
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("도시", t.city.name);
            row.put("지역", t.city.region.getDescription());
            row.put("방식", t.city.major ? "큰 도시 (검색어 16개 × 격자 5곳)" : "작은 도시 (검색어 8개 × 도심 1곳)");
            row.put("지금 장소 수", t.existing);
            row.put("예상 호출", t.estCalls());
            row.put("최대 호출", t.maxCalls());
            rows.add(row);
        }
        out.put("수집할 도시 수", plan.targets.size());
        out.put("건너뛸 도시 수", plan.skipped.size());
        out.put("예상 호출 합계", est);
        out.put("최대 호출 합계", max);
        out.put("예상 비용", cost(est));
        out.put("최대 비용", cost(max));
        out.put("이번 실행 한도(maxCalls)", maxCalls);
        if (plan.targets.isEmpty()) {
            out.put("이번 실행 예상", "수집할 도시가 없습니다.");
        } else if (est <= maxCalls) {
            out.put("이번 실행 예상", "한도 안에서 모두 끝날 것으로 보입니다.");
        } else {
            out.put("이번 실행 예상", lastWithinBudget == null
                    ? "첫 도시도 한도를 넘을 수 있습니다. maxCalls 를 늘려 주세요."
                    : "대략 [" + lastWithinBudget + "]까지 하고 멈춥니다 (예상 " + estWithinBudget + "번). 다시 실행하면 이어서 합니다.");
        }
        out.put("참고", "비용은 장소 검색(Text Search Enterprise) 1000번당 35달러 기준이고, 매달 무료 1000번은 빼지 않았습니다. "
                + "도시 위치 확인(지오코딩)은 도시마다 2번이며 무료 한도(월 1만 번)가 넉넉해 따로 세지 않습니다.");
        out.put("수집할 도시", rows);
        out.put("건너뛸 도시", plan.skipped);
        return out;
    }

    static String cost(int calls) {
        double usd = calls / 1000.0 * USD_PER_1000;
        long krw = Math.round(usd * KRW_PER_USD / 1000.0) * 1000;
        return String.format("약 %.1f달러 (약 %,d원)", usd, krw);
    }

    // ---------------------------------------------------------------- 실행

    private final AtomicBoolean running = new AtomicBoolean(false);
    private volatile Status status = new Status();

    public boolean isRunning() { return running.get(); }
    public void stop() { running.set(false); }

    /** 진행 상황 (GET /collect-region/status) */
    public static final class Status {
        volatile String state = "대기";
        volatile String regions = "";
        volatile String currentCity;
        volatile int maxCalls;
        volatile int calls;
        volatile int inserted;
        volatile int duplicates;
        volatile int citiesTotal;
        final List<String> finished = new java.util.concurrent.CopyOnWriteArrayList<>();
        volatile String message;
        volatile LocalDateTime startedAt;
        volatile LocalDateTime endedAt;
    }

    public Map<String, Object> status() {
        Status s = status;
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("상태", running.get() ? "실행 중" : s.state);
        out.put("지역", s.regions);
        if (running.get() && s.currentCity != null) out.put("지금 도시", s.currentCity);
        out.put("끝낸 도시", s.finished.size() + " / " + s.citiesTotal);
        out.put("장소 검색 호출", s.calls + " / " + s.maxCalls);
        out.put("쓴 비용", cost(s.calls));
        out.put("새로 넣은 장소", s.inserted);
        out.put("이미 있던 장소", s.duplicates);
        if (s.message != null) out.put("메시지", s.message);
        if (s.startedAt != null) out.put("시작", s.startedAt.toString());
        if (s.endedAt != null) out.put("끝", s.endedAt.toString());
        out.put("도시별 결과", s.finished);
        return out;
    }

    /** 수집에 쓰는 DB 동작 (테스트에서 바꿔 끼운다) */
    interface Store {
        boolean known(String placeId);
        Place find(String placeId);
        void save(Place place);
        void logCity(CollectRegions.City city, int calls, int inserted);
    }

    private Store dbStore() {
        return new Store() {
            @Override public boolean known(String placeId) { return placeRepository.existsByPlaceId(placeId); }
            @Override public Place find(String placeId) { return placeRepository.findByPlaceId(placeId).orElse(null); }
            @Override public void save(Place place) { placeRepository.save(place); }
            @Override public void logCity(CollectRegions.City city, int calls, int inserted) {
                PlaceCollectLog log = new PlaceCollectLog();
                log.setCity(city.name);
                log.setRegion(city.region.name());
                log.setMode(city.major ? "FULL" : "LIGHT");
                log.setCalls(calls);
                log.setInserted(inserted);
                collectLogRepository.save(log);
            }
        };
    }

    @Async("adminTaskExecutor")
    public void run(List<Region> regions, int maxCalls, boolean force) {
        if (!running.compareAndSet(false, true)) return;
        try {
            Plan plan = currentPlan(regions, force);
            Status s = newStatus(regions, maxCalls, plan);
            status = s;
            collect(plan, maxCalls, dbStore(), s);
        } catch (RuntimeException e) {
            status.state = "오류로 멈춤";
            status.message = e.getMessage();
            System.out.println("[지역 수집] 오류로 멈췄습니다: " + e.getMessage());
        } finally {
            status.endedAt = LocalDateTime.now();
            status.currentCity = null;
            running.set(false);
        }
    }

    static Status newStatus(List<Region> regions, int maxCalls, Plan plan) {
        Status s = new Status();
        s.regions = String.join(", ", regions.stream().map(Region::getDescription).toList());
        s.maxCalls = maxCalls;
        s.citiesTotal = plan.targets.size();
        s.startedAt = LocalDateTime.now();
        s.state = "실행 중";
        return s;
    }

    /** 계획대로 도시를 차례로 돈다. running 이 꺼지거나 한도에 닿으면 멈춘다. */
    void collect(Plan plan, int maxCalls, Store store, Status s) {
        System.out.println("[지역 수집] 시작: " + s.regions + " / 도시 " + plan.targets.size() + "곳 / 한도 " + maxCalls + "번");
        for (Target t : plan.targets) {
            if (!running.get()) {
                s.state = "중지됨";
                s.message = "중지 요청으로 멈췄습니다. 다시 실행하면 [" + t.city.name + "]부터 이어서 합니다.";
                return;
            }
            s.currentCity = t.city.name;
            CityResult r = collectCity(t, maxCalls - s.calls, store, s);
            if (r.budgetHit) {
                s.state = "한도 도달";
                s.message = "이번 실행 한도(" + maxCalls + "번)에 닿아 [" + t.city.name + "]에서 멈췄습니다. 다시 실행하면 이 도시부터 이어서 합니다.";
                System.out.println("[지역 수집] " + s.message);
                return;
            }
            if (r.stopped) {
                s.state = "중지됨";
                s.message = "중지 요청으로 [" + t.city.name + "]에서 멈췄습니다. 다시 실행하면 이 도시부터 이어서 합니다.";
                return;
            }
            if (r.failed != null) {
                s.finished.add(t.city.name + ": 건너뜀 (" + r.failed + ")");
                continue;
            }
            store.logCity(t.city, r.calls, r.inserted);
            s.finished.add(t.city.name + ": +" + r.inserted + "곳 (호출 " + r.calls + "번, 이미 있던 곳 " + r.duplicates + ")");
            System.out.println("[지역 수집] " + t.city.name + " 완료: 새 장소 " + r.inserted + "곳, 호출 " + r.calls + "번 (누적 " + s.calls + "/" + maxCalls + ")");
        }
        s.state = "완료";
        s.message = "계획한 도시를 모두 돌았습니다. 새 장소의 테마는 /admin/enrich-themes 로 채워 주세요.";
        System.out.println("[지역 수집] " + s.message);
    }

    static final class CityResult {
        int calls;
        int inserted;
        int duplicates;
        boolean budgetHit;
        boolean stopped;
        String failed;
    }

    CityResult collectCity(Target t, int budgetLeft, Store store, Status s) {
        CityResult r = new CityResult();
        CollectRegions.City city = t.city;
        String formalized;
        try {
            formalized = googleMapsService.getFormalizedJapanCity(city.query);
        } catch (RuntimeException e) {
            r.failed = "지명 확인 실패";
            return r;
        }
        List<double[]> grid = googleMapsService.getCityGrid(formalized);
        if (grid == null || grid.isEmpty()) {
            r.failed = "도시 범위 확인 실패";
            return r;
        }
        if (!city.major) grid = grid.subList(0, 1);   // 작은 도시는 도심 한 곳만
        String[] suite = city.major ? MAJOR_SEARCH : TOWN_SEARCH;
        int maxPages = city.major ? MAJOR_MAX_PAGES : TOWN_MAX_PAGES;

        for (double[] node : grid) {
            for (String item : suite) {
                if (!running.get()) {
                    r.stopped = true;
                    return r;
                }
                if (r.calls + maxPages > budgetLeft) {   // 이번 검색이 최대로 쓰면 한도를 넘는다
                    r.budgetHit = true;
                    return r;
                }
                GoogleMapsService.CollectBatch batch = googleMapsService.searchForCollection(
                        city.name, formalized, node[0], node[1], node[2], item, maxPages, false, store::known);
                r.calls += batch.calls;
                s.calls += batch.calls;
                for (Place fetched : batch.places) {
                    if (fetched.getPlaceId() == null || fetched.getPlaceId().isBlank()) continue;
                    fetched.setCity(city.name);
                    Place existing = store.find(fetched.getPlaceId());
                    if (existing != null) {
                        if (backfill(existing, fetched)) store.save(existing);
                        r.duplicates++;
                        s.duplicates++;
                        continue;
                    }
                    store.save(fetched);
                    r.inserted++;
                    s.inserted++;
                }
            }
        }
        return r;
    }

    /** 이미 있는 장소에 비어 있는 값만 새 응답으로 채운다. 바뀐 것이 있으면 true. (/collect-places 와 같이 쓴다) */
    public static boolean backfill(Place existing, Place fetched) {
        boolean changed = false;
        if ((existing.getCategory() == null || existing.getCategory().isBlank()) && fetched.getCategory() != null) {
            existing.setCategory(fetched.getCategory());
            changed = true;
        }
        if (existing.getOpeningPeriods() == null && fetched.getOpeningPeriods() != null) {
            existing.setOpeningPeriods(fetched.getOpeningPeriods());
            if (fetched.getOpeningHours() != null) existing.setOpeningHours(fetched.getOpeningHours());
            changed = true;
        }
        if (existing.getAddress() == null && fetched.getAddress() != null) { existing.setAddress(fetched.getAddress()); changed = true; }
        if (existing.getPhone() == null && fetched.getPhone() != null) { existing.setPhone(fetched.getPhone()); changed = true; }
        if (existing.getRating() == null && fetched.getRating() != null) { existing.setRating(fetched.getRating()); changed = true; }
        if (existing.getUserRatingCount() == null && fetched.getUserRatingCount() != null) {
            existing.setUserRatingCount(fetched.getUserRatingCount());
            changed = true;
        }
        if (changed) existing.setLastUpdated(LocalDateTime.now());
        return changed;
    }

    /** 테스트용: running 을 켠 상태로 collect 를 부를 수 있게 */
    void setRunningForTest(boolean on) { running.set(on); }
}
