package com.travel.planner.service;

import com.travel.planner.entity.Place;
import com.travel.planner.repository.PlaceRepository;
import com.travel.planner.util.OpeningHours;
import com.travel.planner.util.PlaceDescriber;
import com.travel.planner.util.PlaceKind;
import com.travel.planner.util.ThemeVocabulary;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class AdminAsyncService {

    private final PlaceRepository placeRepository;
    private final GoogleMapsService googleMapsService;
    private final AiService aiService;

    // 확인과 설정이 따로면 버튼을 빠르게 두 번 눌렀을 때 작업이 두 개 뜬다 → compareAndSet 으로 한 번에 처리
    private final AtomicBoolean enriching = new AtomicBoolean(false);
    private final AtomicBoolean cleansing = new AtomicBoolean(false);
    private final AtomicBoolean describing = new AtomicBoolean(false);

    public boolean isEnriching() { return enriching.get(); }
    public void stopEnriching() { enriching.set(false); }

    public boolean isCleansing() { return cleansing.get(); }
    public void stopCleansing() { cleansing.set(false); }

    public boolean isDescribing() { return describing.get(); }
    public void stopDescribing() { describing.set(false); }

    /** AI 응답 한 건("테마1,테마2|실내|90|세부유형|한 줄 소개")을 해석한 결과 */
    static class ParsedAttributes {
        String theme;
        String placeType;
        Integer duration;
        String subType;   // 없으면 null (앞 3칸만 온 응답도 받아들인다)
        String summary;   // 없으면 null
    }

    /**
     * AI 응답 해석.
     * 프롬프트는 "테마|속성|체류시간|세부유형|한 줄 소개" 5칸을 요구하지만, 모델이 뒤 칸을 빼먹어도 앞 칸은 살린다.
     * 체류시간까지 없으면(2칸) 테마와 속성만 쓰고 체류시간은 기본값으로 둔다.
     * (이전에는 프롬프트가 2칸을 요구하고 파서는 3칸만 받아서 단 한 건도 저장되지 않았다)
     */
    static ParsedAttributes parseAttributes(String aiResult, Place place) {
        if (aiResult == null) return null;
        String[] parts = aiResult.split("\\|");
        if (parts.length < 2) return null;

        List<String> themes = ThemeVocabulary.normalizeList(parts[0]);
        if (themes.isEmpty()) return null;                      // 12개 어휘에 하나도 안 맞으면 버린다
        if (themes.size() > 3) themes = themes.subList(0, 3);

        ParsedAttributes parsed = new ParsedAttributes();
        parsed.theme = String.join(",", themes);
        parsed.placeType = parts[1].contains("실외") ? "실외" : "실내";

        Integer duration = null;
        if (parts.length >= 3) {
            String digits = parts[2].replaceAll("[^0-9]", "");
            if (!digits.isEmpty() && digits.length() <= 4) duration = Integer.parseInt(digits);
        }
        if (duration == null || duration < 15 || duration > 720) duration = defaultDuration(place);
        parsed.duration = duration;

        if (parts.length >= 4) parsed.subType = PlaceDescriber.cleanSubType(parts[3]);
        if (parts.length >= 5) {
            // 소개 문장 안에 '|' 가 들어간 경우를 대비해 뒤쪽은 모두 이어 붙인다
            parsed.summary = PlaceDescriber.cleanSummary(String.join(" ", java.util.Arrays.copyOfRange(parts, 4, parts.length)));
        }
        return parsed;
    }

    /** 소개 전용 응답 한 건("세부유형|한 줄 소개") 해석. 소개가 없으면 null. */
    static String[] parseDescription(String aiResult) {
        if (aiResult == null) return null;
        String[] parts = aiResult.split("\\|");
        String subType;
        String summary;
        if (parts.length >= 2) {
            subType = PlaceDescriber.cleanSubType(parts[0]);
            summary = PlaceDescriber.cleanSummary(String.join(" ", java.util.Arrays.copyOfRange(parts, 1, parts.length)));
        } else {
            subType = null;                                     // 구분자 없이 문장만 온 경우: 소개로만 쓴다
            summary = PlaceDescriber.cleanSummary(parts[0]);
        }
        if (summary == null) return null;
        return new String[]{subType, summary};
    }

    private static int defaultDuration(Place place) {
        switch (PlaceKind.of(place)) {
            case THEME_PARK: return 480;
            case RESTAURANT: return 60;
            case CAFE: return 45;
            case BAR: return 75;
            default: return 90;
        }
    }

    @Async("adminTaskExecutor")
    public void runThemeEnrichment() {
        if (!enriching.compareAndSet(false, true)) return;

        System.out.println("[어드민 엔진] 오프라인 AI 데이터 인리치먼트 백그라운드 파이프라인 가동 (벌크 최적화)...");

        // 이번 실행 세션 동안에만 실패한 장소 ID를 기억하는 '임시 블랙리스트'
        // DB에 저장되지 않으므로, 작업 종료 후 다음에 다시 버튼을 누르면 초기화되어 재시도합니다.
        Set<Long> failedPlaceIdsThisSession = new HashSet<>();

        try {
            while (enriching.get()) {
                List<Place> targetPlaces = new ArrayList<>();
                int pageNum = 0;

                // 맨 앞줄이 블랙리스트로 꽉 차서 0건이 되는 현상(병목)을 방지합니다.
                // 유효한 장소가 나올 때까지 DB의 다음 페이지(100건 단위)를 계속 넘겨가며 탐색합니다.
                while (targetPlaces.isEmpty()) {
                    org.springframework.data.domain.Pageable pageable = org.springframework.data.domain.PageRequest.of(pageNum, 100);
                    List<Place> rawPlaces = placeRepository.findPlacesNeedingEnrichment(pageable);

                    if (rawPlaces.isEmpty()) {
                        break; // DB 끝까지 다 뒤졌는데 진짜로 남은 빈칸 데이터가 없는 경우 탈출
                    }

                    targetPlaces = rawPlaces.stream()
                            .filter(p -> !failedPlaceIdsThisSession.contains(p.getId()))
                            .limit(30)
                            .collect(Collectors.toList());

                    if (targetPlaces.isEmpty()) {
                        pageNum++; // 가져온 100건이 모조리 블랙리스트라면, 다음 100건을 가져오도록 페이지 증가
                    }
                }

                if (targetPlaces.isEmpty()) {
                    System.out.println("[인리치먼트 완료] 남은 데이터가 없거나 모두 처리에 실패한 데이터입니다.");
                    break;
                }

                System.out.println("남은 테마 데이터 정제 중... (현재 " + targetPlaces.size() + "건 일괄 처리 시도)");

                Map<String, String> reviewsMap = new HashMap<>();
                List<Place> validPlacesForBulk = new ArrayList<>();

                for (Place p : targetPlaces) {
                    if (!enriching.get()) break;

                    // 1. 영업시간 복구 (Place ID 로 조회, 구조화 periods·주소·평점도 함께 채운다)
                    boolean needsHours = p.getOpeningHours() == null || p.getOpeningHours().isEmpty()
                            || OpeningHours.LEGACY_DEFAULT.equals(p.getOpeningHours());
                    if (needsHours) {
                        if (googleMapsService.refreshPlace(p, "ko")) {
                            // 구글에도 영업시간이 없으면 refreshPlace 가 "확인 불가"로 표시해 두므로 다음부터 재조회하지 않는다.
                            placeRepository.save(p);
                        } else {
                            failedPlaceIdsThisSession.add(p.getId());   // 통신 실패: 이번 세션에서는 건너뛴다
                            continue;
                        }
                    }

                    // 2. 테마와 속성이 이미 있다면 AI 호출 스킵
                    boolean hasAttributes = p.getTheme() != null && !p.getTheme().isEmpty()
                            && p.getPlaceType() != null && !p.getPlaceType().isEmpty()
                            && p.getRecommendedDuration() != null;
                    if (hasAttributes) continue;

                    // 3. 리뷰 수집. 리뷰가 없어도 이름·분류만으로 분류를 시도한다
                    //    (리뷰 없는 장소가 영원히 미분류로 남지 않도록)
                    String reviewsText = googleMapsService.getPlaceReviewsById(p.getPlaceId());
                    if (reviewsText == null || reviewsText.trim().isEmpty()) reviewsText = "리뷰 정보 없음";

                    reviewsMap.put(p.getPlaceId(), reviewsText);
                    validPlacesForBulk.add(p);
                }
                if (!enriching.get()) break;

                if (validPlacesForBulk.isEmpty()) {
                    System.out.println("[알림] 이번 배치는 AI 처리가 필요 없습니다. 다음 배치를 탐색합니다.");
                    try { Thread.sleep(2000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); break; }
                    continue;
                }

                // 4. AI 벌크 분류 요청
                Map<String, String> enrichedDataMap = aiService.classifyPlaceAttributesBulk(validPlacesForBulk, reviewsMap);

                if (enrichedDataMap == null || enrichedDataMap.isEmpty()) {
                    System.out.println("[경고] AI 응답이 비었습니다(두 모델 모두 실패. 서버 로그의 [AI 통신 장애 리포트] 확인). 1분(60초) 대기 후 재시도합니다...");
                    try { Thread.sleep(60000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); break; }
                    continue;
                }

                int successCount = 0;
                for (Place place : validPlacesForBulk) {
                    ParsedAttributes parsed = parseAttributes(enrichedDataMap.get(place.getPlaceId()), place);
                    if (parsed == null) {
                        failedPlaceIdsThisSession.add(place.getId()); // 응답 누락·형식 오류는 임시 블랙리스트행
                        continue;
                    }
                    place.setTheme(parsed.theme);
                    place.setPlaceType(parsed.placeType);
                    place.setRecommendedDuration(parsed.duration);
                    // 세부 유형·소개는 같이 왔을 때만 채운다 (이미 있는 값은 덮어쓰지 않는다)
                    if (parsed.subType != null && isBlank(place.getSubType())) place.setSubType(parsed.subType);
                    if (parsed.summary != null && isBlank(place.getSummary())) place.setSummary(parsed.summary);
                    placeRepository.save(place);
                    successCount++;
                }
                System.out.println("[벌크 인리치먼트] " + validPlacesForBulk.size() + "건 중 " + successCount + "건 테마/속성 적재 완료.");

                try {
                    Thread.sleep(aiService.batchPauseMillis(10000));   // OpenAI 가 주 모델이면 2초, Gemini 면 10초
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        } finally {
            enriching.set(false);
        }
        System.out.println("[테마 인리치먼트 종료] 백그라운드 스레드가 안전하게 정지되었습니다.");
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    /**
     * 세부 유형·한 줄 소개 채우기.
     * 테마 인리치먼트가 끝난 기존 장소에는 소개가 없으므로, 소개가 빈 장소만 골라 30건씩 AI 에 보낸다.
     * 기본은 DB 에 있는 정보(이름·도시·분류·테마·주소)만 보내 구글 호출이 없다.
     * withReviews=true 면 장소마다 구글 리뷰를 한 번 조회해 함께 보낸다(정확도는 올라가지만 Places 호출 비용이 든다).
     */
    @Async("adminTaskExecutor")
    public void runSummaryEnrichment(boolean withReviews) {
        if (!describing.compareAndSet(false, true)) return;

        System.out.println("[소개 인리치먼트] 장소 세부 유형·한 줄 소개 채우기를 시작합니다. (리뷰 참조: " + (withReviews ? "사용" : "미사용") + ")");
        Set<Long> failedThisSession = new HashSet<>();
        int total = 0;

        try {
            while (describing.get()) {
                List<Place> targets = new ArrayList<>();
                int pageNum = 0;
                while (targets.isEmpty()) {
                    org.springframework.data.domain.Pageable pageable = org.springframework.data.domain.PageRequest.of(pageNum, 100);
                    List<Place> raw = placeRepository.findPlacesNeedingSummary(pageable);
                    if (raw.isEmpty()) break;
                    targets = raw.stream().filter(p -> !failedThisSession.contains(p.getId())).limit(30).collect(Collectors.toList());
                    if (targets.isEmpty()) pageNum++;   // 이번 100건이 모두 실패 목록이면 다음 100건
                }
                if (targets.isEmpty()) {
                    System.out.println("[소개 인리치먼트 완료] 소개가 비어 있는 장소가 더 없거나, 남은 장소는 이번 실행에서 처리하지 못했습니다.");
                    break;
                }

                Map<String, String> reviewsMap = new HashMap<>();
                if (withReviews) {
                    for (Place p : targets) {
                        if (!describing.get()) break;
                        String reviews = googleMapsService.getPlaceReviewsById(p.getPlaceId());
                        if (reviews != null && !reviews.isBlank() && !reviews.startsWith("리뷰 정보 없음")) reviewsMap.put(p.getPlaceId(), reviews);
                    }
                    if (!describing.get()) break;
                }

                Map<String, String> described = aiService.describePlacesBulk(targets, reviewsMap);
                if (described == null || described.isEmpty()) {
                    System.out.println("[경고] AI 응답이 비었습니다(두 모델 모두 실패. 서버 로그의 [AI 통신 장애 리포트] 확인). 1분(60초) 대기 후 재시도합니다...");
                    try { Thread.sleep(60000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); break; }
                    continue;
                }

                int saved = 0;
                for (Place place : targets) {
                    String[] parsed = parseDescription(described.get(place.getPlaceId()));
                    if (parsed == null) {
                        failedThisSession.add(place.getId());   // 응답 누락·형식 오류: 이번 실행에서는 다시 보내지 않는다
                        continue;
                    }
                    if (parsed[0] != null && isBlank(place.getSubType())) place.setSubType(parsed[0]);
                    place.setSummary(parsed[1]);
                    placeRepository.save(place);
                    saved++;
                }
                total += saved;
                System.out.println("[소개 인리치먼트] " + targets.size() + "건 중 " + saved + "건 저장 (누적 " + total + "건).");

                try { Thread.sleep(aiService.batchPauseMillis(10000)); } catch (InterruptedException e) { Thread.currentThread().interrupt(); break; }
            }
        } finally {
            describing.set(false);
        }
        System.out.println("[소개 인리치먼트 종료] 백그라운드 스레드가 정지되었습니다. (이번 실행 저장 " + total + "건)");
    }

    @Async("adminTaskExecutor")
    public void runCategoryCleansing() {
        if (!cleansing.compareAndSet(false, true)) return;

        System.out.println("[자동 정제 시작] AI 카테고리 자동 분류를 시작합니다...");
        Set<String> allowed = Set.of("관광지", "식음", "쇼핑", "숙소", "교통", "테마파크");
        Set<Long> failedThisSession = new HashSet<>();

        try {
            while (cleansing.get()) {
                // DB에게 카테고리가 빈 데이터를 요청합니다. (이번 세션에서 실패한 것은 제외해 무한 반복을 막는다)
                org.springframework.data.domain.Pageable page = org.springframework.data.domain.PageRequest.of(0, 100);
                List<Place> targetPlaces = placeRepository.findPlacesNeedingCategory(page).stream()
                        .filter(p -> !failedThisSession.contains(p.getId()))
                        .limit(30)
                        .collect(Collectors.toList());

                if (targetPlaces.isEmpty()) {
                    System.out.println("[자동 정제 완료] 처리할 수 있는 데이터의 카테고리 분류가 끝났습니다.");
                    break;
                }

                System.out.println("남은 데이터 정제 중... (현재 " + targetPlaces.size() + "건 처리 시도)");
                Map<String, String> categorizedMap = aiService.cleansePlaceCategories(targetPlaces);

                if (categorizedMap == null || categorizedMap.isEmpty()) {
                    System.out.println("[경고] AI 응답이 비었습니다(두 모델 모두 실패. 서버 로그의 [AI 통신 장애 리포트] 확인). 1분(60s) 대기 후 재시도합니다...");
                    try { Thread.sleep(60000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); break; }
                    continue;
                }

                int updateCount = 0;
                for (Place place : targetPlaces) {
                    String newCategory = categorizedMap.get(place.getPlaceId());
                    if (newCategory != null && allowed.contains(newCategory.trim())) {
                        place.setCategory(newCategory.trim());
                        placeRepository.save(place);
                        updateCount++;
                    } else {
                        failedThisSession.add(place.getId());
                    }
                }
                System.out.println(targetPlaces.size() + "건 중 " + updateCount + "건 업데이트 완료.");

                try {
                    long pause = aiService.batchPauseMillis(30000);   // OpenAI 가 주 모델이면 2초, Gemini 면 30초
                    if (pause >= 10000) System.out.println("API 한도 누적을 방지하기 위해 " + (pause / 1000) + "초간 안전 휴식을 취합니다...");
                    Thread.sleep(pause);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        } finally {
            cleansing.set(false);
        }
        System.out.println("[자동 정제 종료] 백그라운드 정제 스레드가 안전하게 정지되었습니다.");
    }
}
