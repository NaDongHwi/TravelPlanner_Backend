package com.travel.planner.service;

import com.travel.planner.entity.TransitName;
import com.travel.planner.entity.TransitStation;
import com.travel.planner.repository.TransitNameRepository;
import com.travel.planner.repository.TransitStationRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 역·노선·운영 회사 이름의 한국어 표기.
 * 원본 데이터에는 일본어만 있으므로 AI 로 한 번 번역해 DB(transit_name)에 저장해 두고 쓴다.
 * 아직 번역되지 않은 이름은 일본어 원문 그대로 나간다.
 */
@Service
@RequiredArgsConstructor
public class TransitNameService {
    // 주의: 이 클래스는 인터페이스를 구현하지 않는다. @Async 메서드가 있는 빈이 인터페이스를 구현하면
    // 스프링이 인터페이스 기반 프록시를 만들어, 다른 빈에서 이 클래스 타입으로 주입받지 못하게 될 수 있다.

    private static final int BATCH = 80;

    // 자주 나오는 운영 회사는 번역을 돌리기 전에도 한국어로 보이도록 미리 넣어 둔다
    private static final Map<String, String> BUILT_IN_OPERATORS = new HashMap<>();

    static {
        BUILT_IN_OPERATORS.put("東日本旅客鉄道", "JR 동일본");
        BUILT_IN_OPERATORS.put("西日本旅客鉄道", "JR 서일본");
        BUILT_IN_OPERATORS.put("東海旅客鉄道", "JR 도카이");
        BUILT_IN_OPERATORS.put("北海道旅客鉄道", "JR 홋카이도");
        BUILT_IN_OPERATORS.put("四国旅客鉄道", "JR 시코쿠");
        BUILT_IN_OPERATORS.put("九州旅客鉄道", "JR 규슈");
        BUILT_IN_OPERATORS.put("東京地下鉄", "도쿄메트로");
        BUILT_IN_OPERATORS.put("東京都", "도에이");
        BUILT_IN_OPERATORS.put("大阪市高速電気軌道", "오사카메트로");
    }

    private final TransitNameRepository nameRepository;
    private final TransitStationRepository stationRepository;
    private final AiService aiService;

    private final AtomicBoolean translating = new AtomicBoolean(false);
    private volatile Map<String, Map<String, String>> cache;   // kind → (일본어 → 한국어)
    private volatile String lastMessage = "아직 실행한 적이 없습니다.";

    public boolean isTranslating() { return translating.get(); }
    public void stopTranslating() { translating.set(false); }
    public String lastMessage() { return lastMessage; }

    public String station(String ja) { return lookup(TransitName.STATION, ja); }
    public String line(String ja) { return lookup(TransitName.LINE, ja); }

    public String operator(String ja) {
        String stored = names().getOrDefault(TransitName.OPERATOR, Map.of()).get(ja);
        if (stored != null) return stored;
        return BUILT_IN_OPERATORS.getOrDefault(ja, ja);
    }

    private String lookup(String kind, String ja) {
        if (ja == null) return null;
        return names().getOrDefault(kind, Map.of()).getOrDefault(ja, ja);
    }

    public int translatedCount() {
        return names().values().stream().mapToInt(Map::size).sum();
    }

    private Map<String, Map<String, String>> names() {
        Map<String, Map<String, String>> current = cache;
        if (current != null) return current;
        synchronized (this) {
            if (cache != null) return cache;
            Map<String, Map<String, String>> loaded = new HashMap<>();
            try {
                for (TransitName n : nameRepository.findAll()) {
                    loaded.computeIfAbsent(n.getKind(), k -> new HashMap<>()).put(n.getJa(), n.getKo());
                }
            } catch (Exception e) {
                System.out.println("[교통] 이름 번역 표를 읽지 못해 일본어 원문을 사용합니다: " + e.getMessage());
            }
            cache = loaded;
            return loaded;
        }
    }

    /** 아직 번역되지 않은 이름을 종류별로 모은다. */
    Map<String, List<String>> pendingNames() {
        Map<String, Set<String>> all = new HashMap<>();
        all.put(TransitName.OPERATOR, new LinkedHashSet<>());
        all.put(TransitName.LINE, new LinkedHashSet<>());
        all.put(TransitName.STATION, new LinkedHashSet<>());
        for (TransitStation s : stationRepository.findAll()) {
            all.get(TransitName.OPERATOR).add(s.getOperator());
            all.get(TransitName.LINE).add(s.getLine());
            all.get(TransitName.STATION).add(s.getName());
        }
        Map<String, Map<String, String>> done = names();
        Map<String, List<String>> pending = new HashMap<>();
        for (Map.Entry<String, Set<String>> e : all.entrySet()) {
            List<String> list = new ArrayList<>();
            Map<String, String> translated = done.getOrDefault(e.getKey(), Map.of());
            for (String name : e.getValue()) {
                if (name != null && !name.isBlank() && !translated.containsKey(name)) list.add(name);
            }
            pending.put(e.getKey(), list);
        }
        return pending;
    }

    /**
     * 번역되지 않은 역·노선·회사 이름을 80개씩 AI 에 보내 한국어 표기를 채운다 (백그라운드).
     * 회사 → 노선 → 역 순서로 처리한다. 응답에서 빠진 이름은 이번 실행에서는 건너뛰고 다음 실행 때 다시 시도한다.
     */
    @Async("adminTaskExecutor")
    public void translateMissing() {
        if (!translating.compareAndSet(false, true)) return;
        int saved = 0;
        int skipped = 0;
        try {
            Map<String, List<String>> pending = pendingNames();
            int total = pending.values().stream().mapToInt(List::size).sum();
            System.out.println("[교통 이름 번역] 번역할 이름 " + total + "개 (회사 " + pending.get(TransitName.OPERATOR).size()
                    + ", 노선 " + pending.get(TransitName.LINE).size() + ", 역 " + pending.get(TransitName.STATION).size() + ")");

            for (String kind : new String[]{TransitName.OPERATOR, TransitName.LINE, TransitName.STATION}) {
                List<String> names = pending.get(kind);
                int failures = 0;
                for (int from = 0; from < names.size() && translating.get(); from += BATCH) {
                    List<String> batch = names.subList(from, Math.min(names.size(), from + BATCH));
                    Map<String, String> translated = aiService.translateRailNames(kind, batch);
                    if (translated == null || translated.isEmpty()) {
                        if (++failures >= 3) {
                            System.out.println("[교통 이름 번역] AI 응답이 계속 비어 중단합니다. 서버 로그의 [AI 통신 장애 리포트]를 확인해 주세요.");
                            translating.set(false);
                            break;
                        }
                        try { Thread.sleep(30000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); return; }
                        from -= BATCH;   // 같은 묶음을 다시 시도
                        continue;
                    }
                    failures = 0;
                    List<TransitName> rows = new ArrayList<>();
                    for (String ja : batch) {
                        String ko = clean(kind, translated.get(ja));
                        if (ko == null) {
                            skipped++;
                            continue;
                        }
                        TransitName row = new TransitName();
                        row.setKind(kind);
                        row.setJa(ja);
                        row.setKo(ko);
                        rows.add(row);
                    }
                    nameRepository.saveAll(rows);
                    saved += rows.size();
                    cache = null;   // 다음 조회 때 새로 읽는다
                    System.out.println("[교통 이름 번역] " + kind + " " + Math.min(names.size(), from + BATCH) + "/" + names.size() + " (누적 저장 " + saved + "개)");
                    try { Thread.sleep(aiService.batchPauseMillis(10000)); } catch (InterruptedException e) { Thread.currentThread().interrupt(); return; }
                }
            }
            lastMessage = "이름 " + saved + "개를 번역해 저장했습니다." + (skipped > 0 ? " (응답에서 빠진 " + skipped + "개는 다시 실행하면 재시도합니다)" : "");
        } catch (Exception e) {
            lastMessage = "번역 중 오류: " + e.getMessage() + " (저장된 " + saved + "개는 유지됩니다)";
        } finally {
            translating.set(false);
            cache = null;
            System.out.println("[교통 이름 번역 종료] " + lastMessage);
        }
    }

    /** AI 가 돌려준 표기를 다듬는다. 일본어가 그대로 남았거나 너무 길면 버린다. */
    static String clean(String kind, String raw) {
        if (raw == null) return null;
        String s = raw.replaceAll("[\\r\\n\\t\"]+", " ").replaceAll("\\s{2,}", " ").trim();
        if (s.isEmpty() || s.length() > 60) return null;
        // "신주쿠역" → "신주쿠" (역 이름에는 '역'을 빼고 저장하고, 보여 줄 때 붙인다)
        if (TransitName.STATION.equals(kind) && s.endsWith("역") && s.length() > 2) s = s.substring(0, s.length() - 1);
        boolean hasHangul = s.chars().anyMatch(c -> c >= 0xAC00 && c <= 0xD7A3);
        return hasHangul ? s : null;
    }
}
